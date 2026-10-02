package async.m09;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.m09.minikv.FakeTransport;

/** m09 예측 채점. Predictions 를 모두 채운 뒤 실행한다. 규칙 L1~L8 은 README 에 정의되어 있다. */
@Timeout(10)
class PredictionTest {

    FakeTransport transport;
    NamedPool app;
    ScheduledThreadPoolExecutor timer;
    ThreadRecorder rec;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport(200).latencyFor("slow", 1000);
        app = Pools.single("app");
        timer = new ScheduledThreadPoolExecutor(1, Pools.namedFactory("minikv-timer"));
        rec = ThreadRecorder.start();
    }

    @AfterEach
    void tearDown() {
        transport.close();
        app.close();
        timer.shutdownNow();
    }

    @Test
    void s01_policy1SameThread() {
        Scenarios.s01(transport, rec::mark);
        new Grader().check("m09.s01.user", Predictions.S01_USER, rec.on("user"), "R1,L2").verify();
    }

    @Test
    void s02_completionExecutor() {
        Scenarios.s02(transport, app, rec::mark);
        new Grader()
                .check("m09.s02.user", Predictions.S02_USER, rec.on("user"), "R1,L2")
                .check("m09.s02.late", Predictions.S02_LATE, rec.on("late"), "R1")
                .verify();
    }

    @Test
    void s03_policy1WithTimeout() {
        String exception = Scenarios.s03(transport, timer, rec::mark);
        new Grader()
                .check("m09.s03.user", Predictions.S03_USER, rec.on("user"), "R1,R10,L2")
                .check("m09.s03.exception", Predictions.S03_EXCEPTION, exception, "R8,L6")
                .verify();
    }

    @Test
    void s04_returningInternalFuture_complete() {
        new Grader().check("m09.s04.callerB", Predictions.S04_CALLER_B, Scenarios.s04(transport), "L1").verify();
    }

    @Test
    void s05_returningInternalFuture_cancel() {
        new Grader().check("m09.s05.callerB", Predictions.S05_CALLER_B, Scenarios.s05(transport), "L1,R10").verify();
    }

    @Test
    void s06_perCallDependentCopy() {
        Object[] r = Scenarios.s06(transport);
        new Grader()
                .check("m09.s06.callerB", Predictions.S06_CALLER_B, r[0], "L1,R6")
                .check("m09.s06.sameObject", Predictions.S06_SAME_OBJECT, r[1], "L1")
                .verify();
    }

    @Test
    void s07_sharedDependentCopy() {
        new Grader().check("m09.s07.callerB", Predictions.S07_CALLER_B, Scenarios.s07(transport), "L1").verify();
    }

    @Test
    void s08_exceptionSeenByUser() {
        String[] r = Scenarios.s08();
        new Grader()
                .check("m09.s08.viaThenApply", Predictions.S08_VIA_THEN_APPLY, r[0], "R8,L6")
                .check("m09.s08.viaRelay", Predictions.S08_VIA_RELAY, r[1], "R8,L6")
                .verify();
    }

    @Test
    void s09_minimalCompletionStage() {
        new Grader().check("m09.s09.castComplete", Predictions.S09_CAST_COMPLETE, ModernScenarios.s09(), "L1").verify();
    }

    @Test
    void s10_fakeAsync() {
        Scenarios.s10(transport, rec::mark);
        new Grader()
                .checkMillis("m09.s10.returnedMs", Predictions.S10_RETURNED_MS, rec.atMillis("returned"), "L4,R5")
                .check("m09.s10.user", Predictions.S10_USER, rec.on("user"), "R1")
                .verify();
    }

    @Test
    void s11_syncOverAsyncOnCompletionExecutor() {
        new Grader().check("m09.s11.outcome", Predictions.S11_OUTCOME, Scenarios.s11(transport, app), "L4,R11").verify();
    }

    @Test
    void s12_cancelledTimersAndQueue() {
        ScheduledThreadPoolExecutor plain = new ScheduledThreadPoolExecutor(1);
        try {
            new Grader().check("m09.s12.queued", Predictions.S12_QUEUED, Scenarios.s12(plain), "L5").verify();
        } finally {
            plain.shutdownNow();
        }
    }

    @Test
    void s13_compileTarget(@TempDir Path dir) throws Exception {
        boolean release8 = compiles(dir.resolve("a"), "--release", "8");
        boolean sourceTarget8 = compiles(dir.resolve("b"), "-source", "8", "-target", "8");
        new Grader()
                .check("m09.s13.release8", Predictions.S13_RELEASE_8, release8, "L8")
                .check("m09.s13.sourceTarget8", Predictions.S13_SOURCE_TARGET_8, sourceTarget8, "L8")
                .verify();
    }

    static boolean compiles(Path dir, String... targetOptions) throws Exception {
        Files.createDirectories(dir);
        Path src = dir.resolve("Snippet.java");
        Files.writeString(src, Scenarios.S13_SNIPPET, StandardCharsets.UTF_8);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of(targetOptions));
        args.addAll(List.of("-Xlint:-options", "-nowarn", "-d", dir.toString(), src.toString()));
        // 컴파일러 출력은 버린다 (결과는 종료 코드로만 판단)
        return javac.run(null, java.io.OutputStream.nullOutputStream(), java.io.OutputStream.nullOutputStream(),
                args.toArray(new String[0])) == 0;
    }
}
