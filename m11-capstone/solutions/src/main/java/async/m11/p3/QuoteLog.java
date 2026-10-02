package async.m11.p3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 메모리 로그 (SLF4J 대역, non-blocking). (주어진 타입) */
public final class QuoteLog {

    private static final List<String> LINES = new CopyOnWriteArrayList<>();

    private QuoteLog() {
    }

    public static void info(String line) {
        LINES.add(line);
    }

    public static List<String> lines() {
        return List.copyOf(LINES);
    }
}
