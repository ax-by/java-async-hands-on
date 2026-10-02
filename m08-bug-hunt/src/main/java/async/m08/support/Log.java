package async.m08.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SLF4J Logger 의 대역. 로그를 메모리에 남겨 테스트가 "무엇이, 어느 스레드에서, 어떤 traceId 로" 기록됐는지 볼 수 있게 한다.
 * 각 로그는 기록 시점 스레드의 {@link Mdc} 의 {@code traceId} 를 함께 남긴다(실무의 로그 패턴 {@code %X{traceId}} 와 같다).
 * 메모리에만 쓰므로 blocking 이 아니다.
 */
public final class Log {

    public enum Level { INFO, WARN, ERROR }

    /** 기록된 로그 한 줄. */
    public record Entry(Level level, String message, Throwable error, String traceId, String thread) {
        @Override
        public String toString() {
            return level + " [" + thread + "] traceId=" + traceId + " " + message + (error == null ? "" : " (" + error + ")");
        }
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    private Log() {
    }

    public static void info(String message) {
        add(Level.INFO, message, null);
    }

    public static void warn(String message) {
        add(Level.WARN, message, null);
    }

    public static void warn(String message, Throwable error) {
        add(Level.WARN, message, error);
    }

    public static void error(String message, Throwable error) {
        add(Level.ERROR, message, error);
    }

    private static void add(Level level, String message, Throwable error) {
        ENTRIES.add(new Entry(level, message, error, Mdc.get("traceId"), Thread.currentThread().getName()));
    }

    public static List<Entry> entries() {
        return List.copyOf(ENTRIES);
    }

    public static void reset() {
        ENTRIES.clear();
    }
}
