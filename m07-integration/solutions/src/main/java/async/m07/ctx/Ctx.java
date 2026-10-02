package async.m07.ctx;

import java.util.List;

import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderThreadLocalAccessor;
import org.springframework.security.core.context.SecurityContextImpl;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;

/**
 * "요청 컨텍스트" 도우미. 실무의 두 ThreadLocal 컨텍스트를 다룬다.
 * <ul>
 *   <li>MDC 의 {@code traceId} (로그 상관관계 ID)</li>
 *   <li>{@link SecurityContextHolder} 의 인증 사용자 (Spring Security)</li>
 * </ul>
 * 값이 없으면 {@link #NONE} 을 돌려준다(예측 시트에서 null 은 "아직 예측하지 않음"이라는 뜻이라서).
 */
public final class Ctx {

    public static final String TRACE_ID = "traceId";
    public static final String NONE = "NONE";

    private Ctx() {
    }

    /** 현재 스레드를 "traceId, user 로 인증된 요청을 처리 중인 스레드"로 만든다. 서블릿 필터가 하는 일과 같다. */
    public static void enter(String traceId, String user) {
        MDC.put(TRACE_ID, traceId);
        SecurityContextHolder.setContext(new SecurityContextImpl(authenticated(user)));
    }

    /** 요청 처리가 끝났을 때 필터가 하는 정리. */
    public static void clear() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    public static String traceId() {
        String v = MDC.get(TRACE_ID);
        return v == null ? NONE : v;
    }

    public static String user() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? NONE : a.getName();
    }

    /** "traceId/user" 형태. */
    public static String both() {
        return traceId() + "/" + user();
    }

    public static Authentication authenticated(String user) {
        return UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
    }

    /**
     * MDC(traceId)와 SecurityContext 를 함께 캡처/복원하는 Micrometer Context Propagation 팩토리.
     *
     * <p>전역 {@link ContextRegistry#getInstance()} 에는 ServiceLoader 로 Spring Security 의
     * {@link SecurityContextHolderThreadLocalAccessor} 가 자동 등록되지만 <b>MDC 접근자는 등록되지 않는다</b>
     * (Micrometer Tracing 이 없으면). 그래서 전용 레지스트리를 만들어 둘 다 명시적으로 등록한다.
     */
    public static ContextSnapshotFactory mdcAndSecurityFactory() {
        ContextRegistry registry = new ContextRegistry()
                .registerThreadLocalAccessor(new Slf4jThreadLocalAccessor(TRACE_ID))
                .registerThreadLocalAccessor(new SecurityContextHolderThreadLocalAccessor());
        return ContextSnapshotFactory.builder().contextRegistry(registry).build();
    }
}
