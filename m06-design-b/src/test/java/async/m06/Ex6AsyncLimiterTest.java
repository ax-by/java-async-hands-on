package async.m06;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.StubHttpServer;
import async.m06.support.Cpu;
import async.m06.support.IoThreadHttpClient;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class Ex6AsyncLimiterTest {

    StubHttpServer server;
    IoThreadHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = StubHttpServer.start();
        client = IoThreadHttpClient.create();
        server.route("/warmup", 0, 200, "w");
        client.sendAsync(HttpRequest.newBuilder(server.uri("/warmup")).build(), BodyHandlers.ofString()).get(5, TimeUnit.SECONDS);
        BlockHoundSupport.reset();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    void limitsConcurrentRemoteCalls_withoutBlockingTheSubmitter() throws Exception {
        server.route("/quote", 100, 200, "q");
        HttpRequest request = HttpRequest.newBuilder(server.uri("/quote")).build();
        Ex6AsyncLimiter limiter = new Ex6AsyncLimiter(10);
        List<CompletableFuture<String>> results = new ArrayList<>();
        long[] submitMillis = new long[1];
        long start = System.nanoTime();
        // 이벤트 루프처럼 "막히면 안 되는" 스레드에서 50건을 제출한다
        try (NamedPool submitter = Pools.single("nonblocking-submitter")) {
            submitter.submit(() -> {
                long s = System.nanoTime();
                for (int i = 0; i < 50; i++) {
                    results.add(limiter.submit(() -> client.sendAsync(request, BodyHandlers.ofString())
                            .thenApply(HttpResponse::body)));
                }
                submitMillis[0] = (System.nanoTime() - s) / 1_000_000;
            }).get(5, TimeUnit.SECONDS);
        }
        for (CompletableFuture<String> r : results) {
            assertThat(r.get(5, TimeUnit.SECONDS)).isEqualTo("q");
        }
        long total = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("한도 10, 50건 × 100ms: 총 %dms, 서버 동시 처리 최대 %d, 제출 %dms%n",
                total, server.maxConcurrentRequests(), submitMillis[0]);

        assertThat(BlockHoundSupport.violations())
                .as("submit 이나 허가 반납 과정에서 blocking 이 일어났다 (Semaphore.acquire 같은 blocking 대기 금지, R4)")
                .isEmpty();
        assertThat(submitMillis[0]).as("50건 제출은 즉시 끝나야 한다. submit 이 기다렸다 (R3)").isLessThan(100);
        assertThat(server.maxConcurrentRequests())
                .as("원격 서버가 동시에 받은 요청이 한도(10)를 넘었다. 허가 전에 call 을 호출했는가? (R3)")
                .isLessThanOrEqualTo(10)
                .isGreaterThanOrEqualTo(5);
        assertThat(total).as("50건 / 동시 10 × 100ms ≈ 500ms (R7)").isBetween(400L, 1200L);
        assertThat(client.sendCalls()).as("blocking send() 금지").isZero();
        assertThat(limiter.inFlight()).as("모두 끝나면 진행 중은 0. 허가 반납이 누락됐다").isZero();
        assertThat(limiter.queued()).isZero();
    }

    @Test
    void callIsInvokedOnlyAfterPermit() throws Exception {
        TestSupport.within2s("submit 이 호출 스레드를 막았다(Semaphore.acquire 같은 blocking 대기) 또는 허가가 반납되지 않았다 (R4)", () -> {
            Ex6AsyncLimiter limiter = new Ex6AsyncLimiter(2);
            AtomicInteger started = new AtomicInteger();
            List<CompletableFuture<String>> remotes = new ArrayList<>();
            List<CompletableFuture<String>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                results.add(limiter.submit(() -> {
                    started.incrementAndGet();
                    CompletableFuture<String> remote = new CompletableFuture<>();
                    remotes.add(remote);
                    return remote;
                }));
            }
            assertThat(started.get()).as("허가는 2개뿐이므로 call 은 2번만 호출돼야 한다 (future 를 만들면 작업이 시작된다, R3)")
                    .isEqualTo(2);
            assertThat(limiter.inFlight()).isEqualTo(2);
            assertThat(limiter.queued()).isEqualTo(3);

            remotes.get(0).complete("r0");
            assertThat(results.get(0)).isCompletedWithValue("r0");
            assertThat(started.get()).as("하나가 끝나면 대기 중이던 다음 call 이 시작돼야 한다").isEqualTo(3);
            assertThat(limiter.queued()).isEqualTo(2);

            remotes.get(1).completeExceptionally(new IllegalStateException("remote failed"));
            assertThat(started.get()).as("실패로 끝나도 허가는 반납돼야 한다").isEqualTo(4);
            assertThat(Cpu.unwrap(catchThrowable(() -> results.get(1).join())))
                    .as("call 의 실패는 결과 future 로 전달돼야 한다 (R8)")
                    .isInstanceOf(IllegalStateException.class);
            remotes.get(2).complete("r2");
            assertThat(started.get()).isEqualTo(5);
            assertThat(limiter.queued()).isZero();
            remotes.get(3).complete("r3");
            remotes.get(4).complete("r4");
            assertThat(results.get(4)).isCompletedWithValue("r4");
            assertThat(limiter.inFlight()).isZero();
        });
    }

    @Test
    void supplierThrowing_failsTheFuture_andReleasesThePermit() throws Exception {
        Ex6AsyncLimiter limiter = new Ex6AsyncLimiter(1);
        IllegalArgumentException bad = new IllegalArgumentException("bad request");
        CompletableFuture<String> failed = limiter.submit(() -> {
            throw bad;
        });
        assertThat(Cpu.unwrap(catchThrowable(() -> failed.get(1, TimeUnit.SECONDS))))
                .as("Supplier 가 던진 예외는 submit 이 던지지 않고 결과 future 의 실패로 전달한다")
                .isSameAs(bad);
        assertThat(TestSupport.await(limiter.submit(() -> CompletableFuture.completedFuture("ok")), 1,
                "Supplier 가 예외를 던졌을 때 허가가 반납되지 않아 다음 call 이 시작되지 않았다"))
                .as("예외가 나도 허가가 반납돼야 다음 call 이 실행된다")
                .isEqualTo("ok");
        assertThat(limiter.inFlight()).isZero();
    }

    @Test
    void manyImmediatelyCompletingCalls_doNotOverflowTheStack() throws Exception {
        TestSupport.within2s("submit 이 호출 스레드를 막았다(Semaphore.acquire 같은 blocking 대기) 또는 허가가 반납되지 않았다 (R4)", () -> {
            Ex6AsyncLimiter limiter = new Ex6AsyncLimiter(1);
            CompletableFuture<String> gate = new CompletableFuture<>();
            CompletableFuture<String> first = limiter.submit(() -> gate);
            int n = 20_000;
            List<CompletableFuture<Integer>> rest = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int v = i;
                rest.add(limiter.submit(() -> CompletableFuture.completedFuture(v)));
            }
            assertThat(limiter.queued()).isEqualTo(n);
            gate.complete("open"); // 이 순간 대기 중인 2만 개가 차례로 시작되고 즉시 끝난다
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("open");
            for (int i = 0; i < n; i++) {
                CompletableFuture<Integer> r = rest.get(i);
                assertThat(r.isDone() && !r.isCompletedExceptionally())
                        .as("%d 번째 call 이 정상 완료되지 않았다. 완료 콜백 → 반납 → 다음 시작이 재귀로 쌓여 "
                                + "StackOverflowError 가 났을 가능성이 높다. drain 루프를 한 곳에서만 돌려라", i)
                        .isTrue();
            }
            assertThat(limiter.inFlight()).isZero();
            assertThat(limiter.queued()).isZero();
        });
    }
}
