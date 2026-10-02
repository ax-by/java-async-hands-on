package async.m05;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.harness.Timed;

/** m05 예측 채점. Predictions 를 모두 채운 뒤 실행한다. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PredictionTest {

    @Test
    void t01_naiveSequential() {
        ThreadRecorder rec = ThreadRecorder.start();
        ProductBackends b = new ProductBackends().recordTo(rec);
        Timed<ProductDetail> t = Scenarios.t01(b);
        new Grader()
                .checkMillis("m05.t01.naiveMs", Predictions.T01_NAIVE_MS, t.millis(), "R7")
                .check("m05.t01.productThread", Predictions.T01_PRODUCT_THREAD, rec.on("product"), "R0")
                .verify();
    }

    @Test
    void t02_commonPoolFanOut() {
        assumeTrue(ForkJoinPool.getCommonPoolParallelism() >= 4,
                "이 시나리오는 commonPool 병렬도가 4 이상(CPU 5개 이상)인 환경을 가정한다. 현재: "
                        + ForkJoinPool.getCommonPoolParallelism());
        ThreadRecorder rec = ThreadRecorder.start();
        ProductBackends b = new ProductBackends().recordTo(rec);
        Timed<ProductDetail> t = Scenarios.t02(b);
        new Grader()
                .checkMillis("m05.t02.commonPoolMs", Predictions.T02_COMMON_POOL_MS, t.millis(), "R7")
                .check("m05.t02.priceThread", Predictions.T02_PRICE_THREAD, rec.on("price"), "R2")
                .verify();
    }

    @Test
    void t03_joinEachCall() {
        try (NamedPool io = Pools.fixed("io", 8)) {
            Timed<?> t = Scenarios.t03(new ProductBackends(), io);
            new Grader().checkMillis("m05.t03.returnedMs", Predictions.T03_RETURNED_MS, t.millis(), "R5,R7").verify();
        }
    }

    @Test
    void t04_bareFanOut() {
        try (NamedPool io = Pools.fixed("io", 8)) {
            Scenarios.FanOutTiming t = Scenarios.t04(new ProductBackends(), io);
            new Grader()
                    .checkMillis("m05.t04.returnedMs", Predictions.T04_RETURNED_MS, t.returnedMillis(), "R3")
                    .checkMillis("m05.t04.totalMs", Predictions.T04_TOTAL_MS, t.totalMillis(), "R7")
                    .verify();
        }
    }

    @Test
    void t05_fanOutOnTwoThreads() {
        long ms = Scenarios.t05(new ProductBackends());
        new Grader().checkMillis("m05.t05.twoThreadsMs", Predictions.T05_TWO_THREADS_MS, ms, "R7,R11").verify();
    }

    @Test
    void t06_overallTimeout() {
        try (NamedPool io = Pools.fixed("io", 8)) {
            Scenarios.TimeoutObservation o = Scenarios.t06(new ProductBackends(), io);
            new Grader()
                    .checkMillis("m05.t06.failedAtMs", Predictions.T06_FAILED_AT_MS, o.failedAtMillis(), "R10")
                    .check("m05.t06.priceInFlight", Predictions.T06_PRICE_IN_FLIGHT, o.priceInFlightAtFailure(), "R10")
                    .check("m05.t06.exceptionSeenByHandle", Predictions.T06_EXCEPTION_SEEN_BY_HANDLE,
                            o.exceptionSeenByHandle(), "R8")
                    .check("m05.t06.handleThread", Predictions.T06_HANDLE_THREAD, o.handleThread(), "R1,R10")
                    .verify();
        }
    }

    @Test
    void l01_poolSizeUnderLoad() {
        LoadDriver.LoadReport right = Scenarios.l01(60);
        LoadDriver.LoadReport under = Scenarios.l01(16);
        System.out.println("[L01] 풀 60: " + right + " / 풀 16: " + under);
        new Grader()
                .check("m05.l01.pool60", Predictions.L01_POOL_60, right.outcome(), "R11")
                .check("m05.l01.pool16", Predictions.L01_POOL_16, under.outcome(), "R11,R10")
                .verify();
    }

    @Test
    void l02_slowOptionalSource() {
        LoadDriver.LoadReport report = Scenarios.l02();
        System.out.println("[L02] " + report);
        new Grader().check("m05.l02.slowOptional", Predictions.L02_SLOW_OPTIONAL, report.outcome(), "R10,R11").verify();
    }

    @Test
    void d01_to_d06_designQuiz() {
        new Grader()
                .checkHashed("m05.d01", answer("d01", Predictions.D01),
                        "0f72b8376470249eff1b13e654815c91b5aec0b8256b2cce0c964ea4ffa5d182", "R0")
                .checkHashed("m05.d02", answer("d02", Predictions.D02),
                        "32851a1c35344e213c1f2f49080e5398615c9a9c1a107f44b0d1ac528a4e3d05", "R0")
                .checkHashed("m05.d03", answer("d03", Predictions.D03),
                        "7e97f39ce9ffdcda1db53f18fcf684f6e369519d1835d3679dbdae5de610cdf1", "R0,R7")
                .checkHashed("m05.d04", answer("d04", Predictions.D04),
                        "986e33448e7c3a8864ed9fc6524f149e5bc9e8198849676d4674daf5aa2a2944", "R0,R4")
                .checkHashed("m05.d05", answer("d05", Predictions.D05),
                        "e8825e83fc924f67f4b73c2a691c24bc7f236169615628b97f2596babd6a0d08", "R0")
                .checkHashed("m05.d06", answer("d06", Predictions.D06),
                        "66d1112e1cd4c1a97a22329b275e3165e8e308d30f2352796b174d27d4b128b5", "R0")
                .verify();
    }

    /** 문항마다 다른 해시가 나오도록 문항 번호를 붙여 해시한다. */
    private static String answer(String question, Design design) {
        return design == null ? null : "m05." + question + ":" + design.name();
    }
}
