package async.m01;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;

/** m01 예측 채점. Predictions 를 모두 채운 뒤 실행한다. */
class PredictionTest {

    NamedPool io;
    NamedPool cpu;
    NamedPool completer;
    NamedPool a;
    NamedPool b;
    ThreadRecorder rec;

    @BeforeEach
    void setUp() {
        io = Pools.fixed("io", 2);
        cpu = Pools.fixed("cpu", 2);
        completer = Pools.single("completer");
        a = Pools.single("a");
        b = Pools.single("b");
        rec = ThreadRecorder.start();
    }

    @AfterEach
    void tearDown() {
        io.close();
        cpu.close();
        completer.close();
        a.close();
        b.close();
    }

    @Test
    void s01_incompleteFutureCallback() throws Exception {
        Scenarios.s01(rec, io).get(2, TimeUnit.SECONDS);
        new Grader()
                .check("m01.s01.supply", Predictions.S01_SUPPLY, rec.on("supply"), "R3")
                .check("m01.s01.map", Predictions.S01_MAP, rec.on("map"), "R1")
                .verify();
    }

    @Test
    void s02_completedFutureCallback() throws Exception {
        Scenarios.s02(rec, io).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s02.map", Predictions.S02_MAP, rec.on("map"), "R1").verify();
    }

    @Test
    void s03_asyncWithoutExecutor() throws Exception {
        Scenarios.s03(rec, io).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s03.map", Predictions.S03_MAP, rec.on("map"), "R2").verify();
    }

    @Test
    void s04_asyncWithExecutor() throws Exception {
        Scenarios.s04(rec, io, cpu).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s04.map", Predictions.S04_MAP, rec.on("map"), "R2").verify();
    }

    @Test
    void s05_completedByAnotherPool() throws Exception {
        Scenarios.s05(rec, completer).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s05.map", Predictions.S05_MAP, rec.on("map"), "R1").verify();
    }

    @Test
    void s06_completedByCaller() throws Exception {
        Scenarios.s06(rec).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s06.map", Predictions.S06_MAP, rec.on("map"), "R1").verify();
    }

    @Test
    void s07_thenCombine() throws Exception {
        Scenarios.s07(rec, a, b).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s07.combine", Predictions.S07_COMBINE, rec.on("combine"), "R1").verify();
    }

    @Test
    void s08_anyOf() throws Exception {
        Scenarios.s08(rec, a, b).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s08.first", Predictions.S08_FIRST, rec.on("first"), "R1").verify();
    }

    @Test
    void s09_chainWithAsyncInTheMiddle() throws Exception {
        Scenarios.s09(rec, io, cpu).get(2, TimeUnit.SECONDS);
        new Grader()
                .check("m01.s09.supply", Predictions.S09_SUPPLY, rec.on("supply"), "R3")
                .check("m01.s09.parse", Predictions.S09_PARSE, rec.on("parse"), "R2")
                .check("m01.s09.after", Predictions.S09_AFTER, rec.on("after"), "R1")
                .verify();
    }

    @Test
    void s10_orTimeout() throws Exception {
        Scenarios.s10(rec).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s10.afterTimeout", Predictions.S10_AFTER_TIMEOUT, rec.on("afterTimeout"), "R10").verify();
    }

    @Test
    void s11_directExecutor() throws Exception {
        Scenarios.s11(rec).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s11.task", Predictions.S11_TASK, rec.on("task"), "R3").verify();
    }

    @Test
    void s12_supplyAsyncReturnsImmediately() {
        Scenarios.s12(rec, io);
        new Grader()
                .checkMillis("m01.s12.returnedMs", Predictions.S12_RETURNED_MS, rec.atMillis("returned"), "R3")
                .checkMillis("m01.s12.joinedMs", Predictions.S12_JOINED_MS, rec.atMillis("joined"), "R5")
                .verify();
    }

    @Test
    void s13_completedFuture() throws Exception {
        Scenarios.s13(rec).get(2, TimeUnit.SECONDS);
        new Grader().check("m01.s13.map", Predictions.S13_MAP, rec.on("map"), "R1").verify();
    }

    @Test
    void s14_thenCompose() throws Exception {
        Scenarios.s14(rec, io, cpu).get(2, TimeUnit.SECONDS);
        new Grader()
                .check("m01.s14.composeFn", Predictions.S14_COMPOSE_FN, rec.on("compose-fn"), "R1")
                .check("m01.s14.inner", Predictions.S14_INNER, rec.on("inner"), "R3")
                .check("m01.s14.after", Predictions.S14_AFTER, rec.on("after"), "R1")
                .verify();
    }

    @Test
    void s15_registeringABlockingCallback() {
        long[] took = Scenarios.s15(io);
        new Grader()
                .checkMillis("m01.s15.registerIncompleteMs", Predictions.S15_REGISTER_ON_INCOMPLETE_MS, took[0], "R1,R4")
                .checkMillis("m01.s15.registerCompletedMs", Predictions.S15_REGISTER_ON_COMPLETED_MS, took[1], "R1,R4")
                .verify();
    }
}
