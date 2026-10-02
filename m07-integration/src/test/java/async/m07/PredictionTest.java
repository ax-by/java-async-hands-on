package async.m07;

import java.util.concurrent.Executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.security.core.context.SecurityContextHolder;

import async.harness.FakeEventLoopClient;
import async.harness.Grader;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m07.ctx.ContextRecorder;
import async.m07.ctx.ContextScenarios;
import async.m07.ctx.Ctx;

/** m07 예측 채점 (1. MDC, 2. SecurityContext, 4. CF 고유의 함정). Spring 컨텍스트 없이 돈다. */
@Timeout(10)
class PredictionTest {

    NamedPool io;
    ContextRecorder rec;

    @BeforeEach
    void setUp() {
        io = Pools.fixed("io", 2);
        Ctx.enter("t-100", "alice"); // 공통 전제: 테스트 스레드 = 요청 스레드
        rec = new ContextRecorder();
    }

    @AfterEach
    void tearDown() {
        io.close();
        Ctx.clear();
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_THREADLOCAL);
    }

    private Executor decoratedIo() {
        return ContextScenarios.decorated(io, new ContextPropagatingTaskDecorator(Ctx.mdcAndSecurityFactory()));
    }

    @Test
    void s01_supplyAsync() {
        ContextScenarios.s01(rec, io);
        new Grader().check("m07.s01.supplyTrace", Predictions.S01_SUPPLY_TRACE, rec.traceId("supply"), "R12").verify();
    }

    @Test
    void s02_callbackBeforeAndAfterCompletion() {
        ContextScenarios.s02(rec, io);
        new Grader()
                .check("m07.s02.beforeTrace", Predictions.S02_BEFORE_TRACE, rec.traceId("before"), "R1,R12")
                .check("m07.s02.afterTrace", Predictions.S02_AFTER_TRACE, rec.traceId("after"), "R1,R12")
                .verify();
    }

    @Test
    void s03_leakingDecorator() {
        ContextScenarios.s03(rec);
        new Grader()
                .check("m07.s03.leakyAnonymousUser", Predictions.S03_LEAKY_ANONYMOUS_USER, rec.user("leaky.anonymous"), "R12")
                .check("m07.s03.fixedAnonymousUser", Predictions.S03_FIXED_ANONYMOUS_USER, rec.user("fixed.anonymous"), "R12")
                .verify();
    }

    @Test
    void s04_chainInsideDecoratedPool() {
        ContextScenarios.s04(rec, decoratedIo());
        new Grader()
                .check("m07.s04.mapTrace", Predictions.S04_MAP_TRACE, rec.traceId("map"), "R1,R12")
                .check("m07.s04.mapAsyncTrace", Predictions.S04_MAP_ASYNC_TRACE, rec.traceId("mapAsync"), "R2,R12")
                .verify();
    }

    @Test
    void s05_eventLoopCallback() {
        try (FakeEventLoopClient redis = new FakeEventLoopClient("redis", 200)) {
            ContextScenarios.s05(rec, redis);
        }
        new Grader().check("m07.s05.mapTrace", Predictions.S05_MAP_TRACE, rec.traceId("map"), "R1,R12").verify();
    }

    @Test
    void s06_eventLoopThenAsyncDecorated() {
        try (FakeEventLoopClient redis = new FakeEventLoopClient("redis", 200)) {
            ContextScenarios.s06(rec, redis, decoratedIo());
        }
        new Grader().check("m07.s06.mapAsyncTrace", Predictions.S06_MAP_ASYNC_TRACE, rec.traceId("mapAsync"), "R1,R2,R12").verify();
    }

    @Test
    void s07_completedThenAsyncDecorated() {
        try (FakeEventLoopClient redis = new FakeEventLoopClient("redis", 200)) {
            ContextScenarios.s07(rec, redis, decoratedIo());
        }
        new Grader().check("m07.s07.mapAsyncTrace", Predictions.S07_MAP_ASYNC_TRACE, rec.traceId("mapAsync"), "R1,R2,R12").verify();
    }

    @Test
    void s08_delegatingSecurityContextExecutor() {
        try (NamedPool single = Pools.single("io")) {
            ContextScenarios.s08(rec, single);
        }
        new Grader()
                .check("m07.s08.duringUser", Predictions.S08_DURING_USER, rec.user("during"), "R12")
                .check("m07.s08.afterUser", Predictions.S08_AFTER_USER, rec.user("after"), "R12")
                .verify();
    }

    @Test
    void s09_inheritableThreadLocalWithPool() {
        ContextScenarios.s09(rec);
        new Grader().check("m07.s09.bobTaskUser", Predictions.S09_BOB_TASK_USER, rec.user("bob"), "R12").verify();
    }

    @Test
    void s10_springDefaultContextPropagatingTaskDecorator() {
        ContextScenarios.s10(rec, io);
        new Grader()
                .check("m07.s10.trace", Predictions.S10_TRACE, rec.traceId("task"), "R12")
                .check("m07.s10.user", Predictions.S10_USER, rec.user("task"), "R12")
                .verify();
    }
}
