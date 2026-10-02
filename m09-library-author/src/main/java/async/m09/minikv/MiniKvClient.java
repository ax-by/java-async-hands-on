package async.m09.minikv;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import async.m09.util.Timeouts;

/**
 * minikv 비동기 key-value 클라이언트. Java 8 이상에서 동작한다.
 *
 * <h2>스레드 정책 (사용자가 반드시 알아야 하는 것, 체크리스트 #18)</h2>
 * <ul>
 *   <li>{@code getAsync}/{@code getShared} 는 요청을 보내고 <b>즉시</b> 반환한다. 호출 스레드를 막지 않는다.</li>
 *   <li>반환된 stage 를 완료시키는 스레드:
 *     <ul>
 *       <li>{@link Builder#completionExecutor(Executor)} 를 <b>지정하지 않으면</b>: 응답이면 minikv I/O 스레드
 *           ({@value FakeTransport#IO_THREAD_PREFIX}-N), 타임아웃이면 타이머 스레드. 따라서 응답 전에 붙인
 *           non-async 콜백도 그 스레드에서 실행된다(R1). <b>콜백에서 blocking 하지 마라</b>: I/O 스레드가 멈추면
 *           이 클라이언트의 모든 응답이 늦어진다. blocking 이 필요하면 {@code thenApplyAsync(fn, 당신의 executor)} 를 써라.</li>
 *       <li>지정하면: 응답과 타임아웃 모두 그 executor 로 넘겨서 완료시킨다(스레드 전환 1회 비용).
 *           그 executor 가 작업을 거절하면(종료 등) 결과를 잃지 않도록 완료를 일으킨 스레드에서 바로 완료시킨다.</li>
 *       <li>이미 완료된 stage 에 붙인 non-async 콜백은 <b>붙인 스레드</b>에서 즉시 실행된다(R1, 라이브러리가 바꿀 수 없다).</li>
 *     </ul>
 *   </li>
 *   <li>이 라이브러리는 내부에서 {@code ForkJoinPool.commonPool()} 을 쓰지 않는다(#19).</li>
 * </ul>
 *
 * <h2>예외 정책</h2> {@link Errors} 참고. 실패 원인은 감싸지 않은 {@link MiniKvException} 계열이다.
 *
 * <h2>취소</h2> 반환된 stage 를 {@code toCompletableFuture().cancel(..)} 하면 <b>아직 응답 전인 요청을 실제로 취소</b>한다
 * ({@code getShared} 는 다른 호출자와 공유하는 요청이므로 취소해도 요청은 계속된다. 내 stage 만 취소된다).
 */
public final class MiniKvClient implements AutoCloseable {

    public static final String TIMER_THREAD_PREFIX = "minikv-timer";

    private final FakeTransport transport;
    /** null 이면 완료를 일으킨 스레드(I/O 또는 타이머)에서 직접 완료시킨다. */
    private final Executor completionExecutor;
    private final ScheduledExecutorService timer;
    private final boolean ownsTimer;
    private volatile Thread ownedTimerThread;
    private final long timeoutMillis;
    /** getShared 용: 키별로 진행 중인 요청 하나. */
    private final ConcurrentMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    private MiniKvClient(Builder b) {
        this.transport = b.transport;
        this.completionExecutor = b.completionExecutor;
        this.timeoutMillis = b.timeoutMillis;
        if (b.timer != null) {
            this.timer = b.timer;
            this.ownsTimer = false;
        } else {
            final AtomicInteger seq = new AtomicInteger();
            ScheduledThreadPoolExecutor owned = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, TIMER_THREAD_PREFIX + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                ownedTimerThread = t;
                return t;
            });
            owned.setRemoveOnCancelPolicy(true); // 취소된 타이머가 큐에 남지 않게 (L5)
            this.timer = owned;
            this.ownsTimer = true;
        }
    }

    public static Builder builder(FakeTransport transport) {
        return new Builder(transport);
    }

    /** 요청 하나: 내부 future(raw)와 취소 손잡이. raw 는 절대 사용자에게 그대로 내보내지 않는다. */
    static final class Call {
        final CompletableFuture<String> raw;
        final FakeTransport.Request request;

        Call(CompletableFuture<String> raw, FakeTransport.Request request) {
            this.raw = raw;
            this.request = request;
        }
    }

    /**
     * (주어진 코드) 요청을 보내고 내부 future 를 만든다.
     * raw 는 I/O 스레드(응답) 또는 타이머 스레드(타임아웃)가 완료시킨다. 타임아웃이면 하부 요청도 취소한다.
     */
    Call start(String key) {
        final CompletableFuture<String> raw = new CompletableFuture<>();
        FakeTransport.Request request = transport.send(key, (value, error) -> {
            if (error != null) {
                raw.completeExceptionally(error);
            } else {
                raw.complete(value);
            }
        });
        Timeouts.failAfter(raw, timeoutMillis, TimeUnit.MILLISECONDS, timer, request::abort);
        return new Call(raw, request);
    }

    /**
     * E4. 내부 future 의 결과를 사용자에게 줄 <b>새 future</b> 로 전달한다(완료 스레드 정책 + 예외 정책 + 보호).
     *
     * <ol>
     *   <li>호출마다 새 future 를 만들어 반환한다(사용자가 그것을 complete/cancel 해도 raw 는 무사하다).</li>
     *   <li>실패는 {@link Errors#toPublic} 으로 바꿔서 <b>직접</b> completeExceptionally 한다(감싸지 않음).</li>
     *   <li>{@code completionExecutor == null} 이면 raw 를 완료시킨 스레드에서 바로 완료시킨다.
     *       아니면 완료 작업을 {@code completionExecutor} 로 넘긴다. raw 가 이미 완료된 상태여도 넘긴다.</li>
     *   <li>{@code completionExecutor} 가 거절하면({@link RejectedExecutionException}) 그 자리에서 완료시킨다.
     *       결과를 잃어 사용자가 영원히 기다리게 하면 안 된다.</li>
     * </ol>
     */
    <T> CompletableFuture<T> deliver(CompletableFuture<T> raw) {
        // TODO(E4): raw.whenComplete 안에서 "완료 작업(Runnable)"을 만들고, 정책에 따라 바로 실행하거나 executor 로 넘긴다 (L2, R1)
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * E5. 비동기 조회.
     *
     * <ol>
     *   <li>즉시 반환한다(가짜 async 금지: 응답을 기다린 뒤 completedFuture 로 감싸면 안 된다).</li>
     *   <li>{@link #start} 로 요청을 보내고 {@link #deliver} 로 사용자용 future 를 만든다.</li>
     *   <li><b>취소 전파</b>: 사용자가 반환된 stage 를 취소하면 하부 요청을 {@code abort()} 하고, raw 도 취소해서
     *       타이머가 정리되게 한다. CF 의 cancel 은 상류로 전파되지 않으므로(R10) 라이브러리가 직접 연결해야 한다.</li>
     * </ol>
     *
     * @return 사용자 전용 stage. 스레드 정책과 예외 정책은 클래스 Javadoc 참고
     */
    public CompletionStage<String> getAsync(String key) {
        // TODO(E5): out.whenComplete 에서 "취소로 끝났는지"를 확인하고 하부 요청을 abort 한다 (L5, R10)
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * E6. 같은 키의 동시 조회를 요청 하나로 합친다(single-flight, 캐시 stampede 방지).
     *
     * <ol>
     *   <li>같은 키로 진행 중인 요청이 있으면 새 요청을 보내지 않는다. 동시에 N 번 호출해도 transport 요청은 1개.</li>
     *   <li>요청이 끝나면(<b>실패와 타임아웃 포함</b>) 맵에서 제거한다. 실패가 영원히 캐시되면 안 된다.
     *       제거는 공유 future 가 완료되기 <b>전에</b> 일어나게 하라. 그래야 결과를 받은 사용자가 곧바로 다시 호출했을 때
     *       새 요청이 나간다.</li>
     *   <li>호출자마다 <b>독립된 stage</b> 를 준다({@link #deliver}). 한 호출자의 complete/cancel 이 다른 호출자와
     *       공유 요청에 영향을 주면 안 된다(공유 요청은 abort 하지 않는다).</li>
     *   <li>원자성: "확인 후 시작"을 두 단계로 나누면 경쟁 상태에서 요청이 2개 나간다. {@code putIfAbsent} 또는
     *       {@code computeIfAbsent} 를 써라. 단 computeIfAbsent 의 매핑 함수 안에서 같은 맵을 수정하면 안 된다
     *       (Java 9+ 는 IllegalStateException("Recursive update"), Java 8 은 무한 루프가 될 수 있다).</li>
     * </ol>
     */
    public CompletionStage<String> getShared(String key) {
        // TODO(E6): 자리표시 future 를 putIfAbsent 로 먼저 차지한 쪽만 start 한다. 끝나면 remove(key, 그 future) (L7)
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * E7. 동기 조회. 결과를 기다려 반환한다.
     *
     * <ol>
     *   <li>라이브러리 스레드({@link #inLibraryThread()}: I/O 스레드, 라이브러리 소유 타이머)에서 호출되면
     *       기다리지 말고 즉시 {@link IllegalStateException} 을 던진다. 그 스레드가 기다리면 응답(또는 타임아웃)을
     *       전달할 스레드가 없어 deadlock 이 된다(Netty 의 BlockingOperationException 과 같은 방어).</li>
     *   <li>{@link #getAsync} 의 결과가 아니라 {@link #start} 의 raw 를 기다린다. completionExecutor 를 거치는
     *       future 를 기다리면, completionExecutor 스레드 위에서 호출됐을 때 deadlock 이 된다(R11).</li>
     *   <li>실패는 {@link Errors#toPublic} 으로 바꿔 <b>그대로 던진다</b>(ExecutionException 으로 감싸지 않는다).</li>
     *   <li>기다리다 interrupt 되면: interrupt 상태를 복원하고, 하부 요청을 abort 하고, MiniKvException 을 던진다.</li>
     * </ol>
     */
    public String get(String key) {
        // TODO(E7): 스레드 검사 → start(key).raw.get() → ExecutionException/InterruptedException 처리 (L4, R5)
        throw new UnsupportedOperationException("TODO");
    }

    /** 현재 스레드가 이 라이브러리가 소유한 스레드(I/O, 소유 타이머)인가. */
    boolean inLibraryThread() {
        return transport.inIoThread() || (ownsTimer && Thread.currentThread() == ownedTimerThread);
    }

    /** 진단용: getShared 로 진행 중인 키 수. */
    public int inFlightKeyCount() {
        return inFlight.size();
    }

    @Override
    public void close() {
        if (ownsTimer) {
            timer.shutdownNow();
        }
    }

    /** 설정. 기본값과 의미를 모두 문서화한다. */
    public static final class Builder {
        private final FakeTransport transport;
        private Executor completionExecutor;
        private ScheduledExecutorService timer;
        private long timeoutMillis = 1000;

        private Builder(FakeTransport transport) {
            if (transport == null) {
                throw new NullPointerException("transport");
            }
            this.transport = transport;
        }

        /**
         * 반환된 stage 를 완료시킬 executor. 지정하지 않으면 I/O 스레드(또는 타이머 스레드)에서 직접 완료시킨다.
         * 사용자 콜백에서 blocking 하거나 ThreadLocal 컨텍스트가 필요하면 지정하라.
         * {@code CallerRunsPolicy} 같은 "호출 스레드에서 실행" 정책을 가진 executor 는 이 설정을 무력화한다(R3 예외 1).
         */
        public Builder completionExecutor(Executor executor) {
            if (executor == null) {
                throw new NullPointerException("executor");
            }
            this.completionExecutor = executor;
            return this;
        }

        /** 타임아웃 타이머. 지정하지 않으면 데몬 스레드 1개({@value MiniKvClient#TIMER_THREAD_PREFIX}-1)를 소유하고 close() 때 종료한다. */
        public Builder timer(ScheduledExecutorService timer) {
            if (timer == null) {
                throw new NullPointerException("timer");
            }
            this.timer = timer;
            return this;
        }

        /** 요청 타임아웃(기본 1초). 시간 초과 시 {@link MiniKvTimeoutException} 으로 실패하고 하부 요청도 취소된다. */
        public Builder timeout(long timeout, TimeUnit unit) {
            this.timeoutMillis = unit.toMillis(timeout);
            return this;
        }

        public MiniKvClient build() {
            return new MiniKvClient(this);
        }
    }
}
