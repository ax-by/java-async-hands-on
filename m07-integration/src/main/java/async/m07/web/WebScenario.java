package async.m07.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.m07.ctx.Ctx;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * m07 서블릿 경계 예측 시나리오. <b>이 파일은 수정하지 않는다.</b>
 *
 * <p>실제 Tomcat 을 띄운다(랜덤 포트). 설정은 {@code m07-web.properties}:
 * <ul>
 *   <li>{@code server.tomcat.threads.max=2} : 요청 스레드(Tomcat worker)가 <b>2개뿐</b>이다</li>
 *   <li>{@code spring.mvc.async.request-timeout=700ms}</li>
 * </ul>
 * Tomcat worker 스레드 이름은 {@code http-nio-auto-1-exec-N} 이고 예측에서는 {@code On.pool("tomcat")} 으로 쓴다.
 * {@code io} 는 컨트롤러가 가진 16개짜리 풀이다.
 */
public final class WebScenario {

    private WebScenario() {
    }

    /** 단계 이름 → 실행 스레드 이름. 테스트가 매번 비운다. */
    public static final Map<String, String> THREADS = new ConcurrentHashMap<>();

    static void mark(String step) {
        THREADS.put(step, Thread.currentThread().getName());
    }

    /** 이 시나리오의 Spring Boot 애플리케이션 (DB 없음). */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @PropertySource("classpath:m07-web.properties")
    @Import({ScenarioController.class, ScenarioAdvice.class, PlainController.class})
    public static class App {

        /** 서블릿 필터: 요청 헤더 X-Trace-Id 를 MDC 에 넣고, 요청이 끝나면 지운다. */
        @Bean
        OncePerRequestFilter traceIdFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                        throws ServletException, IOException {
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

        /** 모든 요청 허용 + HTTP Basic (Authorization 헤더가 있으면 인증한다). 사용자 alice, bob (비밀번호 pw). */
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(c -> c.disable())
                    .build();
        }

        @Bean
        InMemoryUserDetailsManager users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("alice").password("{noop}pw").roles("USER").build(),
                    User.withUsername("bob").password("{noop}pw").roles("USER").build());
        }
    }

    /** 주문이 없을 때 서비스 계층이 던지는 예외. @ExceptionHandler 가 404 로 바꾼다. */
    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String id) {
            super("주문 없음: " + id);
        }
    }

    @RestController
    public static class ScenarioController {
        /**
         * 컨트롤러 전용 blocking 풀. (빈으로 등록하지 않는다: Executor 빈이 하나라도 있으면
         * Boot 의 applicationTaskExecutor 자동 구성이 물러난다 → VerifiedFactsTest 참고)
         */
        private final NamedPool io = Pools.fixed("io", 16);

        /** S19 에서 반환한 (영원히 완료되지 않는) future. */
        public static volatile CompletableFuture<String> lastNever;

        @jakarta.annotation.PreDestroy
        void close() {
            io.close();
        }

        /** S17 blocking: 300ms 걸리는 조회를 io 에 넘기고 컨트롤러에서 join 한다. */
        @GetMapping("/s/blocking")
        public String blocking() {
            return CompletableFuture.supplyAsync(() -> slowLookup(), io).join();
        }

        /** S17 async / S18: 같은 조회를 io 에 넘기고 future 를 그대로 반환한다. */
        @GetMapping("/s/async")
        public CompletableFuture<String> async() {
            mark("handler");
            return CompletableFuture.supplyAsync(() -> {
                mark("work");
                return slowLookup();
            }, io);
        }

        /**
         * S19: 다운스트림이 응답하지 않는다. 영원히 완료되지 않는 future 를 반환한다.
         * 타임아웃(700ms)이 지나면 Spring MVC 는 무엇을 하는가? 그 결과가 {@link ScenarioAdvice} 의
         * 핸들러에 걸리는지, 걸린다면 어느 것인지까지 따라가 보라.
         */
        @GetMapping("/s/never")
        public CompletableFuture<String> never() {
            lastNever = new CompletableFuture<>();
            return lastNever;
        }

        /** S20 cf: 실패한 future 를 반환한다. */
        @GetMapping("/s/fail-cf")
        public CompletableFuture<String> failCf() {
            return CompletableFuture.supplyAsync(() -> {
                throw new OrderNotFoundException("o-404");
            }, io);
        }

        /** S20 join: 컨트롤러 안에서 join 한다. */
        @GetMapping("/s/fail-join")
        public String failJoin() {
            return CompletableFuture.<String>supplyAsync(() -> {
                throw new OrderNotFoundException("o-404");
            }, io).join();
        }

        /** S20 get: 컨트롤러 안에서 get 한다. */
        @GetMapping("/s/fail-get")
        public String failGet() throws InterruptedException, ExecutionException {
            return CompletableFuture.<String>supplyAsync(() -> {
                throw new OrderNotFoundException("o-404");
            }, io).get();
        }

        /** S21 callable: Spring MVC 의 Callable 반환. 누가 인증된 사용자로 보일까? */
        @GetMapping("/s/me-callable")
        public Callable<String> meCallable() {
            return () -> {
                mark("callable");
                return Ctx.user();
            };
        }

        /** S21 cf: 같은 일을 supplyAsync(io) 로. */
        @GetMapping("/s/me-cf")
        public CompletableFuture<String> meCf() {
            return CompletableFuture.supplyAsync(Ctx::user, io);
        }

        private static String slowLookup() {
            Sleep.ms(300);
            return "order-1";
        }
    }

    /** ScenarioAdvice 가 적용되지 않는 컨트롤러 (VerifiedFactsTest 가 Spring 기본 동작을 확인하는 용도). */
    @RestController
    public static class PlainController {
        @GetMapping("/plain/never")
        public CompletableFuture<String> never() {
            return new CompletableFuture<>();
        }
    }

    @RestControllerAdvice(assignableTypes = ScenarioController.class)
    public static class ScenarioAdvice implements ResponseBodyAdvice<Object> {

        @ExceptionHandler(OrderNotFoundException.class)
        public ResponseEntity<String> notFound(OrderNotFoundException e) {
            return ResponseEntity.status(404).body("not found");
        }

        /** "예상 못 한 예외는 500 으로" 하는 흔한 catch-all 핸들러. */
        @ExceptionHandler(RuntimeException.class)
        public ResponseEntity<String> unexpected(RuntimeException e) {
            return ResponseEntity.status(500).body("internal error: " + e.getClass().getSimpleName());
        }

        @Override
        public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
            return true;
        }

        /** 응답 본문을 실제로 쓰는 스레드를 기록한다 (S18 write). */
        @Override
        public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
            if (request.getURI().getPath().equals("/s/async")) {
                mark("write");
            }
            return body;
        }
    }
}
