package async.m07.async;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import async.m07.ctx.Ctx;

/**
 * E6. 운영에서 쓸 {@code @Async} 설정을 명시적으로 작성한다.
 *
 * <p>요구사항
 * <ol>
 *   <li>{@link #asyncExecutor()}: 스레드 이름 {@code "async-"}, core = max = 4, 큐 100 (무제한 금지),
 *       MDC(traceId)와 SecurityContext 를 전파하고 작업 후 복원한다. 초기화는 Spring 이 빈 생명주기로 한다.</li>
 *   <li>{@link #getAsyncExecutor()}: 위 빈을 {@code @Async} 의 기본 executor 로 쓴다.</li>
 *   <li>{@link #getAsyncUncaughtExceptionHandler()}: <b>void</b> {@code @Async} 메서드의 예외를
 *       {@link #FAILURES} 에 {@code "메서드이름: 메시지"} 형태로 남긴다(운영에서는 로그 + 메트릭).</li>
 * </ol>
 * CF 를 반환하는 {@code @Async} 메서드의 예외는 이 핸들러로 오지 않는다(R6). 호출자가 future 를 관찰해야 한다.
 */
@Configuration
@EnableAsync
@Import(AsyncExerciseConfig.NotificationService.class)
public class AsyncExerciseConfig implements AsyncConfigurer {

    public static final List<String> FAILURES = new CopyOnWriteArrayList<>();

    @Bean
    public ThreadPoolTaskExecutor asyncExecutor() {
        // TODO(E6): ThreadPoolTaskExecutor 를 만들어 이름/크기/큐/데코레이터를 설정한다 (initialize 는 Spring 이 호출) (R11, R12)
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public Executor getAsyncExecutor() {
        // TODO(E6): @Configuration 클래스 안에서 @Bean 메서드를 호출하면 무엇이 반환되는가?
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // TODO(E6): (ex, method, params) -> ...
        throw new UnsupportedOperationException("TODO");
    }

    /** 주어진 서비스 (수정하지 않는다). */
    @Service
    public static class NotificationService {

        /** 알림 발송. 실행 스레드와 그때 보인 traceId/user 를 돌려준다. */
        @Async
        public CompletableFuture<String> send(String to) {
            return CompletableFuture.completedFuture(Thread.currentThread().getName() + " " + Ctx.both());
        }

        /** 실패하는 void 발송. */
        @Async
        public void sendAndForget(String to) {
            throw new IllegalStateException("SMTP 장애: " + to);
        }
    }
}
