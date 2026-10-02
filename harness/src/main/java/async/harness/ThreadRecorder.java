package async.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 스레드 기록기. 파이프라인의 각 단계가 "어느 스레드에서, 시작 후 몇 ms 에" 실행됐는지 기록한다.
 *
 * <pre>{@code
 * ThreadRecorder rec = ThreadRecorder.start();   // 호출한 스레드가 caller 가 된다
 * CompletableFuture.supplyAsync(() -> { rec.mark("supply"); return 1; }, io)
 *                  .thenApply(v -> { rec.mark("map"); return v + 1; });
 * rec.on("map");      // -> On.pool("io") 또는 On.caller() ...
 * rec.atMillis("map") // -> 시작 후 경과 ms
 * }</pre>
 *
 * 같은 이름으로 여러 번 mark 하면 마지막 기록이 남고, {@link #count(String)} 로 횟수를 볼 수 있다.
 */
public final class ThreadRecorder {

    /** 한 번의 기록. */
    public record Mark(String step, String threadName, On on, long atMillis) {
    }

    private final Thread caller;
    private final long startNanos;
    private final Map<String, Mark> last = new ConcurrentHashMap<>();
    private final List<Mark> all = new CopyOnWriteArrayList<>();

    private ThreadRecorder(Thread caller) {
        this.caller = caller;
        this.startNanos = System.nanoTime();
    }

    /** 현재 스레드를 caller 로 하는 기록기를 시작한다. */
    public static ThreadRecorder start() {
        return new ThreadRecorder(Thread.currentThread());
    }

    /** 현재 스레드에서 step 이 실행되었음을 기록한다. */
    public void mark(String step) {
        Thread t = Thread.currentThread();
        Mark m = new Mark(step, t.getName(), On.classify(t, caller), elapsedMillis());
        last.put(step, m);
        all.add(m);
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public boolean has(String step) {
        return last.containsKey(step);
    }

    /** step 이 실행된 스레드 분류. 실행되지 않았다면 예외. */
    public On on(String step) {
        return require(step).on();
    }

    public String threadName(String step) {
        return require(step).threadName();
    }

    public long atMillis(String step) {
        return require(step).atMillis();
    }

    public int count(String step) {
        return (int) all.stream().filter(m -> m.step().equals(step)).count();
    }

    /** 기록된 순서대로 step 이름 목록 (중복 포함). */
    public List<String> order() {
        return all.stream().map(Mark::step).collect(Collectors.toList());
    }

    public List<Mark> marks() {
        return new ArrayList<>(all);
    }

    public Thread callerThread() {
        return caller;
    }

    private Mark require(String step) {
        Mark m = last.get(step);
        if (m == null) {
            throw new AssertionError("단계 '" + step + "' 는 실행되지 않았다. 기록된 단계: " + order());
        }
        return m;
    }

    /** 사람이 읽기 쉬운 표. 테스트 실패 메시지와 디버깅에 쓴다. */
    public String dump() {
        StringBuilder sb = new StringBuilder("step                 thread                                   on                 at(ms)\n");
        for (Mark m : all) {
            sb.append(String.format("%-20s %-40s %-18s %d%n", m.step(), m.threadName(), m.on().label(), m.atMillis()));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return dump();
    }
}
