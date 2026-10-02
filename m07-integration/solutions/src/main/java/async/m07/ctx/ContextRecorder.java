package async.m07.ctx;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import async.harness.On;
import async.harness.ThreadRecorder;

/**
 * {@link ThreadRecorder} + "그 시점에 보인 컨텍스트" 기록기.
 * {@code rec.mark("map")} 을 호출한 스레드와, 그 스레드에서 보인 traceId / user 를 함께 기록한다.
 */
public final class ContextRecorder {

    private final ThreadRecorder threads = ThreadRecorder.start();
    private final Map<String, String> traceIds = new ConcurrentHashMap<>();
    private final Map<String, String> users = new ConcurrentHashMap<>();

    public void mark(String step) {
        traceIds.put(step, Ctx.traceId());
        users.put(step, Ctx.user());
        threads.mark(step);
    }

    public String traceId(String step) {
        return require(traceIds, step);
    }

    public String user(String step) {
        return require(users, step);
    }

    public On on(String step) {
        return threads.on(step);
    }

    private String require(Map<String, String> m, String step) {
        String v = m.get(step);
        if (v == null) {
            throw new AssertionError("단계 '" + step + "' 는 실행되지 않았다. 기록: " + threads.order());
        }
        return v;
    }

    @Override
    public String toString() {
        return threads.dump() + "traceIds=" + traceIds + ", users=" + users;
    }
}
