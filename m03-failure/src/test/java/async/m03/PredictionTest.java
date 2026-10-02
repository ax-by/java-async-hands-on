package async.m03;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;

/** m03 예측 채점. Predictions 를 모두 채운 뒤 실행한다. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PredictionTest {

    NamedPool io;
    ThreadRecorder rec;

    @BeforeEach
    void setUp() {
        io = Pools.fixed("io", 4);
        io.prestartAllCoreThreads();
        rec = ThreadRecorder.start();
    }

    @AfterEach
    void tearDown() {
        io.close();
    }

    @Test
    void s01_failingPipeline() {
        String stages = Scenarios.s01(rec, io);
        new Grader().check("m03.s01.stages", Predictions.S01_STAGES, stages, "R8").verify();
    }

    @Test
    void s02_supplyAsyncFailure() {
        new Grader().check("m03.s02.exType", Predictions.S02_EX_TYPE, Scenarios.s02(io), "R8").verify();
    }

    @Test
    void s03_completeExceptionallyDirectVsAfterStage() {
        Class<?>[] seen = Scenarios.s03();
        new Grader()
                .check("m03.s03.direct", Predictions.S03_DIRECT, seen[0], "R8")
                .check("m03.s03.afterMap", Predictions.S03_AFTER_MAP, seen[1], "R8")
                .verify();
    }

    @Test
    void s04_getVsJoin() {
        Class<?>[] seen = Scenarios.s04(io);
        new Grader()
                .check("m03.s04.getCause", Predictions.S04_GET_CAUSE, seen[0], "R8")
                .check("m03.s04.joinThrows", Predictions.S04_JOIN_THROWS, seen[1], "R8")
                .verify();
    }

    @Test
    void s05_instanceofCheck() {
        new Grader().check("m03.s05.outcome", Predictions.S05_OUTCOME, Scenarios.s05(io), "R8").verify();
    }

    @Test
    void s06_whenCompleteFallback() {
        new Grader().check("m03.s06.outcome", Predictions.S06_OUTCOME, Scenarios.s06(rec, io), "R9").verify();
    }

    @Test
    void s07_whenCompleteActionThrowsOnFailure() {
        Object[] r = Scenarios.s07(io);
        new Grader()
                .check("m03.s07.outcome", Predictions.S07_OUTCOME, r[0], "R9")
                .check("m03.s07.suppressed", Predictions.S07_SUPPRESSED, r[1], "R9")
                .verify();
    }

    @Test
    void s08_whenCompleteActionThrowsOnSuccess() {
        new Grader().check("m03.s08.outcome", Predictions.S08_OUTCOME, Scenarios.s08(io), "R9").verify();
    }

    @Test
    void s09_recoveryPlacement() {
        String[] r = Scenarios.s09(io);
        new Grader()
                .check("m03.s09.recoverFirst", Predictions.S09_RECOVER_FIRST, r[0], "R9")
                .check("m03.s09.recoverLast", Predictions.S09_RECOVER_LAST, r[1], "R9")
                .verify();
    }

    @Test
    void s10_orTimeout() {
        Object[] r = Scenarios.s10(io);
        new Grader()
                .check("m03.s10.exType", Predictions.S10_EX_TYPE, r[0], "R8,R10")
                .check("m03.s10.inFlight", Predictions.S10_IN_FLIGHT, r[1], "R10")
                .verify();
    }

    @Test
    void s11_completeOnTimeout() {
        String source = Scenarios.s11(rec, io);
        new Grader()
                .check("m03.s11.render", Predictions.S11_RENDER, rec.on("render"), "R10,R1")
                .check("m03.s11.sourceValue", Predictions.S11_SOURCE_VALUE, source, "R10,R6")
                .verify();
    }

    @Test
    void s12_getWithTimeout() throws Exception {
        boolean doneAfter = Scenarios.s12(rec, io);
        new Grader()
                .checkMillis("m03.s12.timedOutMs", Predictions.S12_TIMED_OUT_MS, rec.atMillis("timedOut"), "R5,R10")
                .check("m03.s12.doneAfter", Predictions.S12_DONE_AFTER, doneAfter, "R10")
                .verify();
    }

    @Test
    void s13_cfCancel() {
        new Grader().check("m03.s13.interrupted", Predictions.S13_INTERRUPTED, Scenarios.s13(io), "R10").verify();
    }

    @Test
    void s14_cancelDownstream() {
        new Grader().check("m03.s14.upstreamCancelled", Predictions.S14_UPSTREAM_CANCELLED, Scenarios.s14(io), "R10").verify();
    }

    @Test
    void s15_dependentOfCancelled() {
        Object[] r = Scenarios.s15(io);
        new Grader()
                .check("m03.s15.depJoinThrows", Predictions.S15_DEP_JOIN_THROWS, r[0], "R8,R10")
                .check("m03.s15.depIsCancelled", Predictions.S15_DEP_IS_CANCELLED, r[1], "R10")
                .verify();
    }

    @Test
    void s16_executorFutureCancel() {
        new Grader().check("m03.s16.interrupted", Predictions.S16_INTERRUPTED, Scenarios.s16(io), "R10").verify();
    }
}
