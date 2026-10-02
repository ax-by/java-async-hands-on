package async.m09.minikv;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * minikv 의 가짜 네트워크 계층. Netty 같은 <b>이벤트 루프 기반 I/O</b> 를 흉내 낸다.
 * <b>이 파일은 수정하지 않는다.</b>
 *
 * <ul>
 *   <li>{@link #send(String, BiConsumer)} 는 요청을 "보내고" 즉시 반환한다(non-blocking).</li>
 *   <li>지연시간이 지나면 <b>I/O 스레드</b>({@value #IO_THREAD_PREFIX}-1)가 콜백을 호출한다.
 *       성공이면 {@code ("val:" + key, null)}, 실패로 설정된 키면 {@code (null, 예외)} 다.</li>
 *   <li>I/O 스레드는 하나다. 누군가 그 위에서 blocking 하면 <b>다른 모든 응답</b>이 늦어진다({@link #maxLagMillis()}).</li>
 *   <li>스레드 이름이 {@code nonblocking-} 으로 시작하므로 테스트의 BlockHound 가 이 스레드 위의 blocking 을 감지한다.</li>
 *   <li>{@link Request#abort()} 로 아직 응답이 오지 않은 요청을 취소할 수 있다(소켓에 RST 를 보내는 것에 해당).
 *       취소된 요청의 콜백은 호출되지 않는다.</li>
 * </ul>
 *
 * Java 8 호환 코드다(이 모듈의 main 은 {@code --release 8} 로 컴파일된다).
 */
public final class FakeTransport implements AutoCloseable {

    public static final String IO_THREAD_PREFIX = "nonblocking-minikv-io";

    /** 보낸 요청 하나. */
    public interface Request {
        /** 응답 전이면 요청을 취소하고 true. 이미 응답했거나 취소됐으면 false. 어느 스레드에서 호출해도 안전하다. */
        boolean abort();

        boolean isAborted();
    }

    private final long defaultLatencyMillis;
    private final ScheduledThreadPoolExecutor io;
    private final Set<Thread> ioThreads = Collections.newSetFromMap(new ConcurrentHashMap<Thread, Boolean>());
    private final Map<String, Long> latencyByKey = new ConcurrentHashMap<>();
    private final Map<String, Throwable> failureByKey = new ConcurrentHashMap<>();
    private final Set<String> senderThreads = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger responses = new AtomicInteger();
    private final AtomicInteger aborted = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong maxLag = new AtomicLong();

    public FakeTransport(long defaultLatencyMillis) {
        this.defaultLatencyMillis = defaultLatencyMillis;
        final AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, IO_THREAD_PREFIX + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            ioThreads.add(t);
            return t;
        };
        this.io = new ScheduledThreadPoolExecutor(1, factory);
        this.io.setRemoveOnCancelPolicy(true);
    }

    /** 특정 키의 응답 지연시간을 바꾼다. */
    public FakeTransport latencyFor(String key, long millis) {
        latencyByKey.put(key, millis);
        return this;
    }

    /** 특정 키의 응답을 실패(지연 후 콜백에 예외 전달)로 만든다. */
    public FakeTransport failOn(String key, Throwable failure) {
        failureByKey.put(key, failure);
        return this;
    }

    /**
     * 요청을 보낸다. <b>즉시 반환</b>하고, 지연시간 후 I/O 스레드가 {@code onResponse} 를 정확히 한 번 호출한다
     * (취소되지 않은 경우). transport 가 닫혀 있으면 호출 스레드에서 즉시 {@link IOException} 으로 콜백한다.
     */
    public Request send(final String key, final BiConsumer<String, Throwable> onResponse) {
        requests.incrementAndGet();
        senderThreads.add(Thread.currentThread().getName());
        inFlight.incrementAndGet();
        long latency = latencyByKey.containsKey(key) ? latencyByKey.get(key) : defaultLatencyMillis;
        final long due = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(latency);
        final RequestImpl request = new RequestImpl();
        try {
            request.task = io.schedule(() -> {
                if (!request.state.compareAndSet(RequestImpl.PENDING, RequestImpl.DONE)) {
                    return;
                }
                inFlight.decrementAndGet();
                maxLag.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - due), Math::max);
                responses.incrementAndGet();
                Throwable failure = failureByKey.get(key);
                if (failure != null) {
                    onResponse.accept(null, failure);
                } else {
                    onResponse.accept("val:" + key, null);
                }
            }, latency, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closed) {
            request.state.set(RequestImpl.DONE);
            inFlight.decrementAndGet();
            onResponse.accept(null, new IOException("transport closed"));
        }
        return request;
    }

    /** 현재 스레드가 이 transport 의 I/O 스레드인가 (Netty 의 {@code inEventLoop()} 에 해당). */
    public boolean inIoThread() {
        return ioThreads.contains(Thread.currentThread());
    }

    /** 보낸 요청 수. */
    public int requestCount() {
        return requests.get();
    }

    /** 실제로 응답(성공/실패)이 전달된 수. */
    public int responseCount() {
        return responses.get();
    }

    /** 응답 전에 취소된 요청 수. */
    public int abortedCount() {
        return aborted.get();
    }

    /** 아직 응답도 취소도 되지 않은 요청 수. */
    public int inFlight() {
        return inFlight.get();
    }

    /** 응답 예정 시각보다 실제 전달이 늦어진 최댓값(ms). I/O 스레드 위의 blocking 을 드러낸다. */
    public long maxLagMillis() {
        return maxLag.get();
    }

    /** {@link #send} 를 호출한 스레드 이름들. */
    public Set<String> senderThreads() {
        return Collections.unmodifiableSet(senderThreads);
    }

    @Override
    public void close() {
        io.shutdownNow();
    }

    private final class RequestImpl implements Request {
        static final int PENDING = 0;
        static final int DONE = 1;
        static final int ABORTED = 2;

        final AtomicInteger state = new AtomicInteger(PENDING);
        volatile ScheduledFuture<?> task;

        @Override
        public boolean abort() {
            if (!state.compareAndSet(PENDING, ABORTED)) {
                return false;
            }
            ScheduledFuture<?> t = task;
            if (t != null) {
                t.cancel(false);
            }
            inFlight.decrementAndGet();
            aborted.incrementAndGet();
            return true;
        }

        @Override
        public boolean isAborted() {
            return state.get() == ABORTED;
        }
    }
}
