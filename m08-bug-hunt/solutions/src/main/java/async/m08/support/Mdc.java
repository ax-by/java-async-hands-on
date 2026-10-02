package async.m08.support;

import java.util.HashMap;
import java.util.Map;

/**
 * SLF4J {@code MDC} 의 대역. 값은 {@link ThreadLocal} 에 저장되므로 <b>스레드를 넘어가지 않는다</b>(R12).
 * 실무의 {@code org.slf4j.MDC} 와 같은 API 모양을 유지했다.
 */
public final class Mdc {

    private static final ThreadLocal<Map<String, String>> CONTEXT = new ThreadLocal<>();

    private Mdc() {
    }

    public static void put(String key, String value) {
        Map<String, String> map = CONTEXT.get();
        if (map == null) {
            map = new HashMap<>();
            CONTEXT.set(map);
        }
        map.put(key, value);
    }

    public static String get(String key) {
        Map<String, String> map = CONTEXT.get();
        return map == null ? null : map.get(key);
    }

    /** 현재 스레드의 MDC 복사본. 비어 있으면 null. */
    public static Map<String, String> getCopyOfContextMap() {
        Map<String, String> map = CONTEXT.get();
        return map == null ? null : new HashMap<>(map);
    }

    /** 현재 스레드의 MDC 를 통째로 바꾼다. null 이면 비운다. */
    public static void setContextMap(Map<String, String> map) {
        if (map == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(new HashMap<>(map));
        }
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
