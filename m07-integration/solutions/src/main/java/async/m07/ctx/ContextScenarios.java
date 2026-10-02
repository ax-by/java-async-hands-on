package async.m07.ctx;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import async.harness.FakeEventLoopClient;
import async.harness.Gate;
import async.harness.Pools;
import async.harness.Pools.NamedPool;

/**
 * m07 컨텍스트 예측 시나리오 (MDC, SecurityContext). <b>이 파일은 수정하지 않는다.</b> 읽고 예측만 한다.
 *
 * <p>공통 전제: 시나리오를 호출하는 스레드(caller)는 <b>요청 스레드</b>다. 호출 전에 테스트가
 * {@code Ctx.enter("t-100", "alice")} 를 해 두었다(서블릿 필터가 traceId 를 MDC 에, 인증 정보를 SecurityContextHolder 에 넣은 상태).
 * 시나리오마다 다른 전제가 있으면 javadoc 에 적었다.
 *
 * <p>{@code rec.mark("x")} 는 그 지점에서 보인 traceId 와 user 를 기록한다. 값이 없으면 "NONE".
 */
public final class ContextScenarios {

    private ContextScenarios() {
    }

    /** S01. 요청 스레드에서 supplyAsync 로 io 풀에 넘긴 작업. */
    public static void s01(ContextRecorder rec, Executor io) {
        CompletableFuture.supplyAsync(() -> {
            rec.mark("supply");
            return "order-1";
        }, io).join();
    }

    /**
     * S02. 같은 콜백을 "완료 전"에 붙인 경우(before)와 "완료 후"에 붙인 경우(after).
     * 둘 다 non-async thenApply 다.
     */
    public static void s02(ContextRecorder rec, Executor io) {
        Gate gate = new Gate();
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
            gate.await();
            return "order-1";
        }, io);
        CompletableFuture<String> before = f.thenApply(v -> {
            rec.mark("before");
            return v;
        });
        gate.open();
        before.join();
        f.thenApply(v -> {
            rec.mark("after");
            return v;
        }).join();
    }

    /**
     * 흔히 보이는 "직접 만든" TaskDecorator. 요청 스레드의 MDC 와 인증 정보를 복사해서 작업 스레드에 넣는다.
     * (코드 리뷰에서 이 데코레이터의 문제를 찾아보라.)
     */
    public static TaskDecorator copyingDecorator() {
        return task -> {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return () -> {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                if (auth != null) {
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
                task.run();
            };
        };
    }

    /** decorator 를 적용해 pool 에 제출하는 Executor (ThreadPoolTaskExecutor.setTaskDecorator 와 같은 효과). */
    public static Executor decorated(Executor pool, TaskDecorator decorator) {
        return task -> pool.execute(decorator.decorate(task));
    }

    /**
     * S03. 스레드가 <b>1개뿐인</b> 풀 + {@link #copyingDecorator()} (leaky) 와
     * Spring 의 {@link ContextPropagatingTaskDecorator}(MDC+Security 등록, fixed) 를 비교한다.
     * <ol>
     *   <li>alice 의 요청 스레드(t-alice)가 작업을 하나 제출한다.</li>
     *   <li>그다음 <b>인증 정보도 traceId 도 없는</b> 스레드(배치 잡, 헬스 체크, 익명 요청)가 같은 풀에 작업을 제출한다.
     *       그 작업 안에서 보이는 user 는?</li>
     * </ol>
     * 이 시나리오는 caller 의 컨텍스트를 직접 설정한다(공통 전제 무시).
     */
    public static void s03(ContextRecorder rec) {
        try (NamedPool one = Pools.single("io")) {
            runTwoRequests(rec, "leaky", decorated(one, copyingDecorator()));
        }
        try (NamedPool one = Pools.single("io")) {
            runTwoRequests(rec, "fixed", decorated(one, new ContextPropagatingTaskDecorator(Ctx.mdcAndSecurityFactory())));
        }
    }

    private static void runTwoRequests(ContextRecorder rec, String prefix, Executor pool) {
        Ctx.enter("t-alice", "alice");
        CompletableFuture.runAsync(() -> rec.mark(prefix + ".alice"), pool).join();
        Ctx.clear(); // 이제 caller 는 컨텍스트가 없는 스레드다
        CompletableFuture.runAsync(() -> rec.mark(prefix + ".anonymous"), pool).join();
    }

    /**
     * S04. 올바른(복원하는) 데코레이터를 단 풀 안에서 이어지는 체인.
     * map 은 non-async(완료 전에 등록), mapAsync 는 같은 decorated 풀로 넘기는 Async 다.
     */
    public static void s04(ContextRecorder rec, Executor decoratedIo) {
        Gate gate = new Gate();
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                    gate.await();
                    rec.mark("supply");
                    return "order-1";
                }, decoratedIo)
                .thenApply(v -> {
                    rec.mark("map");
                    return v;
                })
                .thenApplyAsync(v -> {
                    rec.mark("mapAsync");
                    return v;
                }, decoratedIo);
        gate.open();
        f.join();
    }

    /**
     * S05. 비동기 Redis 클라이언트(이벤트 루프가 future 를 완료시킨다)의 응답에 non-async 콜백을 붙인다.
     * 응답은 200ms 뒤에 오므로 콜백은 확실히 "완료 전"에 등록된다.
     */
    public static void s05(ContextRecorder rec, FakeEventLoopClient redis) {
        redis.fetch("cart:1").thenApply(v -> {
            rec.mark("map");
            return v;
        }).join();
    }

    /**
     * S06. S05 와 같지만 이번엔 <b>MDC 와 Security 를 전파하는 decorated 풀</b>로 넘긴다:
     * {@code thenApplyAsync(fn, decoratedIo)}. 콜백은 "완료 전"에 등록된다.
     */
    public static void s06(ContextRecorder rec, FakeEventLoopClient redis, Executor decoratedIo) {
        redis.fetch("cart:1").thenApplyAsync(v -> {
            rec.mark("mapAsync");
            return v;
        }, decoratedIo).join();
    }

    /** S07. S06 과 같은 코드. 단, 응답이 <b>이미 도착한 뒤에</b> 콜백을 등록한다. */
    public static void s07(ContextRecorder rec, FakeEventLoopClient redis, Executor decoratedIo) {
        CompletableFuture<String> response = redis.fetch("cart:1");
        response.join();
        response.thenApplyAsync(v -> {
            rec.mark("mapAsync");
            return v;
        }, decoratedIo).join();
    }

    /**
     * S08. Spring Security 의 {@link DelegatingSecurityContextExecutor}.
     * "during": 감싼 executor 로 실행한 작업, "after": 그 뒤 <b>같은 스레드</b>에 감싸지 않은 채로 실행한 작업.
     * (io 는 스레드 1개짜리 풀이다.)
     */
    public static void s08(ContextRecorder rec, Executor singleIo) {
        Executor secured = new DelegatingSecurityContextExecutor(singleIo);
        CompletableFuture.runAsync(() -> rec.mark("during"), secured).join();
        CompletableFuture.runAsync(() -> rec.mark("after"), singleIo).join();
    }

    /**
     * S09. {@code SecurityContextHolder.MODE_INHERITABLETHREADLOCAL} (자식 스레드가 부모의 인증 정보를 물려받는 모드).
     * <ol>
     *   <li>alice 의 요청이 스레드 1개짜리 풀에 <b>처음으로</b> 작업을 제출한다(이때 풀 스레드가 만들어진다).</li>
     *   <li>그다음 bob 의 요청이 같은 풀에 작업을 제출한다. 그 작업에서 보이는 user 는?</li>
     * </ol>
     * 이 시나리오는 caller 의 컨텍스트를 직접 설정하고, 끝나면 전략을 MODE_THREADLOCAL 로 되돌린다.
     */
    public static void s09(ContextRecorder rec) {
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
        try (NamedPool lazy = Pools.single("io")) {
            Ctx.enter("t-alice", "alice");
            CompletableFuture.runAsync(() -> rec.mark("alice"), lazy).join();
            Ctx.enter("t-bob", "bob");
            CompletableFuture.runAsync(() -> rec.mark("bob"), lazy).join();
        } finally {
            Ctx.clear();
            SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_THREADLOCAL);
        }
    }

    /**
     * S10. Spring Framework 6.1+ 의 {@link ContextPropagatingTaskDecorator} 를 <b>기본 생성자</b>로 쓴다
     * (전역 ContextRegistry 를 쓴다. 이 모듈의 classpath: spring-security, micrometer-observation, context-propagation.
     * Micrometer Tracing 은 없다). 작업 안에서 보이는 traceId 와 user 는?
     */
    public static void s10(ContextRecorder rec, Executor io) {
        Executor springDefault = decorated(io, new ContextPropagatingTaskDecorator());
        CompletableFuture.runAsync(() -> rec.mark("task"), springDefault).join();
    }
}
