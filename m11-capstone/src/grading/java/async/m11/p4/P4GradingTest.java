package async.m11.p4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.m11.Grading;

/** P4 졸업 채점: 라이브러리 공개 비동기 API 의 계약(콜백 스레드, commonPool 금지, 타임아웃, 취소, 종료). */
@Timeout(30)
class P4GradingTest {

    /** 전송 계층 대역: Netty I/O 스레드(event-loop-N)가 응답으로 완료시키고, 보낸 요청 future 를 기록한다. */
    static final class RecordingTransport implements Transport {
        final FakeEventLoopClient io = new FakeEventLoopClient("geo-io", 50);
        final List<CompletableFuture<String>> sent = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<String> send(String path) {
            CompletableFuture<String> f = io.fetch(path);
            sent.add(f);
            return f;
        }

        long inFlight() {
            return sent.stream().filter(f -> !f.isDone()).count();
        }
    }

    RecordingTransport transport;
    NamedPool app;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        app = Pools.fixed("app-cb", 2);
    }

    @AfterEach
    void tearDown() {
        transport.io.close();
        app.close();
    }

    /** 사용자 코드처럼: stage 를 받자마자 non-async 콜백을 붙이고, 그 콜백이 실행된 스레드를 기록한다. */
    private static <T> CompletableFuture<String> callbackThread(CompletionStage<T> stage) {
        return stage.handle((v, ex) -> Thread.currentThread().getName()).toCompletableFuture();
    }

    private static void assertNotJdkOrIoThread(String thread, String what) {
        assertThat(thread).as("%s 의 콜백이 %s 에서 실행됐다. 라이브러리는 사용자 콜백을 I/O 스레드, commonPool, "
                        + "JDK 지연 스케줄러에서 실행하면 안 된다 (R1, R10, #18, #19)", what, thread)
                .doesNotStartWith("event-loop")
                .doesNotStartWith("ForkJoinPool")
                .doesNotContain("DelayScheduler")
                .doesNotContain("delayScheduler");
    }

    @Test
    void p4_1_defaultCallbackThread_isTheLibrarysOwnNamedPool_andUserBlockingCannotStallIo() {
        try (GeoClient client = GeoClient.builder(transport).build()) {
            CompletionStage<GeoResult> stage = client.lookup("1.2.3.4");
            CompletableFuture<String> thread = callbackThread(stage);
            assertThat(Grading.await(stage, 2000, "조회")).isEqualTo(new GeoResult("1.2.3.4", "geo-io:/geo/1.2.3.4"));
            assertThat(Grading.await(thread, 2000, "콜백")).as("기본 콜백 스레드는 문서화된 이름 geo-callback-N").startsWith("geo-callback-");

            // 사용자가 콜백에서 실수로 blocking 해도 전송 I/O 스레드는 멈추지 않아야 한다
            List<CompletableFuture<GeoResult>> blocking = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                blocking.add(client.lookup("10.0.0." + i).thenApply(r -> {
                    Sleep.ms(100);
                    return r;
                }).toCompletableFuture());
            }
            for (CompletableFuture<GeoResult> f : blocking) {
                Grading.await(f, 3000, "blocking 콜백 조회");
            }
            assertThat(transport.io.maxLagMillis()).as("사용자 콜백이 전송 I/O 스레드에서 실행되면 I/O 가 멈춘다 (#18)").isLessThan(50);
        }
    }

    @Test
    void p4_2_userExecutor_receivesSuccessAndFailure_withOriginalCause() {
        IllegalStateException boom = new IllegalStateException("upstream 502");
        transport.io.failOn("/geo/6.6.6.6", boom);
        try (GeoClient client = GeoClient.builder(transport).callbackExecutor(app).build()) {
            CompletionStage<GeoResult> ok = client.lookup("1.2.3.4");
            CompletableFuture<String> okThread = callbackThread(ok);
            CompletionStage<GeoResult> bad = client.lookup("6.6.6.6");
            CompletableFuture<String> badThread = callbackThread(bad);

            Grading.await(ok, 2000, "조회");
            assertThat(Grading.await(okThread, 2000, "콜백")).as("성공 콜백은 사용자가 지정한 executor 에서").startsWith("app-cb-");
            assertThat(Grading.awaitFailure(bad, 2000, "실패 조회")).as("실패 원인은 unwrap 하면 전송 계층의 원래 예외").isSameAs(boom);
            assertThat(Grading.await(badThread, 2000, "실패 콜백")).as("실패 콜백도 사용자가 지정한 executor 에서").startsWith("app-cb-");
        }
    }

    @Test
    void p4_3_timeout_failsWithTimeoutException_onTheCallbackExecutor_andCancelsTheRequest() {
        transport.io.latencyFor("/geo/9.9.9.9", 10_000);
        try (GeoClient client = GeoClient.builder(transport).callbackExecutor(app).requestTimeout(Duration.ofMillis(200)).build()) {
            long start = System.nanoTime();
            CompletionStage<GeoResult> stage = client.lookup("9.9.9.9");
            CompletableFuture<String> thread = callbackThread(stage);
            Throwable cause = Grading.awaitFailure(stage, 2000, "타임아웃 조회");
            long took = Grading.since(start);
            assertThat(cause).as("타임아웃 실패 원인 (문서화된 계약)").isInstanceOf(TimeoutException.class);
            assertThat(took).as("requestTimeout 200ms. 실제 %dms", took).isBetween(180L, 400L);
            String t = Grading.await(thread, 1000, "콜백");
            assertNotJdkOrIoThread(t, "타임아웃");
            assertThat(t).as("타임아웃도 사용자가 지정한 executor 로 전달").startsWith("app-cb-");
            assertThat(Grading.pollUntil(() -> transport.inFlight() == 0, 300))
                    .as("시간 초과된 요청은 전송 계층에서 취소돼야 한다 (R10: 취소는 상류로 저절로 전파되지 않는다)").isTrue();
        }
    }

    @Test
    void p4_4_callerCancel_propagatesToTransport_andIsIsolatedPerCall() {
        transport.io.latencyFor("/geo/9.9.9.9", 10_000);
        try (GeoClient client = GeoClient.builder(transport).callbackExecutor(app).requestTimeout(Duration.ofSeconds(5)).build()) {
            CompletionStage<GeoResult> a = client.lookup("9.9.9.9");
            CompletionStage<GeoResult> b = client.lookup("9.9.9.9");
            CompletionStage<GeoResult> c = client.lookup("1.2.3.4");
            assertThat(a).as("호출마다 독립된 stage 를 반환해야 한다 (#17)").isNotSameAs(b);

            a.toCompletableFuture().cancel(true);
            assertThat(Grading.pollUntil(() -> transport.sent.get(0).isCancelled(), 200))
                    .as("사용자가 취소한 조회의 전송 요청도 취소돼야 한다").isTrue();
            assertThat(b.toCompletableFuture().isDone()).as("a 의 취소가 같은 IP 의 다른 호출 b 에 영향을 주면 안 된다").isFalse();
            assertThat(transport.sent.get(1).isCancelled()).isFalse();
            assertThat(Grading.await(c, 2000, "다른 조회").ip()).isEqualTo("1.2.3.4");

            c.toCompletableFuture().obtrudeValue(new GeoResult("x", "hacked"));
            CompletionStage<GeoResult> d = client.lookup("1.2.3.4");
            assertThat(Grading.await(d, 2000, "다음 조회").body()).as("한 호출자가 자기 future 를 조작해도 다른 호출에 보이면 안 된다")
                    .isEqualTo("geo-io:/geo/1.2.3.4");
        }
    }

    @Test
    void p4_5_invalidInput_returnsFailedStage_insteadOfThrowing() {
        try (GeoClient client = GeoClient.builder(transport).callbackExecutor(app).build()) {
            AtomicReference<CompletionStage<GeoResult>> nullIp = new AtomicReference<>();
            assertThatCode(() -> nullIp.set(client.lookup(null))).as("비동기 API 는 입력 오류도 던지지 않고 실패한 stage 로 돌려준다")
                    .doesNotThrowAnyException();
            assertThat(Grading.awaitFailure(nullIp.get(), 1000, "null IP")).isInstanceOf(IllegalArgumentException.class);
            assertThat(Grading.awaitFailure(client.lookup("  "), 1000, "빈 IP")).isInstanceOf(IllegalArgumentException.class);
            assertThat(transport.sent).as("잘못된 입력은 전송하지 않는다").isEmpty();
        }
    }

    @Test
    void p4_6_close_failsPendingLookups_rejectsNewOnes_andStopsLibraryThreads() {
        transport.io.latencyFor("/geo/9.9.9.9", 10_000);
        GeoClient client = GeoClient.builder(transport).build();
        CompletionStage<GeoResult> pending = client.lookup("9.9.9.9");
        Grading.await(client.lookup("1.2.3.4"), 2000, "조회");   // 라이브러리 스레드가 생기게 한다
        client.close();

        assertThat(Grading.awaitFailure(pending, 1000, "close 중 진행 중이던 조회")).isInstanceOf(IllegalStateException.class);
        assertThat(Grading.pollUntil(() -> transport.inFlight() == 0, 300)).as("close 는 진행 중인 전송 요청을 취소한다").isTrue();
        AtomicReference<CompletionStage<GeoResult>> after = new AtomicReference<>();
        assertThatCode(() -> after.set(client.lookup("1.2.3.4"))).doesNotThrowAnyException();
        assertThat(Grading.awaitFailure(after.get(), 1000, "close 후 조회")).isInstanceOf(IllegalStateException.class);
        assertThat(Grading.pollUntil(() -> Grading.liveThreadNames().stream().noneMatch(n -> n.startsWith("geo-")), 1000))
                .as("close 후에도 라이브러리 스레드(geo-*)가 살아 있다: %s", Grading.liveThreadNames().stream()
                        .filter(n -> n.startsWith("geo-")).toList())
                .isTrue();
    }

    @Test
    void p4_7_noCommonPool_inAnyCompletionPath() {
        transport.io.failOn("/geo/6.6.6.6", new IllegalStateException("x"));
        transport.io.latencyFor("/geo/9.9.9.9", 10_000);
        try (GeoClient client = GeoClient.builder(transport).requestTimeout(Duration.ofMillis(150)).build()) {
            List<CompletableFuture<String>> threads = List.of(
                    callbackThread(client.lookup("1.2.3.4")),
                    callbackThread(client.lookup("6.6.6.6")),
                    callbackThread(client.lookup("9.9.9.9")));
            String[] paths = {"성공", "실패", "타임아웃"};
            for (int i = 0; i < threads.size(); i++) {
                String t = Grading.await(threads.get(i), 2000, paths[i] + " 콜백");
                assertNotJdkOrIoThread(t, paths[i]);
                assertThat(t).as("기본 설정에서는 모든 경로가 geo-callback 스레드로 전달").startsWith("geo-callback-");
            }
        }
    }
}
