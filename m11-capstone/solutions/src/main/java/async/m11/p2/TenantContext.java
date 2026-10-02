package async.m11.p2;

/**
 * 멀티테넌트 컨텍스트. 웹 필터가 요청 스레드의 {@link ThreadLocal} 에 테넌트 ID 를 넣어 준다. (주어진 타입)
 * ThreadLocal 이므로 스레드를 넘어가지 않는다 (R12).
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String get() {
        return TENANT.get();
    }

    public static void set(String tenantId) {
        if (tenantId == null) {
            TENANT.remove();
        } else {
            TENANT.set(tenantId);
        }
    }

    public static void clear() {
        TENANT.remove();
    }
}
