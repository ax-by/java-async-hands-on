package async.harness;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Netty 기반 비동기 클라이언트(Lettuce, AWS SDK v2, DataStax 드라이버 등)의 대역.
 *
 * <ul>
 *   <li>요청을 보내는 스레드는 막히지 않는다 ({@link #fetch(String)} 은 즉시 CompletableFuture 를 반환).</li>
 *   <li>응답이 도착하면 <b>이벤트 루프 스레드("event-loop-1")가 future 를 완료</b>시킨다.
 *       따라서 그 future 에 붙은 non-async 콜백은 (아직 완료 전에 붙였다면) 이벤트 루프에서 실행된다.</li>
 *   <li>이벤트 루프가 막히면 그 뒤의 모든 응답이 늦게 도착한다. 이 지연을 {@link #maxLagMillis()} 로 측정한다.</li>
 * </ul>
 *
 * 이벤트 루프 스레드는 {@link BlockHoundSupport} 에서 "막히면 안 되는 스레드"로 등록되어 있다.
 */
public final class FakeEventLoopClient implements AutoCloseable {

    public static final String THREAD_PREFIX = "event-loop";

    private final String name;
    private final long defaultLatencyMillis;
    private final ScheduledThreadPoolExecutor loop;
    private final Executor completionExecutor;
    private final Map<String, Long> latencyByKey = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> failureByKey = new ConcurrentHashMap<>();
    private final AtomicLong maxLag = new AtomicLong();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();

    /** 이벤트 루프 스레드 1개, 이벤트 루프에서 직접 future 를 완료시키는 클라이언트. */
    public FakeEventLoopClient(String name, long latencyMillis) {
        this(name, latencyMillis, 1, null);
    }

    /**
     * @param completionExecutor null 이면 이벤트 루프에서 직접 완료시킨다.
     *                           null 이 아니면 응답을 이 executor 로 넘겨 그 스레드에서 완료시킨다
     *                           (AWS SDK v2 의 FUTURE_COMPLETION_EXECUTOR 와 같은 설계).
     */
    public FakeEventLoopClient(String name, long latencyMillis, int loopThreads, Executor completionExecutor) {
        this.name = name;
        this.defaultLatencyMillis = latencyMillis;
        this.loop = new ScheduledThreadPoolExecutor(loopThreads, Pools.namedFactory(THREAD_PREFIX));
        this.loop.setRemoveOnCancelPolicy(true);
        this.completionExecutor = completionExecutor;
        BlockHoundSupport.installIfAvailable();
    }

    public FakeEventLoopClient latencyFor(String key, long millis) {
        latencyByKey.put(key, millis);
        return this;
    }

    public FakeEventLoopClient failOn(String key, RuntimeException failure) {
        failureByKey.put(key, failure);
        return this;
    }

    /** non-blocking 요청. 호출 즉시 반환하고, 지연시간 후 이벤트 루프가 "{name}:{key}" 로 완료시킨다. */
    public CompletableFuture<String> fetch(String key) {
        requests.incrementAndGet();
        CompletableFuture<String> result = new CompletableFuture<>();
        long latency = latencyByKey.getOrDefault(key, defaultLatencyMillis);
        long due = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(latency);
        loop.schedule(() -> {
            long lag = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - due);
            maxLag.accumulateAndGet(lag, Math::max);
            Runnable complete = () -> {
                completed.incrementAndGet();
                RuntimeException failure = failureByKey.get(key);
                if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    result.complete(name + ":" + key);
                }
            };
            if (completionExecutor == null) {
                complete.run();
            } else {
                completionExecutor.execute(complete);
            }
        }, latency, TimeUnit.MILLISECONDS);
        return result;
    }

    /** 현재 스레드가 이 클라이언트의 이벤트 루프인지. */
    public static boolean inEventLoop() {
        return Thread.currentThread().getName().startsWith(THREAD_PREFIX + "-");
    }

    /**
     * 예정된 응답 시각보다 실제로 얼마나 늦게 처리됐는지의 최댓값(ms).
     * 이벤트 루프 위에서 누군가 blocking 하면 이 값이 커진다.
     */
    public long maxLagMillis() {
        return maxLag.get();
    }

    public int requestCount() {
        return requests.get();
    }

    public int completedCount() {
        return completed.get();
    }

    public String name() {
        return name;
    }

    @Override
    public void close() {
        loop.shutdownNow();
    }
}
