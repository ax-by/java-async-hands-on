// SOLUTIONS-BRANCH-ONLY: 예측 정답에 해당하는 사실을 단언하므로 solutions/src/test 에만 둔다(학습자용 src/test 에는 없다).
package async.m07;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolderThreadLocalAccessor;

import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.ThreadRecorder;
import async.m07.async.AsyncScenario;
import async.m07.web.WebScenario;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;

/**
 * README 에 적은 "Spring Boot 3.5 / Spring Framework 6.2 / Security 6.5 의 실제 동작"을 실험으로 고정한다.
 * 예측 채점이 아니다. 라이브러리 버전을 올렸을 때 이 테스트가 깨지면 README 를 다시 검증하라.
 */
@Timeout(30)
class VerifiedFactsTest {

    @Nested
    @SpringBootTest(classes = AsyncScenario.DefaultConfig.class)
    class BootDefaultExecutor {
        @Autowired ApplicationContext ctx;

        @Test
        void applicationTaskExecutorDefaults() {
            ThreadPoolTaskExecutor e = ctx.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(e.getCorePoolSize()).as("core").isEqualTo(8);
            assertThat(e.getMaxPoolSize()).as("max (큐가 무제한이라 의미 없음)").isEqualTo(Integer.MAX_VALUE);
            assertThat(e.getQueueCapacity()).as("queue: 무제한").isEqualTo(Integer.MAX_VALUE);
            assertThat(e.getThreadNamePrefix()).isEqualTo("task-");
            assertThat(e.getKeepAliveSeconds()).isEqualTo(60);
            assertThat(ctx.getAliases("applicationTaskExecutor")).as("Boot 3.5: taskExecutor 별칭은 없다").containsExactly("bootstrapExecutor");
            assertThat(ctx.containsBean("taskExecutor")).isFalse();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(TaskExecutionAutoConfiguration.class)
    static class OneUserExecutor {
        @Bean(destroyMethod = "close")
        NamedPool myIo() {
            return Pools.fixed("io", 2);
        }
    }

    @Nested
    @SpringBootTest(classes = OneUserExecutor.class)
    class BacksOff {
        @Autowired ApplicationContext ctx;

        @Test
        void anyExecutorBeanDisablesApplicationTaskExecutor() {
            assertThat(ctx.getBeanNamesForType(Executor.class)).containsExactly("myIo");
        }
    }

    @Nested
    @SpringBootTest(classes = OneUserExecutor.class, properties = "spring.task.execution.mode=force")
    class ForceMode {
        @Autowired ApplicationContext ctx;

        @Test
        void forceModeKeepsApplicationTaskExecutor() {
            assertThat(ctx.getBeanNamesForType(Executor.class)).contains("myIo", "applicationTaskExecutor");
        }
    }

    @Nested
    @SpringBootTest(classes = async.m07.tx.TxScenario.Config.class,
            properties = "spring.datasource.url=jdbc:h2:mem:m07tx;DB_CLOSE_DELAY=-1")
    class Hikari {
        @Autowired javax.sql.DataSource dataSource;

        @Test
        void defaultConnectionTimeoutIs30Seconds() {
            assertThat(((com.zaxxer.hikari.HikariDataSource) dataSource).getConnectionTimeout()).isEqualTo(30_000);
            assertThat(((com.zaxxer.hikari.HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(10);
        }
    }

    @Nested
    @SpringBootTest(classes = AsyncScenario.DefaultConfig.class, properties = "spring.threads.virtual.enabled=true")
    class VirtualThreads {
        @Autowired ApplicationContext ctx;
        @Autowired AsyncScenario.ReportService reports;

        @Test
        void virtualThreadModeUsesSimpleAsyncTaskExecutor() throws Exception {
            assertThat(ctx.getBean("applicationTaskExecutor")).isInstanceOf(SimpleAsyncTaskExecutor.class);
            ThreadRecorder rec = ThreadRecorder.start();
            reports.render(rec, "vt").get(2, TimeUnit.SECONDS);
            assertThat(rec.threadName("vt")).startsWith("task-");
            assertThat(rec.on("vt")).as("JDK 21+ 에서만. Java 17 운영에서는 이 속성이 효과가 없다").isEqualTo(async.harness.On.virtualThread());
        }
    }

    @Nested
    @SpringBootTest(classes = WebScenario.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class Web {
        @LocalServerPort int port;
        final HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> get(String path, String basic) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
            if (basic != null) {
                b.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(basic.getBytes()));
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Test
        void asyncTimeoutWithoutCatchAllHandlerIs503() throws Exception {
            assertThat(get("/plain/never", null).statusCode()).isEqualTo(503);
        }

        @Test
        void callableRunsOnApplicationTaskExecutor() throws Exception {
            WebScenario.THREADS.clear();
            assertThat(get("/s/me-callable", "alice:pw").body()).isEqualTo("alice");
            assertThat(WebScenario.THREADS.get("callable")).startsWith("task-");
        }
    }

    @Nested
    class Plain {
        @Test
        void globalRegistryHasSecurityButNoMdcAccessor() {
            List<Class<?>> accessors = ContextRegistry.getInstance().getThreadLocalAccessors().stream()
                    .<Class<?>>map(ThreadLocalAccessor::getClass).toList();
            assertThat(accessors).as("Spring Security 가 ServiceLoader 로 자동 등록").contains(SecurityContextHolderThreadLocalAccessor.class);
            assertThat(accessors).as("MDC 접근자는 자동 등록되지 않는다")
                    .noneMatch(c -> c.getName().toLowerCase().contains("mdc") || c.getName().toLowerCase().contains("slf4j"));
        }

        @Test
        void requestContextHolderIsThreadBound() throws Exception {
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(
                            new org.springframework.mock.web.MockHttpServletRequest()));
            try (NamedPool io = Pools.single("io")) {
                Object attrs = CompletableFuture.supplyAsync(
                        org.springframework.web.context.request.RequestContextHolder::getRequestAttributes, io).get(2, TimeUnit.SECONDS);
                assertThat(attrs).isNull();
                assertThat(CompletableFuture.supplyAsync(() -> {
                    try {
                        org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
                        return "bound";
                    } catch (IllegalStateException e) {
                        return "IllegalStateException";
                    }
                }, io).get(2, TimeUnit.SECONDS)).isEqualTo("IllegalStateException");
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        void asyncCallbackSubmissionHappensOnCompletingThread() {
            AtomicReference<String> executeCalledOn = new AtomicReference<>();
            try (NamedPool io = Pools.single("io"); FakeEventLoopClient redis = new FakeEventLoopClient("redis", 100)) {
                Executor spy = task -> {
                    executeCalledOn.set(Thread.currentThread().getName());
                    io.execute(task);
                };
                redis.fetch("k").thenApplyAsync(v -> v, spy).join();
            }
            assertThat(executeCalledOn.get()).as("TaskDecorator.decorate 도 이 스레드에서 불린다").startsWith("event-loop-");
        }

        @Test
        void completedFutureSubmissionHappensOnRegisteringThread() {
            AtomicReference<String> executeCalledOn = new AtomicReference<>();
            try (NamedPool io = Pools.single("io")) {
                Executor spy = task -> {
                    executeCalledOn.set(Thread.currentThread().getName());
                    io.execute(task);
                };
                CompletableFuture.completedFuture("v").thenApplyAsync(v -> v, spy).join();
            }
            assertThat(executeCalledOn.get()).isEqualTo(Thread.currentThread().getName());
        }
    }
}
