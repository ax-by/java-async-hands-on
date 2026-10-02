package async.m07;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import async.harness.Grader;
import async.harness.ThreadRecorder;
import async.m07.async.AsyncScenario;

/** m07 예측 채점 (5. Spring @Async). */
@Timeout(30)
class AsyncPredictionTest {

    @Nested
    @SpringBootTest(classes = AsyncScenario.DefaultConfig.class)
    class Defaults {
        @Autowired AsyncScenario.ReportService reports;
        @Autowired ThreadPoolTaskExecutor applicationTaskExecutor;

        @Test
        void s13_proxyVsSelfInvocation() throws Exception {
            ThreadRecorder rec = ThreadRecorder.start();
            reports.render(rec, "proxy").get(2, TimeUnit.SECONDS);
            reports.renderViaThis(rec).get(2, TimeUnit.SECONDS);
            new Grader()
                    .check("m07.s13.proxy", Predictions.S13_PROXY, rec.on("proxy"), "R2")
                    .check("m07.s13.self", Predictions.S13_SELF, rec.on("self"), "R3")
                    .verify();
        }

        @Test
        void s14_bootDefaultExecutor() {
            List<CompletableFuture<String>> all = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                all.add(reports.renderSlow("r" + i));
            }
            CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0])).orTimeout(5, TimeUnit.SECONDS).join();
            new Grader()
                    .check("m07.s14.largestPool", Predictions.S14_LARGEST_POOL,
                            applicationTaskExecutor.getThreadPoolExecutor().getLargestPoolSize(), "R11")
                    .verify();
        }

        @Test
        void s15_whereDoAsyncExceptionsGo() throws Exception {
            AsyncScenario.UNCAUGHT.clear();
            reports.sendMailVoid();
            waitUntil(() -> !AsyncScenario.UNCAUGHT.isEmpty());
            boolean voidToHandler = AsyncScenario.UNCAUGHT.stream().anyMatch(e -> e.getMessage().contains("(void)"));

            CompletableFuture<Void> ignored = reports.sendMailFuture();
            try {
                ignored.get(2, TimeUnit.SECONDS); // 실험을 위해 결과를 기다린다 (시나리오의 호출자는 무시했다)
            } catch (ExecutionException ignoredForGrading) {
                // 채점 대상은 핸들러로 갔는지뿐이다
            }
            boolean futureToHandler = AsyncScenario.UNCAUGHT.stream().anyMatch(e -> e.getMessage().contains("(future)"));
            new Grader()
                    .check("m07.s15.voidToHandler", Predictions.S15_VOID_TO_HANDLER, voidToHandler, "R6")
                    .check("m07.s15.futureToHandler", Predictions.S15_FUTURE_TO_HANDLER, futureToHandler, "R6,R8")
                    .verify();
        }
    }

    @Nested
    @SpringBootTest(classes = AsyncScenario.TwoExecutorsConfig.class)
    class TwoExecutors {
        @Autowired AsyncScenario.ReportService reports;

        @Test
        void s16_twoExecutorBeans() throws Exception {
            ThreadRecorder rec = ThreadRecorder.start();
            reports.render(rec, "render").get(2, TimeUnit.SECONDS);
            new Grader().check("m07.s16.thread", Predictions.S16_THREAD, rec.on("render"), "R2,R11").verify();
        }
    }

    static void waitUntil(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
