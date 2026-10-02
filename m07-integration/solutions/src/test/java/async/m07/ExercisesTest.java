package async.m07;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import async.harness.FakeEventLoopClient;
import async.harness.Gate;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m07.ctx.ContextScenarios;
import async.m07.ctx.Ctx;

/** m07 구현 채점 E1~E3. */
@Timeout(10)
class ExercisesTest {

    @AfterEach
    void tearDown() {
        Ctx.clear();
    }

    private static String onPool(Executor pool, java.util.function.Supplier<String> s) throws Exception {
        return CompletableFuture.supplyAsync(s, pool).get(2, TimeUnit.SECONDS);
    }

    // ── E1 ─────────────────────────────────────────────────────────────────────

    @Test
    void e1_propagatesMdcToWorker() throws Exception {
        TaskDecorator d = Exercises.mdcTaskDecorator();
        try (NamedPool io = Pools.single("io")) {
            Ctx.enter("t-e1", "alice");
            assertThat(onPool(ContextScenarios.decorated(io, d), Ctx::traceId))
                    .as("제출 스레드의 traceId 가 작업 스레드에서 보여야 한다 (R12)").isEqualTo("t-e1");
        }
    }

    @Test
    void e1_restoresWorkerAfterTask_noLeakToNextTask() throws Exception {
        TaskDecorator d = Exercises.mdcTaskDecorator();
        try (NamedPool io = Pools.single("io")) {
            Ctx.enter("t-e1", "alice");
            onPool(ContextScenarios.decorated(io, d), Ctx::traceId);
            assertThat(onPool(io, Ctx::traceId))
                    .as("작업이 끝난 뒤 풀 스레드에 이전 요청의 traceId 가 남아 있다. finally 에서 복원하라 (R12 복원)")
                    .isEqualTo(Ctx.NONE);
        }
    }

    @Test
    void e1_restoresEvenWhenTaskThrows() throws Exception {
        TaskDecorator d = Exercises.mdcTaskDecorator();
        try (NamedPool io = Pools.single("io")) {
            Ctx.enter("t-e1", "alice");
            CompletableFuture<String> failed = CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException("boom");
            }, ContextScenarios.decorated(io, d));
            assertThat(failed).failsWithin(2, TimeUnit.SECONDS);
            assertThat(onPool(io, Ctx::traceId)).as("작업이 예외로 끝나도 복원해야 한다 (try/finally)").isEqualTo(Ctx.NONE);
        }
    }

    @Test
    void e1_submitterWithoutMdc_seesNothingEvenIfWorkerIsDirty() throws Exception {
        TaskDecorator d = Exercises.mdcTaskDecorator();
        try (NamedPool io = Pools.single("io")) {
            io.submit(() -> MDC.put(Ctx.TRACE_ID, "STALE")).get(2, TimeUnit.SECONDS); // 누군가 오염시킨 풀 스레드
            Ctx.clear(); // 제출 스레드에는 MDC 가 없다
            assertThat(onPool(ContextScenarios.decorated(io, d), Ctx::traceId))
                    .as("제출 스레드에 MDC 가 없으면 작업에서도 없어야 한다. 캡처한 값이 null 이면 MDC.clear() (R12)")
                    .isEqualTo(Ctx.NONE);
        }
    }

    @Test
    void e1_restoreNotClear_whenTaskRunsOnCallerThread() {
        TaskDecorator d = Exercises.mdcTaskDecorator();
        Ctx.enter("t-request", "alice");
        Executor callerRuns = ContextScenarios.decorated(Runnable::run, d); // CallerRunsPolicy 와 같은 상황
        CompletableFuture.runAsync(() -> MDC.put(Ctx.TRACE_ID, "t-changed-inside"), callerRuns).join();
        assertThat(Ctx.traceId())
                .as("작업이 호출 스레드에서 실행된 경우, 작업 후 그 스레드의 원래 MDC 로 '복원'해야 한다. clear 하면 요청 스레드의 traceId 가 사라진다")
                .isEqualTo("t-request");
    }

    // ── E2 ─────────────────────────────────────────────────────────────────────

    @Test
    void e2_namedFixedSizeAndPropagatesMdcAndSecurity() throws Exception {
        ThreadPoolTaskExecutor ex = Exercises.orderExecutor(4, 10);
        try {
            Ctx.enter("t-e2", "alice");
            String seen = onPool(ex, () -> Thread.currentThread().getName() + " " + Ctx.both());
            assertThat(seen).as("스레드 이름 접두어 order-").startsWith("order-");
            assertThat(seen).as("traceId 와 user 가 모두 전파돼야 한다 (R12)").endsWith("t-e2/alice");

            Gate gate = new Gate();
            List<CompletableFuture<Void>> all = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                all.add(CompletableFuture.runAsync(gate::await, ex));
            }
            gate.open();
            CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0])).get(2, TimeUnit.SECONDS);
            assertThat(ex.getThreadPoolExecutor().getLargestPoolSize()).as("스레드 수는 정확히 threads 개 (core = max)").isEqualTo(4);
        } finally {
            ex.shutdown();
        }
    }

    @Test
    void e2_noLeakAfterTask() throws Exception {
        ThreadPoolTaskExecutor ex = Exercises.orderExecutor(1, 10);
        try {
            Ctx.enter("t-e2", "alice");
            onPool(ex, Ctx::both);
            Ctx.clear();
            assertThat(onPool(ex, Ctx::both)).as("다음 작업(컨텍스트 없는 제출자)에 이전 요청의 컨텍스트가 보이면 안 된다").isEqualTo("NONE/NONE");
        } finally {
            ex.shutdown();
        }
    }

    @Test
    void e2_boundedQueueRejectsSynchronously() {
        ThreadPoolTaskExecutor ex = Exercises.orderExecutor(1, 1);
        Gate gate = new Gate();
        try {
            CompletableFuture.runAsync(gate::await, ex); // 실행 중
            CompletableFuture.runAsync(gate::await, ex); // 큐 1칸
            assertThatThrownBy(() -> CompletableFuture.runAsync(() -> { }, ex))
                    .as("큐가 꽉 차면 supplyAsync/runAsync 가 그 자리에서 RejectedExecutionException 을 던져야 한다 (R3 예외 2, R11)")
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            gate.open();
            ex.shutdown();
        }
    }

    // ── E3 ─────────────────────────────────────────────────────────────────────

    @Test
    void e3_eventLoopCompletedFuture_seesAssemblyContext() throws Exception {
        try (NamedPool io = Pools.single("io"); FakeEventLoopClient redis = new FakeEventLoopClient("redis", 200)) {
            Ctx.enter("t-e3", "alice");
            String seen = redis.fetch("cart:1")
                    .thenApplyAsync(v -> Thread.currentThread().getName() + " " + Ctx.both(), Exercises.boundToCurrentContext(io))
                    .get(2, TimeUnit.SECONDS);
            assertThat(seen).as("작업은 주어진 executor 에서 실행돼야 한다").startsWith("io-");
            assertThat(seen).as("이벤트 루프가 execute 를 호출해도 조립 시점의 컨텍스트가 보여야 한다 (R12 조립 시점 캡처)").endsWith("t-e3/alice");
            assertThat(onPool(io, Ctx::both)).as("작업 후 io 스레드는 원래대로 복원돼야 한다").isEqualTo("NONE/NONE");
        }
    }

    @Test
    void e3_capturesAtCallTime_notAtExecuteTime() throws Exception {
        try (NamedPool io = Pools.single("io")) {
            Ctx.enter("t-alice", "alice");
            Executor bound = Exercises.boundToCurrentContext(io);
            Ctx.enter("t-bob", "bob"); // 조립 후 호출 스레드의 컨텍스트가 바뀌었다
            assertThat(onPool(bound, Ctx::both))
                    .as("boundToCurrentContext 를 호출한 순간의 컨텍스트를 캡처해야 한다 (execute 시점에 캡처하면 TaskDecorator 와 같아진다)")
                    .isEqualTo("t-alice/alice");
        }
    }
}
