package async.m07vt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.FakeBlockingService;
import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;

/** m07-vt 예측 채점. */
@Timeout(20)
class PredictionTest {

    /** 클래스 로딩·가상 스레드 스케줄러 시작 비용을 측정에서 뺀다. */
    static void warmUp() throws Exception {
        VtScenarios.withStructuredScope(new VtScenarios.Services(
                new FakeBlockingService("w", 1), new FakeBlockingService("w", 1), new FakeBlockingService("w", 1)), "w");
    }

    @Test
    void v01_completableFutureAllOfFailure() {
        VtScenarios.Services s = VtScenarios.v01Services();
        long start = System.nanoTime();
        try (NamedPool io = Pools.fixed("io", 3)) {
            try {
                VtScenarios.withCompletableFuture(s, "k", io);
            } catch (RuntimeException expected) {
                // 실패
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            Sleep.ms(100); // 남은 작업이 interrupt 되었는지 관찰할 시간
            new Grader()
                    .checkMillis("m07vt.v01.cfFailMs", Predictions.V01_CF_FAIL_MS, elapsed, "R7,R13")
                    .check("m07vt.v01.cfInterrupted", Predictions.V01_CF_INTERRUPTED, s.interruptedTotal(), "R10")
                    .verify();
        }
    }

    @Test
    void v02_structuredScopeFailure() throws Exception {
        warmUp();
        VtScenarios.Services s = VtScenarios.v02Services();
        long start = System.nanoTime();
        try {
            VtScenarios.withStructuredScope(s, "k");
        } catch (java.util.concurrent.StructuredTaskScope.FailedException expected) {
            // 실패
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        new Grader()
                .checkMillis("m07vt.v02.scopeFailMs", Predictions.V02_SCOPE_FAIL_MS, elapsed, "VT2")
                .check("m07vt.v02.scopeInterrupted", Predictions.V02_SCOPE_INTERRUPTED, s.interruptedTotal(), "VT2")
                .verify();
    }

    @Test
    void v03_manyBlockingCalls() throws Exception {
        warmUp();
        long platform = VtScenarios.v03Platform(new FakeBlockingService("api", 100));
        long virtual = VtScenarios.v03Virtual(new FakeBlockingService("api", 100));
        new Grader()
                .checkMillis("m07vt.v03.platformMs", Predictions.V03_PLATFORM_MS, platform, "R7,R11")
                .checkMillis("m07vt.v03.virtualMs", Predictions.V03_VIRTUAL_MS, virtual, "VT1")
                .verify();
    }

    @Test
    void v04_threadLocalsOnVirtualThreads() throws Exception {
        String[] seen = VtScenarios.v04();
        new Grader()
                .check("m07vt.v04.threadLocal", Predictions.V04_THREAD_LOCAL, seen[0], "R12,VT3")
                .check("m07vt.v04.inheritable", Predictions.V04_INHERITABLE, seen[1], "VT3")
                .verify();
    }

    @Test
    void v05_inheritableWithReusedThreads() throws Exception {
        String[] seen = VtScenarios.v05();
        new Grader()
                .check("m07vt.v05.platformPool", Predictions.V05_PLATFORM_POOL, seen[0], "R12,VT3")
                .check("m07vt.v05.virtual", Predictions.V05_VIRTUAL, seen[1], "VT3")
                .verify();
    }

    @Test
    void v06_scopedValueInheritance() throws Exception {
        String[] seen = VtScenarios.v06();
        new Grader()
                .check("m07vt.v06.fork", Predictions.V06_FORK, seen[0], "VT4")
                .check("m07vt.v06.executor", Predictions.V06_EXECUTOR, seen[1], "VT4")
                .check("m07vt.v06.cf", Predictions.V06_CF, seen[2], "VT4")
                .verify();
    }

    @Test
    void v07_synchronizedPinning() {
        long took = VtScenarios.v07();
        new Grader().checkMillis("m07vt.v07.synchronizedMs", Predictions.V07_SYNCHRONIZED_MS, took, "VT5").verify();
    }
}
