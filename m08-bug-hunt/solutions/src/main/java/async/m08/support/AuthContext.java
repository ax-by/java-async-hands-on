package async.m08.support;

/**
 * Spring Security {@code SecurityContextHolder} 의 대역. 현재 스레드의 로그인 사용자 이름을 {@link ThreadLocal} 에 둔다.
 */
public final class AuthContext {

    private static final ThreadLocal<String> USER = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 현재 스레드의 사용자. 없으면 null. */
    public static String get() {
        return USER.get();
    }

    /** null 을 넘기면 비운다. */
    public static void set(String user) {
        if (user == null) {
            USER.remove();
        } else {
            USER.set(user);
        }
    }

    public static void clear() {
        USER.remove();
    }

    public static String currentUserOr(String defaultUser) {
        String user = USER.get();
        return user == null ? defaultUser : user;
    }
}
