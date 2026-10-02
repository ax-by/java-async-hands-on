package async.m07;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import async.m07.ctx.Ctx;
import io.micrometer.context.ContextSnapshot;

/**
 * m07 구현 실습 E1~E3 (Spring 컨텍스트 없이 채점). 채점: ExercisesTest
 * <p>E4(트랜잭션), E5(컨트롤러), E6(@Async 설정)은 각각
 * {@code tx/OrderPlacementFacade}, {@code web/ProductController}, {@code async/AsyncExerciseConfig} 에 있다.
 */
public final class Exercises {

    private Exercises() {
    }

    /**
     * E1. MDC 를 전파하는 {@link TaskDecorator} 를 <b>직접</b> 작성하라. (라이브러리 금지: 원리를 손으로 익힌다)
     *
     * <p>요구사항
     * <ol>
     *   <li>{@code decorate()} 가 호출되는 스레드(작업을 제출하는 스레드)의 MDC 를 작업 스레드에서 보이게 한다.</li>
     *   <li>제출 스레드에 MDC 가 없으면 작업 스레드에서도 없어야 한다(작업 스레드에 남아 있던 값이 보이면 안 된다).</li>
     *   <li>작업이 끝나면(예외로 끝나도) 작업 스레드의 MDC 를 <b>작업 전 상태로 되돌린다</b>.
     *       "비우기(clear)"가 아니라 "되돌리기(restore)"여야 하는 이유: CallerRunsPolicy 나 직접 실행 executor 에서는
     *       작업 스레드가 곧 요청 스레드다.</li>
     * </ol>
     */
    public static TaskDecorator mdcTaskDecorator() {
        // TODO(E1): capture(제출 스레드) → 작업 스레드에서 이전 값 보관 → set → try { run } finally { restore } (R12)
        // SOLUTION-BEGIN
        return task -> {
            Map<String, String> captured = MDC.getCopyOfContextMap(); // 제출 스레드에서 캡처
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap(); // 작업 스레드의 기존 값
                if (captured == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(captured);
                }
                try {
                    task.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
        // SOLUTION-END
    }

    /**
     * E2. 주문 서비스의 CF fan-out 용 executor 를 운영 수준으로 구성하라 (Spring 의 {@link ThreadPoolTaskExecutor}).
     *
     * <ol>
     *   <li>스레드 이름 접두어 {@code "order-"}, 스레드 수는 정확히 {@code threads} 개(core = max)</li>
     *   <li>큐는 {@code queueCapacity} 로 제한하고, 꽉 차면 <b>제출하는 쪽에서 즉시 거절</b>된다(AbortPolicy)</li>
     *   <li>MDC(traceId)와 SecurityContext 를 전파하고, 작업 후 복원한다.
     *       힌트: Spring 6.1+ {@code ContextPropagatingTaskDecorator} + {@link Ctx#mdcAndSecurityFactory()}</li>
     *   <li>반환하기 전에 초기화까지 끝낸다(바로 쓸 수 있는 상태)</li>
     * </ol>
     */
    public static ThreadPoolTaskExecutor orderExecutor(int threads, int queueCapacity) {
        // TODO(E2): 크기·큐·거절 정책·데코레이터·초기화 (R11, R12, R3 예외 2)
        // SOLUTION-BEGIN
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("order-");
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator(Ctx.mdcAndSecurityFactory()));
        executor.initialize();
        return executor;
        // SOLUTION-END
    }

    /**
     * E3. "지금 이 스레드의 컨텍스트(MDC traceId + SecurityContext)"에 묶인 Executor 를 반환하라.
     *
     * <p>용도: 이벤트 루프(내가 통제하지 못하는 스레드)가 완료시키는 future 에 붙이는 콜백.
     * <pre>{@code
     * // 요청 스레드에서 파이프라인을 "조립"할 때 호출한다
     * redis.fetch(key).thenApplyAsync(v -> enrich(v), Exercises.boundToCurrentContext(io));
     * }</pre>
     * TaskDecorator 방식은 {@code execute()} 를 호출하는 스레드(= future 를 완료시킨 이벤트 루프)의
     * 컨텍스트를 캡처한다. 이 메서드는 캡처 시점을 <b>조립 시점(이 메서드를 호출한 순간)</b>으로 옮긴다.
     *
     * <ol>
     *   <li>반환된 executor 로 실행되는 작업은 <b>이 메서드를 호출한 시점의</b> traceId 와 user 를 본다
     *       (나중에 호출 스레드의 컨텍스트가 바뀌어도, execute 를 어느 스레드가 부르든).</li>
     *   <li>작업은 {@code executor} 에서 실행되고, 작업이 끝나면 그 스레드의 컨텍스트는 원래대로 돌아간다.</li>
     * </ol>
     */
    public static Executor boundToCurrentContext(Executor executor) {
        // TODO(E3): 캡처는 지금, 적용과 복원은 작업 스레드에서 (R12 마지막 항목)
        // SOLUTION-BEGIN
        ContextSnapshot snapshot = Ctx.mdcAndSecurityFactory().captureAll();
        return snapshot.wrapExecutor(executor);
        // SOLUTION-END
    }
}
