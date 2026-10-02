package async.m07.async;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import async.harness.Sleep;
import async.harness.ThreadRecorder;

/**
 * m07 {@code @Async} 예측 시나리오. <b>이 파일은 수정하지 않는다.</b>
 *
 * <p>Spring Boot 3.5 의 자동 구성({@link TaskExecutionAutoConfiguration})을 그대로 쓴다.
 * 별도 설정(spring.task.execution.*)은 하지 않았다.
 */
public final class AsyncScenario {

    private AsyncScenario() {
    }

    /** void @Async 메서드에서 빠져나온 예외를 기록하는 핸들러. */
    public static final List<Throwable> UNCAUGHT = new CopyOnWriteArrayList<>();

    /** 기본 구성: Boot 자동 구성 executor + 예외 핸들러만 지정. */
    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @Import(ReportService.class)
    @ImportAutoConfiguration(TaskExecutionAutoConfiguration.class)
    public static class DefaultConfig implements AsyncConfigurer {
        @Override
        public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
            return (ex, method, params) -> UNCAUGHT.add(ex);
        }
    }

    /**
     * S16 구성: 팀원이 CF fan-out 용으로 executor 빈을 <b>두 개</b> 추가했다.
     * {@code @Async} 메서드 자체는 아무것도 바꾸지 않았다.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @Import(ReportService.class)
    @ImportAutoConfiguration(TaskExecutionAutoConfiguration.class)
    public static class TwoExecutorsConfig {
        @Bean
        ThreadPoolTaskExecutor productExecutor() {
            ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
            e.setThreadNamePrefix("product-");
            e.setCorePoolSize(4);
            return e;
        }

        @Bean
        ThreadPoolTaskExecutor reviewExecutor() {
            ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
            e.setThreadNamePrefix("review-");
            e.setCorePoolSize(4);
            return e;
        }
    }

    @Service
    public static class ReportService {

        /** @Async + CF 반환. 실행 스레드를 기록한다. */
        @Async
        public CompletableFuture<String> render(ThreadRecorder rec, String step) {
            rec.mark(step);
            return CompletableFuture.completedFuture("report");
        }

        /** S13 self: 같은 빈 안에서 @Async 메서드를 호출한다. */
        public CompletableFuture<String> renderViaThis(ThreadRecorder rec) {
            return render(rec, "self");
        }

        /** S14: 200ms 걸리는 리포트 생성 (blocking). */
        @Async
        public CompletableFuture<String> renderSlow(String id) {
            Sleep.ms(200);
            return CompletableFuture.completedFuture("report-" + id);
        }

        /** S15 void: 실패하는 void @Async. */
        @Async
        public void sendMailVoid() {
            throw new IllegalStateException("메일 서버 장애 (void)");
        }

        /** S15 cf: 실패하는 CF 반환 @Async. 호출자는 반환값을 무시한다. */
        @Async
        public CompletableFuture<Void> sendMailFuture() {
            throw new IllegalStateException("메일 서버 장애 (future)");
        }
    }
}
