package async.harness;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDBC, RestTemplate, 파일 I/O 처럼 "호출한 스레드를 응답이 올 때까지 멈추게 하는" 서비스의 대역.
 *
 * <p>내부적으로 {@link Thread#sleep(long)} 을 쓰므로 BlockHound 가 blocking 호출로 감지한다.
 *
 * <pre>{@code
 * FakeBlockingService userDb = new FakeBlockingService("user-db", 100);
 * String user = userDb.call("u1");       // 호출 스레드가 100ms 멈춘다. 결과: "user-db:u1"
 * userDb.maxConcurrency();               // 동시에 몇 개의 호출이 진행됐는지 (병렬 fan-out 검증용)
 * }</pre>
 */
public final class FakeBlockingService {

    private final String name;
    private final long defaultLatencyMillis;
    private final Map<String, Long> latencyByKey = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> failureByKey = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger interrupted = new AtomicInteger();
    private final Set<String> callerThreads = ConcurrentHashMap.newKeySet();

    public FakeBlockingService(String name, long latencyMillis) {
        this.name = name;
        this.defaultLatencyMillis = latencyMillis;
    }

    /** 특정 키만 지연시간을 다르게 한다. */
    public FakeBlockingService latencyFor(String key, long millis) {
        latencyByKey.put(key, millis);
        return this;
    }

    /** 특정 키를 호출하면 지연 후 예외를 던진다. */
    public FakeBlockingService failOn(String key, RuntimeException failure) {
        failureByKey.put(key, failure);
        return this;
    }

    /**
     * blocking 호출. 지연시간만큼 호출 스레드를 멈춘 뒤 "{name}:{key}" 를 반환한다.
     * 스레드가 interrupt 되면 {@link IllegalStateException} 으로 즉시 중단한다.
     */
    public String call(String key) {
        calls.incrementAndGet();
        callerThreads.add(Thread.currentThread().getName());
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            Thread.sleep(latencyByKey.getOrDefault(key, defaultLatencyMillis));
        } catch (InterruptedException e) {
            interrupted.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " 호출이 interrupt 로 중단됨: " + key, e);
        } finally {
            inFlight.decrementAndGet();
        }
        RuntimeException failure = failureByKey.get(key);
        if (failure != null) {
            throw failure;
        }
        return name + ":" + key;
    }

    public String name() {
        return name;
    }

    public int callCount() {
        return calls.get();
    }

    /** 지금 진행 중인 호출 수. 타임아웃 후에도 실제 작업이 계속 도는지 확인할 때 쓴다. */
    public int inFlight() {
        return inFlight.get();
    }

    /** 동시에 진행된 호출의 최대 개수. */
    public int maxConcurrency() {
        return maxInFlight.get();
    }

    /** interrupt 로 중단된 호출 수. */
    public int interruptedCount() {
        return interrupted.get();
    }

    /** 이 서비스를 호출한 스레드 이름들. */
    public Set<String> callerThreads() {
        return Set.copyOf(callerThreads);
    }

    public Duration defaultLatency() {
        return Duration.ofMillis(defaultLatencyMillis);
    }
}
