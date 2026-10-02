package async.m02;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;

/** m02 예측 채점. Predictions 를 모두 채운 뒤 실행한다. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PredictionTest {

    NamedPool io;
    NamedPool small;
    NamedPool a;
    NamedPool b;
    ThreadRecorder rec;

    @BeforeEach
    void setUp() {
        io = Pools.fixed("io", 4);
        small = Pools.fixed("small", 2);
        a = Pools.single("a");
        b = Pools.single("b");
        // 풀 스레드를 미리 띄워 둔다(첫 작업의 스레드 생성 비용이 시간 예측에 섞이지 않도록)
        io.prestartAllCoreThreads();
        small.prestartAllCoreThreads();
        rec = ThreadRecorder.start();
    }

    @AfterEach
    void tearDown() {
        io.close();
        small.close();
        a.close();
        b.close();
    }

    @Test
    void s01_thenApplyReturningFuture() {
        Object joined = Scenarios.s01(rec, io);
        new Grader()
                .check("m02.s01.joinedType", Predictions.S01_JOINED_TYPE, joined.getClass(), "R13")
                .checkMillis("m02.s01.joinedMs", Predictions.S01_JOINED_MS, rec.atMillis("joined"), "R13,R7")
                .verify();
    }

    @Test
    void s02_thenCompose() {
        Scenarios.s02(rec, io);
        new Grader().checkMillis("m02.s02.doneMs", Predictions.S02_DONE_MS, rec.atMillis("done"), "R7,R13").verify();
    }

    @Test
    void s03_startFirstThenCombine() {
        Scenarios.s03(rec, io);
        new Grader().checkMillis("m02.s03.doneMs", Predictions.S03_DONE_MS, rec.atMillis("done"), "R7").verify();
    }

    @Test
    void s04_joinInLoop() {
        Scenarios.s04(rec, io);
        new Grader().checkMillis("m02.s04.doneMs", Predictions.S04_DONE_MS, rec.atMillis("done"), "R7,R5").verify();
    }

    @Test
    void s05_startAllThenJoinInLoop() {
        Scenarios.s05(rec, io);
        new Grader().checkMillis("m02.s05.doneMs", Predictions.S05_DONE_MS, rec.atMillis("done"), "R7,R5").verify();
    }

    @Test
    void s06_poolCap() {
        int maxConcurrency = Scenarios.s06(rec, small);
        new Grader()
                .checkMillis("m02.s06.doneMs", Predictions.S06_DONE_MS, rec.atMillis("done"), "R7")
                .check("m02.s06.maxConcurrency", Predictions.S06_MAX_CONCURRENCY, maxConcurrency, "R7")
                .verify();
    }

    @Test
    void s07_allOfCollect() {
        String result = Scenarios.s07(rec, a, b, io);
        new Grader()
                .check("m02.s07.collect", Predictions.S07_COLLECT, rec.on("collect"), "R1")
                .check("m02.s07.result", Predictions.S07_RESULT, result, "R5")
                .verify();
    }

    @Test
    void s08_validateAndEnrich() {
        String result = Scenarios.s08(rec, io);
        new Grader()
                .check("m02.s08.result", Predictions.S08_RESULT, result, "R6")
                .check("m02.s08.validateRan", Predictions.S08_VALIDATE_RAN, rec.has("validate"), "R6")
                .verify();
    }

    @Test
    void s09_couponAndShipping() {
        new Grader().check("m02.s09.result", Predictions.S09_RESULT, Scenarios.s09(io), "R6").verify();
    }

    @Test
    void s10_callCount() {
        new Grader().check("m02.s10.calls", Predictions.S10_CALLS, Scenarios.s10(io), "R3,R6").verify();
    }

    @Test
    void s11_anyOfFastFailure() {
        int[] r = Scenarios.s11(rec, io);
        new Grader()
                .check("m02.s11.failed", Predictions.S11_FAILED, r[0] == 1, "R13")
                .checkMillis("m02.s11.doneMs", Predictions.S11_DONE_MS, rec.atMillis("done"), "R13")
                .check("m02.s11.dbInFlight", Predictions.S11_DB_IN_FLIGHT, r[1], "R13,R10")
                .verify();
    }

    @Test
    void s12_applyToEither() {
        Scenarios.s12(rec, io);
        new Grader().checkMillis("m02.s12.doneMs", Predictions.S12_DONE_MS, rec.atMillis("done"), "R13,R7").verify();
    }

    @Test
    void s13_combineAlreadyCompleted() {
        Scenarios.s13(rec, a, b);
        new Grader().check("m02.s13.combine", Predictions.S13_COMBINE, rec.on("combine"), "R1").verify();
    }

    @Test
    void s14_joinAfterAllOf() {
        long joinsMs = Scenarios.s14(io);
        new Grader().checkMillis("m02.s14.joinsMs", Predictions.S14_JOINS_MS, joinsMs, "R5").verify();
    }

    @Test
    void s15_dependentThenParallel() {
        Scenarios.s15(rec, io);
        new Grader().checkMillis("m02.s15.doneMs", Predictions.S15_DONE_MS, rec.atMillis("done"), "R7").verify();
    }

    @Test
    void s16_allOfWithEarlyFailure() {
        Scenarios.s16(rec, io);
        new Grader().checkMillis("m02.s16.doneMs", Predictions.S16_DONE_MS, rec.atMillis("done"), "R7,R13").verify();
    }

    @Test
    void s17_thenCombineWithEarlyFailure() {
        Scenarios.s17(rec, io);
        new Grader().checkMillis("m02.s17.doneMs", Predictions.S17_DONE_MS, rec.atMillis("done"), "R7,R13").verify();
    }
}
