package async.m07.web;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import async.harness.Sleep;
import async.m07.Exercises;
import async.m07.ctx.Ctx;

/**
 * E5 의 주어진 부품 (수정하지 않는다). 구현 대상은 {@link ProductController}.
 *
 * <p>설정은 웹 시나리오와 같은 {@code m07-web.properties} (Tomcat worker 2개, async 타임아웃 700ms).
 * {@code orderExecutor} 빈은 <b>E2 에서 구현한</b> {@link Exercises#orderExecutor(int, int)} 로 만든다. E2 를 먼저 끝내라.
 */
public final class ProductExercise {

    private ProductExercise() {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @PropertySource("classpath:m07-web.properties")
    @Import({ProductController.class, ProductClient.class, ReviewClient.class})
    public static class App {

        @Bean
        ThreadPoolTaskExecutor orderExecutor() {
            return Exercises.orderExecutor(16, 100);
        }

        /** X-Trace-Id 헤더 → MDC (요청이 끝나면 지운다). */
        @Bean
        OncePerRequestFilter traceIdFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req,
                        jakarta.servlet.http.HttpServletResponse res, jakarta.servlet.FilterChain chain)
                        throws jakarta.servlet.ServletException, java.io.IOException {
                    String traceId = req.getHeader("X-Trace-Id");
                    if (traceId != null) {
                        org.slf4j.MDC.put(Ctx.TRACE_ID, traceId);
                    }
                    try {
                        chain.doFilter(req, res);
                    } finally {
                        org.slf4j.MDC.remove(Ctx.TRACE_ID);
                    }
                }
            };
        }

        @Bean
        org.springframework.security.web.SecurityFilterChain securityFilterChain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable()).build();
        }
    }

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(String id) {
            super("상품 없음: " + id);
        }
    }

    /** 상품 API (blocking 200ms). id 가 "missing" 이면 ProductNotFoundException. */
    @Component
    public static class ProductClient {
        /** 호출마다 "스레드이름 traceId" 를 기록한다. */
        public final Set<String> seen = ConcurrentHashMap.newKeySet();

        public String find(String id) {
            seen.add(Thread.currentThread().getName() + " " + Ctx.traceId());
            Sleep.ms(200);
            if (id.equals("missing")) {
                throw new ProductNotFoundException(id);
            }
            return "product:" + id;
        }
    }

    /** 리뷰 API (blocking 200ms). id 가 "slow" 면 2000ms. */
    @Component
    public static class ReviewClient {
        public final Set<String> seen = ConcurrentHashMap.newKeySet();

        public String summary(String id) {
            seen.add(Thread.currentThread().getName() + " " + Ctx.traceId());
            Sleep.ms(id.equals("slow") ? 2000 : 200);
            return "review:" + id;
        }
    }
}
