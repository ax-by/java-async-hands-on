package async.m04;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.Grader;
import async.harness.On;
import async.harness.ThreadRecorder;
import async.m04.probe.CommonPoolStarvationProbe;
import async.m04.probe.DefaultExecutorProbe;

/** m04 예측 채점. Predictions 를 모두 채운 뒤 실행한다. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PredictionTest {

    private static String label(On on) {
        return on == null ? null : on.label();
    }

    @Test
    void s01_noExecutor() throws Exception {
        ThreadRecorder rec = ThreadRecorder.start();
        Scenarios.s01(rec).get(2, TimeUnit.SECONDS);
        new Grader().check("m04.s01.noExecutor", Predictions.S01_NO_EXECUTOR, rec.on("task"), "R2").verify();
    }

    @Test
    void s02_rejectionPolicies() {
        Fate abort = Scenarios.s02(ThreadRecorder.start(), new ThreadPoolExecutor.AbortPolicy())[1];
        Fate callerRuns = Scenarios.s02(ThreadRecorder.start(), new ThreadPoolExecutor.CallerRunsPolicy())[1];
        Fate discard = Scenarios.s02(ThreadRecorder.start(), new ThreadPoolExecutor.DiscardPolicy())[1];
        Fate discardOldestSecond = Scenarios.s02(ThreadRecorder.start(), new ThreadPoolExecutor.DiscardOldestPolicy())[0];
        new Grader()
                .check("m04.s02.abortThird", Predictions.S02_ABORT_THIRD, abort, "R3")
                .check("m04.s02.callerRunsThird", Predictions.S02_CALLER_RUNS_THIRD, callerRuns, "R3")
                .check("m04.s02.discardThird", Predictions.S02_DISCARD_THIRD, discard, "R11")
                .check("m04.s02.discardOldestSecond", Predictions.S02_DISCARD_OLDEST_SECOND, discardOldestSecond, "R11")
                .verify();
    }

    @Test
    void s03_coreMaxQueue() throws Exception {
        Scenarios.PoolShape shape = Scenarios.s03(ThreadRecorder.start());
        new Grader()
                .check("m04.s03.largestPoolSize", Predictions.S03_LARGEST_POOL_SIZE, shape.largestPoolSize(), "R11")
                .check("m04.s03.queuedIds", Predictions.S03_QUEUED_IDS, shape.queuedIds(), "R11")
                .verify();
    }

    @Test
    void s04_unboundedQueue() throws Exception {
        Scenarios.Throughput t = Scenarios.s04();
        new Grader()
                .check("m04.s04.largestPoolSize", Predictions.S04_LARGEST_POOL_SIZE, t.largestPoolSize(), "R11")
                .verify();
    }

    @Test
    void s05_unboundedVsBoundedQueue() throws Exception {
        long unboundedLast = Scenarios.s05Unbounded();
        int boundedRejected = Scenarios.s05Bounded();
        new Grader()
                .checkMillis("m04.s05.unboundedLastMs", Predictions.S05_UNBOUNDED_LAST_MS, unboundedLast, "R7,R11")
                .check("m04.s05.boundedRejected", Predictions.S05_BOUNDED_REJECTED, boundedRejected, "R11")
                .verify();
    }

    @Test
    void s06_starvationDeadlock() throws Exception {
        new Grader()
                .check("m04.s06.t2n1", Predictions.S06_T2_N1, Scenarios.s06(2, 1), "R11")
                .check("m04.s06.t2n2", Predictions.S06_T2_N2, Scenarios.s06(2, 2), "R11")
                .check("m04.s06.t4n3", Predictions.S06_T4_N3, Scenarios.s06(4, 3), "R11")
                .check("m04.s06.t4n8", Predictions.S06_T4_N8, Scenarios.s06(4, 8), "R11")
                .verify();
    }

    @Test
    void s07_bulkhead() {
        long shared = Scenarios.s07Shared();
        long bulkhead = Scenarios.s07Bulkhead();
        new Grader()
                .checkMillis("m04.s07.sharedInventoryMs", Predictions.S07_SHARED_INVENTORY_MS, shared, "R11")
                .checkMillis("m04.s07.bulkheadInventoryMs", Predictions.S07_BULKHEAD_INVENTORY_MS, bulkhead, "R11")
                .verify();
    }

    @Test
    void c01_commonPoolStarvation_childJvm() {
        Map<String, String> out = ChildJvm.run(ChildJvm.currentJavaHome(),
                List.of("-Djava.util.concurrent.ForkJoinPool.common.parallelism=2"),
                CommonPoolStarvationProbe.class);
        System.out.println("[C01 자식 JVM 출력] " + out);
        new Grader()
                .checkMillis("m04.c01.supplyAsyncStartMs", Predictions.C01_SUPPLY_ASYNC_START_MS,
                        Long.parseLong(out.get("supplyAsyncStartMs")), "R2,R11")
                .checkMillis("m04.c01.parallelStreamMs", Predictions.C01_PARALLEL_STREAM_MS,
                        Long.parseLong(out.get("parallelStreamMs")), "R11")
                .check("m04.c01.parallelStreamRanOn", label(Predictions.C01_PARALLEL_STREAM_RAN_ON),
                        out.get("parallelStreamRanOn"), "R11")
                .verify();
    }

    @Test
    void v01_defaultExecutor_jdk25_oneCpu() {
        Map<String, String> out = ChildJvm.run(ChildJvm.currentJavaHome(),
                List.of("-XX:ActiveProcessorCount=1"), DefaultExecutorProbe.class);
        System.out.println("[V01 JDK 25 자식 JVM 출력] " + out);
        new Grader()
                .check("m04.v01.jdk25.thread", label(Predictions.V01_JDK25_THREAD), out.get("thread"), "R2")
                .checkMillis("m04.v01.jdk25.totalMs", Predictions.V01_JDK25_TOTAL_MS,
                        Long.parseLong(out.get("totalMs")), "R2,R7")
                .verify();
    }

    @Test
    void v01_defaultExecutor_jdk21_oneCpu() {
        Path jdk21 = ChildJvm.jdk21Home().orElse(null);
        assumeTrue(jdk21 != null, "JDK 21 이 없어 건너뛴다. JAVA21_HOME 환경변수로 위치를 지정할 수 있다 (기본: "
                + ChildJvm.DEFAULT_JDK21_HOME + ")");
        Map<String, String> out = ChildJvm.run(jdk21, List.of("-XX:ActiveProcessorCount=1"), DefaultExecutorProbe.class);
        System.out.println("[V01 JDK 21 자식 JVM 출력] " + out);
        new Grader()
                .check("m04.v01.jdk21.thread", label(Predictions.V01_JDK21_THREAD), out.get("thread"), "R2")
                .checkMillis("m04.v01.jdk21.totalMs", Predictions.V01_JDK21_TOTAL_MS,
                        Long.parseLong(out.get("totalMs")), "R2,R7")
                .verify();
    }
}
