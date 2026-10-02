package async.m09.minikv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.harness.Gate;
import async.harness.On;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.harness.ThreadRecorder;
import async.harness.Timed;
import async.m09.util.Stages;

/**
 * E4~E7 채점: MiniKvClient. (E1~E3 을 먼저 끝내라. 클라이언트가 그것들을 사용한다.)
 *
 * <p>환경: 응답 지연 200ms, "slow" 는 2000ms, 타임아웃 500ms. "broken" 은 IOException, "missing" 은 MiniKvException(NOT_FOUND) 로 실패.
 * I/O 스레드: nonblocking-minikv-io-1, 타이머: minikv-timer-1, completion executor: app-1.
 */
@Timeout(10)
class MiniKvClientTest {

    static final On IO = On.pool(FakeTransport.IO_THREAD_PREFIX);
    static final On TIMER = On.pool("minikv-timer");
    static final On APP = On.pool("app");

    final MiniKvException notFound = new MiniKvException("NOT_FOUND");
    FakeTransport transport;
    ScheduledThreadPoolExecutor timer;
    NamedPool app;
    ThreadRecorder rec;

    @BeforeEach
    void setUp() {
        BlockHoundSupport.reset();
        transport = new FakeTransport(200)
                .latencyFor("slow", 2000)
                .failOn("broken", new IOException("connection reset"))
                .failOn("missing", notFound);
        timer = new ScheduledThreadPoolExecutor(1, Pools.namedFactory("minikv-timer"));
        timer.setRemoveOnCancelPolicy(true);
        app = Pools.single("app");
        rec = ThreadRecorder.start();
    }

    @AfterEach
    void tearDown() {
        transport.close();
        timer.shutdownNow();
        app.close();
    }

    MiniKvClient client() {
        return MiniKvClient.builder(transport).timer(timer).timeout(500, TimeUnit.MILLISECONDS).build();
    }

    MiniKvClient clientWith(Executor completionExecutor) {
        return MiniKvClient.builder(transport).timer(timer).timeout(500, TimeUnit.MILLISECONDS)
                .completionExecutor(completionExecutor).build();
    }

    static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    static Throwable failureOf(CompletionStage<?> stage) throws Exception {
        return stage.handle((v, ex) -> ex).toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    static void assertNotCommonPool(ThreadRecorder rec, String step) {
        assertThat(rec.on(step)).as("[#19, L3] 라이브러리가 commonPool 로 넘기면 안 된다").isNotEqualTo(On.commonPool());
    }

    // ------------------------------------------------------------------ E4

    @Nested
    class E4_Deliver {

        @Test
        void withoutCompletionExecutor_completesOnTheCompletingThread() throws Exception {
            MiniKvClient client = client();
            CompletableFuture<String> raw = new CompletableFuture<>();
            CompletableFuture<String> out = client.deliver(raw);
            CompletableFuture<String> user = out.thenApply(v -> {
                rec.mark("user");
                return v;
            });
            try (NamedPool completer = Pools.single("completer")) {
                completer.execute(() -> raw.complete("v"));
                assertThat(user.get(2, TimeUnit.SECONDS)).isEqualTo("v");
            }
            assertThat(rec.on("user")).as("[L2, R1] completionExecutor 가 없으면 raw 를 완료시킨 스레드에서 바로 완료시킨다. 실제: "
                    + rec.threadName("user")).isEqualTo(On.pool("completer"));
        }

        @Test
        void withCompletionExecutor_alwaysHopsEvenIfRawAlreadyCompleted() throws Exception {
            MiniKvClient client = clientWith(app);
            Gate appBusy = new Gate();
            app.execute(appBusy::await); // (결정성 장치) app 을 잠시 붙잡아 완료 작업이 큐에서 기다리게 한다
            CompletableFuture<String> raw = CompletableFuture.completedFuture("v");
            CompletableFuture<String> out = client.deliver(raw);
            assertThat(out.isDone())
                    .as("[L2] raw 가 이미 완료됐어도 completionExecutor 정책이면 호출 스레드에서 바로 완료시키면 안 된다").isFalse();
            CompletableFuture<String> user = out.thenApply(v -> {
                rec.mark("user");
                return v;
            });
            appBusy.open();
            assertThat(user.get(2, TimeUnit.SECONDS)).isEqualTo("v");
            assertThat(rec.on("user"))
                    .as("[L2] raw 가 이미 완료됐어도 완료 작업은 completionExecutor 로 넘겨야 한다(그래야 out 이 '미완료'인 동안 붙인 콜백이 app 에서 돈다). 실제: "
                            + rec.threadName("user"))
                    .isEqualTo(APP);
        }

        @Test
        void returnsANewFuture_callerCannotCorruptRaw() throws Exception {
            MiniKvClient client = client();
            CompletableFuture<String> raw = new CompletableFuture<>();
            CompletableFuture<String> a = client.deliver(raw);
            CompletableFuture<String> b = client.deliver(raw);
            assertThat(a).as("[L1] raw 를 그대로 반환하면 안 된다").isNotSameAs(raw).isNotSameAs(b);
            a.complete("hacked");
            a.cancel(true);
            raw.complete("v");
            assertThat(raw.join()).isEqualTo("v");
            assertThat(b.get(2, TimeUnit.SECONDS)).as("[L1] 다른 호출자는 영향받지 않는다").isEqualTo("v");
        }

        @Test
        void failureIsTranslatedAndUnwrapped() throws Exception {
            MiniKvClient client = client();
            CompletableFuture<String> raw = new CompletableFuture<>();
            CompletableFuture<String> out = client.deliver(raw);
            IOException reset = new IOException("reset");
            raw.completeExceptionally(new CompletionException(reset));
            Throwable seen = failureOf(out);
            assertThat(seen).as("[L6, R8] 사용자가 바로 붙인 handle 은 MiniKvException 을 직접 받아야 한다 (Errors.toPublic 사용)")
                    .isExactlyInstanceOf(MiniKvException.class);
            assertThat(seen.getCause()).isSameAs(reset);
        }

        @Test
        void rejectedByCompletionExecutor_stillCompletes() throws Exception {
            NamedPool dead = Pools.single("dead");
            dead.shutdown();
            MiniKvClient client = clientWith(dead);
            CompletableFuture<String> raw = new CompletableFuture<>();
            CompletableFuture<String> out = client.deliver(raw);
            raw.complete("v");
            assertThat(out.get(2, TimeUnit.SECONDS))
                    .as("[L2] completionExecutor 가 거절해도(RejectedExecutionException) 결과를 잃으면 안 된다. 그 자리에서 완료시켜라")
                    .isEqualTo("v");
            assertThat(dead.rejectedCount()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ E5

    @Nested
    class E5_GetAsync {

        @Test
        void returnsImmediately_noFakeAsync() throws Exception {
            MiniKvClient client = client();
            Timed<CompletionStage<String>> call = Timed.of(() -> client.getAsync("k"));
            assertThat(call.millis()).as("[L4, #1] getAsync 는 응답(200ms)을 기다리지 않고 즉시 반환해야 한다(가짜 async 금지)").isLessThan(100);
            assertThat(await(call.value())).isEqualTo("val:k");
        }

        @Test
        void callableFromANonBlockingThread() throws Exception {
            assertThat(BlockHoundSupport.isInstalled()).as("BlockHound 설치 실패: " + BlockHoundSupport.installFailure()).isTrue();
            MiniKvClient client = client();
            CompletionStage<String> stage;
            try (NamedPool nonBlocking = Pools.single("nonblocking-caller")) {
                stage = nonBlocking.submit(() -> client.getAsync("k")).get(2, TimeUnit.SECONDS);
            }
            assertThat(await(stage)).isEqualTo("val:k");
            assertThat(BlockHoundSupport.violations())
                    .as("[L4, R4] 이벤트 루프 같은 non-blocking 스레드에서 getAsync 를 불러도 막히면 안 된다").isEmpty();
        }

        @Test
        void defaultPolicy_userCallbackRunsOnIoThread() throws Exception {
            MiniKvClient client = client();
            CompletionStage<String> user = client.getAsync("k").thenApply(v -> {
                rec.mark("user");
                return v;
            });
            assertThat(await(user)).isEqualTo("val:k");
            assertThat(rec.on("user")).as("[L2, R1] 기본 정책: I/O 스레드가 완료시킨다 (Javadoc 에 문서화된 대로). 실제: "
                    + rec.threadName("user")).isEqualTo(IO);
        }

        @Test
        void completionExecutorPolicy_userCallbackRunsOnApp_evenOnTimeout() throws Exception {
            MiniKvClient client = clientWith(app);
            CompletionStage<String> ok = client.getAsync("k").thenApply(v -> {
                rec.mark("ok");
                return v;
            });
            CompletionStage<Throwable> timedOut = client.getAsync("slow").handle((v, ex) -> {
                rec.mark("timeout");
                return ex;
            });
            assertThat(await(ok)).isEqualTo("val:k");
            assertThat(await(timedOut)).isInstanceOf(MiniKvTimeoutException.class);
            assertThat(rec.on("ok")).as("[L2] completionExecutor 정책: 응답도 app 에서 완료").isEqualTo(APP);
            assertThat(rec.on("timeout")).as("[L2, R10] completionExecutor 정책: 타임아웃도 app 에서 완료(타이머 스레드 보호)").isEqualTo(APP);
        }

        @Test
        void defaultPolicy_timeoutCompletesOnTimerThread() throws Exception {
            MiniKvClient client = client();
            CompletionStage<Throwable> timedOut = client.getAsync("slow").handle((v, ex) -> {
                rec.mark("timeout");
                return ex;
            });
            assertThat(await(timedOut)).isInstanceOf(MiniKvTimeoutException.class);
            assertThat(rec.on("timeout")).as("[R1, R10] 기본 정책에서 타임아웃은 타이머 스레드가 완료시킨다").isEqualTo(TIMER);
            assertNotCommonPool(rec, "timeout");
        }

        @Test
        void cancellationReachesTheTransport_andTimerIsCleaned() throws Exception {
            MiniKvClient client = client();
            CompletionStage<String> stage = client.getAsync("k");
            assertThat(transport.inFlight()).isEqualTo(1);

            boolean cancelled = stage.toCompletableFuture().cancel(true);

            assertThat(cancelled).isTrue();
            assertThat(transport.abortedCount())
                    .as("[L5, R10] 사용자가 stage 를 취소하면 하부 요청을 abort 해야 한다. CF.cancel 은 상류로 전파되지 않는다").isEqualTo(1);
            assertThat(transport.inFlight()).isZero();
            assertThat(timer.getQueue().size())
                    .as("[L5] 취소된 요청의 타임아웃 타이머도 정리돼야 한다(raw 를 취소하면 failAfter 의 whenComplete 가 타이머를 지운다)").isZero();
            Sleep.ms(300);
            assertThat(transport.responseCount()).as("[L5] 취소된 요청은 응답이 전달되면 안 된다").isZero();
        }

        @Test
        void timeoutAbortsTheTransportRequest() throws Exception {
            MiniKvClient client = client();
            long start = System.nanoTime();
            Throwable seen = failureOf(client.getAsync("slow"));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(seen).as("[L6] 타임아웃은 MiniKvTimeoutException 으로 (감싸지 않고) 전달된다").isExactlyInstanceOf(MiniKvTimeoutException.class);
            assertThat(elapsed).as("타임아웃 500ms").isBetween(400L, 1500L);
            // abort 는 사용자 stage 와 다른 분기(R6)에서 타이머 스레드가 실행하므로, 사용자가 실패를 본 직후에는 아직일 수 있다. 잠깐 기다린다.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while (transport.abortedCount() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(transport.abortedCount()).as("[L5] 타임아웃되면 하부 요청도 취소한다(start 의 onTimeout)").isEqualTo(1);
        }

        @Test
        void successLeavesNoTimerBehind() throws Exception {
            MiniKvClient client = client();
            List<CompletionStage<String>> stages = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                stages.add(client.getAsync("k" + i));
            }
            assertThat(timer.getQueue().size()).as("요청마다 타이머가 하나씩 걸린다").isEqualTo(20);
            for (CompletionStage<String> s : stages) {
                await(s);
            }
            // 타이머 정리는 raw 의 다른 의존 단계라서 사용자 stage 보다 아주 조금 늦게 실행될 수 있다(R6). 잠깐 기다린다.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while (timer.getQueue().size() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(timer.getQueue().size()).as("[L5] 성공한 요청의 타이머가 큐에 남으면 누수다(10초짜리 타이머라면 10초 동안)").isZero();
        }

        @Test
        void exceptionContract() throws Exception {
            MiniKvClient client = client();

            Throwable broken = failureOf(client.getAsync("broken"));
            assertThat(broken).as("[L6] IOException → MiniKvException, 감싸지 않고 전달").isExactlyInstanceOf(MiniKvException.class);
            assertThat(broken.getCause()).isInstanceOf(IOException.class);

            assertThat(failureOf(client.getAsync("missing"))).as("[L6] 이미 공개 예외면 같은 객체 그대로").isSameAs(notFound);

            CompletableFuture<String> joined = client.getAsync("missing").toCompletableFuture();
            assertThatThrownBy(joined::join).as("[R8] join 은 JDK 계약대로 CompletionException(원인) — 라이브러리가 바꿀 수 없다")
                    .isExactlyInstanceOf(CompletionException.class).hasCause(notFound);
            assertThatThrownBy(() -> client.getAsync("missing").toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .as("[R8] get 은 ExecutionException(원인)").isExactlyInstanceOf(ExecutionException.class).hasCause(notFound);
        }

        @Test
        void eachCallGetsItsOwnStage() {
            MiniKvClient client = client();
            CompletionStage<String> a = client.getAsync("k");
            CompletionStage<String> b = client.getAsync("k");
            assertThat(a).isNotSameAs(b);
            assertThat(a.toCompletableFuture()).as("[L1] Java 8 에서는 CF 로 꺼낼 수 있다. 그래서 '그 사용자 전용' 이어야 한다").isSameAs(a);
            a.toCompletableFuture().cancel(true);
            b.toCompletableFuture().join();
        }
    }

    // ------------------------------------------------------------------ E6

    @Nested
    class E6_GetShared {

        @Test
        void concurrentCallsShareOneRequest() throws Exception {
            MiniKvClient client = client();
            int callers = 16;
            CountDownLatch go = new CountDownLatch(1);
            List<Future<CompletionStage<String>>> submitted = new ArrayList<>();
            try (NamedPool pool = Pools.fixed("callers", callers)) {
                for (int i = 0; i < callers; i++) {
                    submitted.add(pool.submit(() -> {
                        go.await();
                        return client.getShared("k");
                    }));
                }
                go.countDown();
                List<CompletionStage<String>> stages = new ArrayList<>();
                for (Future<CompletionStage<String>> f : submitted) {
                    stages.add(f.get(2, TimeUnit.SECONDS));
                }
                for (CompletionStage<String> s : stages) {
                    assertThat(await(s)).isEqualTo("val:k");
                }
                assertThat(stages.stream().distinct().count()).as("[L1, L7] 호출자마다 독립된 stage").isEqualTo(callers);
            }
            assertThat(transport.requestCount()).as("[L7] 같은 키 동시 조회 16번은 요청 1개로 합쳐져야 한다(원자적 putIfAbsent/computeIfAbsent)")
                    .isEqualTo(1);
        }

        @Test
        void oneCallerCancelling_doesNotAffectOthersOrTheSharedRequest() throws Exception {
            MiniKvClient client = client();
            CompletionStage<String> a = client.getShared("k");
            CompletionStage<String> b = client.getShared("k");
            a.toCompletableFuture().cancel(true);
            assertThat(await(b)).as("[L7, L1] A 의 취소가 B 에게 전파되면 안 된다").isEqualTo("val:k");
            assertThat(transport.abortedCount()).as("[L7] 공유 요청은 한 호출자의 취소로 abort 하면 안 된다").isZero();
            assertThat(transport.requestCount()).isEqualTo(1);
        }

        @Test
        void oneCallerCompleting_doesNotAffectOthers() throws Exception {
            MiniKvClient client = client();
            CompletionStage<String> a = client.getShared("k");
            CompletionStage<String> b = client.getShared("k");
            a.toCompletableFuture().complete("hacked");
            assertThat(await(b)).as("[L1] 호출자 B 는 A 의 complete 와 무관하게 실제 응답 값을 받아야 한다(호출자별 독립 stage)").isEqualTo("val:k");
        }

        @Test
        void entryIsRemovedBeforeCallersAreNotified() throws Exception {
            MiniKvClient client = client();
            int seenInCallback = await(client.getShared("k").thenApply(v -> client.inFlightKeyCount()));
            assertThat(seenInCallback)
                    .as("[L7] 결과를 받은 콜백에서 같은 키를 다시 조회하면 새 요청이 나가야 한다. 공유 future 를 완료시키기 '전에' 맵에서 제거하라")
                    .isZero();
            await(client.getShared("k"));
            assertThat(transport.requestCount()).as("[L7] 완료된 뒤의 호출은 새 요청").isEqualTo(2);
        }

        @Test
        void failureIsNotCachedForever() throws Exception {
            MiniKvClient client = client();
            CompletionStage<String> a = client.getShared("broken");
            CompletionStage<String> b = client.getShared("broken");
            assertThat(failureOf(a)).isExactlyInstanceOf(MiniKvException.class);
            assertThat(failureOf(b)).as("[L6] 공유 요청의 실패도 각 호출자에게 공개 예외로").isExactlyInstanceOf(MiniKvException.class);
            assertThat(client.inFlightKeyCount()).as("[L7] 실패해도 맵에서 제거해야 한다").isZero();
            failureOf(client.getShared("broken"));
            assertThat(transport.requestCount()).as("[L7] 실패 뒤의 호출은 재시도(새 요청)여야 한다. 실패를 캐시하면 장애가 영구화된다").isEqualTo(2);
        }

        @Test
        void timeoutIsSharedAndCleared() throws Exception {
            MiniKvClient client = client();
            CompletionStage<String> a = client.getShared("slow");
            CompletionStage<String> b = client.getShared("slow");
            assertThat(failureOf(a)).isExactlyInstanceOf(MiniKvTimeoutException.class);
            assertThat(failureOf(b)).isExactlyInstanceOf(MiniKvTimeoutException.class);
            assertThat(client.inFlightKeyCount()).as("[L7] 타임아웃도 완료다. 맵에서 제거").isZero();
            assertThat(transport.requestCount()).isEqualTo(1);
        }

        @Test
        void completionPolicyAppliesToSharedCallers() throws Exception {
            MiniKvClient client = clientWith(app);
            CompletionStage<String> user = client.getShared("k").thenApply(v -> {
                rec.mark("user");
                return v;
            });
            assertThat(await(user)).isEqualTo("val:k");
            assertThat(rec.on("user")).as("[L2] getShared 도 같은 완료 정책(deliver)을 따른다").isEqualTo(APP);
        }
    }

    // ------------------------------------------------------------------ E7

    @Nested
    class E7_SyncGet {

        @Test
        void returnsValue() {
            MiniKvClient client = client();
            Timed<String> t = Timed.of(() -> client.get("k"));
            assertThat(t.value()).isEqualTo("val:k");
            assertThat(t.millis()).as("[R5] sync API 는 응답(200ms)까지 기다린다").isBetween(150L, 600L);
        }

        @Test
        void throwsPublicExceptionDirectly() {
            MiniKvClient client = client();
            assertThatThrownBy(() -> client.get("broken"))
                    .as("[L6] sync API 는 ExecutionException/CompletionException 이 아니라 MiniKvException 을 그대로 던진다")
                    .isExactlyInstanceOf(MiniKvException.class)
                    .hasCauseInstanceOf(IOException.class);
            assertThatThrownBy(() -> client.get("missing")).isSameAs(notFound);
            assertThatThrownBy(() -> client.get("slow")).as("[L6] 타임아웃").isExactlyInstanceOf(MiniKvTimeoutException.class);
        }

        @Test
        void onIoThread_failsFastInsteadOfDeadlocking() throws Exception {
            assertThat(BlockHoundSupport.isInstalled()).as("BlockHound 설치 실패: " + BlockHoundSupport.installFailure()).isTrue();
            MiniKvClient client = client();
            long start = System.nanoTime();
            Throwable seen = failureOf(client.getAsync("a").thenApply(v -> client.get("b")));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(Stages.unwrap(seen))
                    .as("[L4] I/O 스레드에서 sync get 을 부르면 즉시 IllegalStateException. 기다리면 응답을 전달할 스레드가 없어 deadlock(타임아웃까지 멈춤)")
                    .isExactlyInstanceOf(IllegalStateException.class);
            assertThat(elapsed).as("[L4] 'a' 응답(200ms) 직후 바로 실패해야 한다. 타임아웃(500ms)까지 기다렸다면 I/O 스레드를 막은 것").isLessThan(450L);
            assertThat(BlockHoundSupport.violations()).as("[R4] I/O 스레드 위에서 blocking 이 일어나면 안 된다").isEmpty();
        }

        @Test
        void onOwnedTimerThread_failsFast() throws Exception {
            MiniKvClient client = MiniKvClient.builder(transport).timeout(100, TimeUnit.MILLISECONDS).build(); // 타이머를 라이브러리가 소유
            try {
                String outcome = await(client.getAsync("slow").handle((v, ex) -> {
                    rec.mark("timeout");
                    try {
                        return client.get("k");
                    } catch (IllegalStateException rejected) {
                        return "rejected";
                    }
                }));
                assertThat(rec.on("timeout")).isEqualTo(On.pool(MiniKvClient.TIMER_THREAD_PREFIX));
                assertThat(outcome).as("[L4, R10] 라이브러리 타이머 스레드에서 blocking 하면 모든 타임아웃이 밀린다. 즉시 거절하라").isEqualTo("rejected");
            } finally {
                client.close();
            }
        }

        @Test
        void onSingleThreadCompletionExecutor_doesNotDeadlock() throws Exception {
            MiniKvClient client = clientWith(app);
            long start = System.nanoTime();
            String v = await(client.getAsync("a").thenApply(x -> client.get("b")));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(v).as("[L4, R11] app(스레드 1개) 위에서 sync get 을 불러도 값이 나와야 한다. "
                    + "getAsync(..).join() 으로 만들면 완료 작업이 app 큐에서 영원히 기다린다 → raw 를 직접 기다려라").isEqualTo("val:b");
            assertThat(elapsed).as("200ms + 200ms").isLessThan(900L);
        }

        @Test
        void interruptRestoresFlagAndAbortsRequest() {
            MiniKvClient client = client();
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> client.get("k")).as("[L6] interrupt 도 MiniKvException 으로").isInstanceOf(MiniKvException.class);
                assertThat(Thread.interrupted()).as("interrupt 상태를 복원해야 한다(삼키지 마라)").isTrue();
                assertThat(transport.abortedCount()).as("[L5] 더 기다리지 않을 요청은 abort 한다").isEqualTo(1);
            } finally {
                Thread.interrupted();
            }
        }
    }
}
