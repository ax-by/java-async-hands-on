package async.m06;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.harness.StubHttpServer;
import async.harness.ThreadRecorder;
import async.m06.Scenarios.CancelMode;
import async.m06.Scenarios.Exchange;
import async.m06.Scenarios.FanOut;
import async.m06.support.Cpu;

/** m06 예측 채점. Predictions 를 모두 채운 뒤 실행한다. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PredictionTest {

    StubHttpServer server;
    NamedPool httpWorker;
    NamedPool worker;
    NamedPool jdbc;
    NamedPool reloader;
    FakeEventLoopClient redis;
    ThreadRecorder rec;

    @BeforeEach
    void setUp() {
        BlockHoundSupport.reset();
        server = StubHttpServer.start();
        httpWorker = Pools.fixed("http-worker", 2);
        worker = Pools.fixed("worker", 2);
        jdbc = Pools.fixed("jdbc", 2);
        reloader = Pools.single("reloader");
        redis = new FakeEventLoopClient("redis", 100);
        rec = ThreadRecorder.start();
    }

    @AfterEach
    void tearDown() {
        redis.close();
        server.close();
        httpWorker.close();
        worker.close();
        jdbc.close();
        reloader.close();
    }

    /** 이벤트 루프 위에서 blocking 이 감지됐는가. */
    private static boolean eventLoopViolation() {
        return BlockHoundSupport.violations().stream().anyMatch(v -> v.threadName().startsWith("event-loop-"));
    }

    private static String outcome(CompletableFuture<?> f) {
        try {
            f.get(5, TimeUnit.SECONDS);
            return "OK";
        } catch (Exception e) {
            return Cpu.unwrap(e).getClass().getSimpleName();
        }
    }

    private HttpClient client() {
        return HttpClient.newBuilder().executor(httpWorker).build();
    }

    @Test
    void s01_defaultHttpClientCompletionThread() throws Exception {
        Scenarios.s01(rec, server).get(5, TimeUnit.SECONDS);
        new Grader().check("m06.s01.map", Predictions.S01_MAP, rec.on("map"), "R1").verify();
    }

    @Test
    void s02_customExecutorHttpClient() throws Exception {
        Scenarios.s02(rec, server, httpWorker).get(5, TimeUnit.SECONDS);
        new Grader()
                .check("m06.s02.body", Predictions.S02_BODY, rec.on("body"), "R1")
                .check("m06.s02.map", Predictions.S02_MAP, rec.on("map"), "R1")
                .verify();
    }

    @Test
    void s03_eventLoopClientCallback() throws Exception {
        Scenarios.s03(rec, redis).get(2, TimeUnit.SECONDS);
        new Grader().check("m06.s03.map", Predictions.S03_MAP, rec.on("map"), "R1").verify();
    }

    @Test
    void s04_blockingOnEventLoopWithUnrelatedPing() throws Exception {
        Scenarios.s04(rec, redis, new FakeBlockingService("audit-db", 300)).get(3, TimeUnit.SECONDS);
        new Grader()
                .checkMillis("m06.s04.pingDoneMs", Predictions.S04_PING_DONE_MS, rec.atMillis("pingDone"), "R1,R4")
                .check("m06.s04.violation", Predictions.S04_VIOLATION, eventLoopViolation(), "R4")
                .verify();
    }

    @Test
    void s05_blockingMovedToJdbcPool() throws Exception {
        Scenarios.s05(rec, redis, new FakeBlockingService("audit-db", 300), jdbc).get(3, TimeUnit.SECONDS);
        new Grader()
                .checkMillis("m06.s05.pingDoneMs", Predictions.S05_PING_DONE_MS, rec.atMillis("pingDone"), "R2,R4")
                .check("m06.s05.violation", Predictions.S05_VIOLATION, eventLoopViolation(), "R2,R4")
                .verify();
    }

    @Test
    void s06_callbackOnAlreadyCompletedCachedFuture() throws Exception {
        Scenarios.s06(rec, redis, worker).get(2, TimeUnit.SECONDS);
        new Grader().check("m06.s06.apply", Predictions.S06_APPLY, rec.on("apply"), "R1").verify();
    }

    @Test
    void s07_failurePathAfterAsyncHop() throws Exception {
        assertThat(Scenarios.s07(rec, redis, worker).get(2, TimeUnit.SECONDS)).isEqualTo("empty-cart");
        assertThat(rec.has("parse")).as("시나리오 전제: 실패했으므로 parse 는 실행되지 않는다 (R8)").isFalse();
        new Grader().check("m06.s07.fallback", Predictions.S07_FALLBACK, rec.on("fallback"), "R1,R2,R8").verify();
    }

    @Test
    void s08_completedFutureOfBlockingCall() throws Exception {
        Scenarios.s08(rec, redis, new FakeBlockingService("profile-db", 200)).get(3, TimeUnit.SECONDS);
        new Grader()
                .checkMillis("m06.s08.returnedMs", Predictions.S08_RETURNED_MS, rec.atMillis("returned"), "R3,R4")
                .check("m06.s08.violation", Predictions.S08_VIOLATION, eventLoopViolation(), "R4")
                .verify();
    }

    @Test
    void s09_waitingOnTheSameEventLoop() {
        String actual = outcome(Scenarios.s09(redis));
        new Grader().check("m06.s09.outcome", Predictions.S09_OUTCOME, actual, "R4,R5").verify();
    }

    @Test
    void s10_lockContentionOnEventLoop() throws Exception {
        Scenarios.s10Synchronized(redis, reloader).get(3, TimeUnit.SECONDS);
        boolean syncViolation = eventLoopViolation();
        BlockHoundSupport.reset();
        Scenarios.s10Locked(redis, reloader).get(3, TimeUnit.SECONDS);
        boolean lockViolation = eventLoopViolation();
        new Grader()
                .check("m06.s10.synchronizedViolation", Predictions.S10_SYNCHRONIZED_VIOLATION, syncViolation, "R4")
                .check("m06.s10.lockViolation", Predictions.S10_LOCK_VIOLATION, lockViolation, "R4")
                .verify();
    }

    @Test
    void s11_remoteCallAfterOrTimeout() throws Exception {
        assertThat(Scenarios.s11(redis).get(2, TimeUnit.SECONDS)).isEqualTo("fallback");
        Sleep.ms(400);
        new Grader()
                .check("m06.s11.remoteCompleted", Predictions.S11_REMOTE_COMPLETED, redis.completedCount() == 1, "R10")
                .verify();
    }

    @Test
    void s12_cancellingHttpClientExchange() {
        HttpClient client = client();
        Exchange cancelTrue = Scenarios.s12(server, client, CancelMode.CANCEL_TRUE);
        Exchange cancelFalse = Scenarios.s12(server, client, CancelMode.CANCEL_FALSE);
        Exchange derived = Scenarios.s12(server, client, CancelMode.DERIVED_CANCEL_TRUE);
        Exchange orTimeout = Scenarios.s12(server, client, CancelMode.OR_TIMEOUT);
        // 시나리오 전제: 네 경우 모두 요청은 이미 서버에 도착했다 (취소는 보낸 요청을 되돌리지 못한다)
        assertThat(cancelTrue.serverReceived() && cancelFalse.serverReceived()
                && derived.serverReceived() && orTimeout.serverReceived()).isTrue();
        new Grader()
                .check("m06.s12.cancelTrueIsCancelled", Predictions.S12_CANCEL_TRUE_IS_CANCELLED, cancelTrue.fIsCancelled(), "R8,R10")
                .check("m06.s12.cancelTrueHandler", Predictions.S12_CANCEL_TRUE_HANDLER, cancelTrue.bodyHandlerInvoked(), "R10")
                .check("m06.s12.cancelFalseHandler", Predictions.S12_CANCEL_FALSE_HANDLER, cancelFalse.bodyHandlerInvoked(), "R10")
                .check("m06.s12.derivedCancelHandler", Predictions.S12_DERIVED_CANCEL_HANDLER, derived.bodyHandlerInvoked(), "R10")
                .check("m06.s12.orTimeoutHandler", Predictions.S12_OR_TIMEOUT_HANDLER, orTimeout.bodyHandlerInvoked(), "R10")
                .verify();
    }

    @Test
    void s13_httpRequestTimeout() {
        String actual = outcome(Scenarios.s13(server, client()));
        new Grader().check("m06.s13.outcome", Predictions.S13_OUTCOME, actual, "R8,R10").verify();
    }

    @Test
    void s14_threadEfficiency_A_vs_B() {
        int n = 100;
        FanOut a;
        try (NamedPool io = Pools.fixed("io", 10)) {
            a = Scenarios.s14Blocking(server, client(), io, n);
        }
        FanOut b;
        try (StubHttpServer fresh = StubHttpServer.start()) {
            b = Scenarios.s14NonBlocking(fresh, client(), n);
        }
        System.out.printf("S14 A: %dms, 서버 동시 처리 최대 %d / B: %dms, 서버 동시 처리 최대 %d, http-worker 스레드 %d개%n",
                a.millis(), a.serverMaxConcurrent(), b.millis(), b.serverMaxConcurrent(), httpWorker.largestPoolSize());
        new Grader()
                .checkMillis("m06.s14.aTotalMs", Predictions.S14_A_TOTAL_MS, a.millis(), "R7,R11")
                .check("m06.s14.aServerMax", Predictions.S14_A_SERVER_MAX, a.serverMaxConcurrent(), "R11")
                .check("m06.s14.bServerMax", Predictions.S14_B_SERVER_MAX, b.serverMaxConcurrent(), "R0")
                .check("m06.s14.bUnder400ms", Predictions.S14_B_UNDER_400MS, b.millis() < 400, "R0")
                .verify();
    }
}
