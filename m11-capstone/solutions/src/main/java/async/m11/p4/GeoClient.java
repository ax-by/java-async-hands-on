package async.m11.p4;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
// SOLUTION-REMOVE-BEGIN (모범 답안에서만 쓰는 import: 설계 힌트가 되지 않도록 main 에서 제거)
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import async.harness.Pools;
// SOLUTION-REMOVE-END

/**
 * P4. 오픈소스 라이브러리 "geo-lookup" 의 공개 클라이언트. 요구사항은 {@code p4-library-api/PROBLEM.md},
 * 공개 계약(스레드·타임아웃·취소·종료)의 결정은 {@code DECISION.md} 에 먼저 쓴다.
 *
 * <p>TODO(P4): 이 클래스와 {@link #lookup(String)} 의 javadoc 에 공개 계약을 문서화하라
 * (콜백이 실행되는 스레드, 타임아웃 시 예외, 취소의 의미, close 후 동작). 사용자는 javadoc 만 읽고 쓴다.
 *
 * <p>공개 시그니처는 바꾸지 않는다. 필드·헬퍼는 자유롭게 추가한다.
 */
public final class GeoClient implements AutoCloseable {

    private final Transport transport;
    private final Executor userCallbackExecutor;   // 사용자가 지정한 executor (없으면 null)
    private final Duration requestTimeout;

    // SOLUTION-REMOVE-BEGIN
    /** 라이브러리가 소유한 기본 콜백 풀 (사용자가 지정하지 않았을 때만). close() 에서 정리한다. */
    private final ExecutorService ownedCallbackPool;
    private final Executor callbackExecutor;
    /** 타임아웃 전용 타이머. JDK 의 공용 지연 스케줄러(commonPool 소속)를 쓰지 않는다. 타이머 스레드는 신호만 보낸다. */
    private final ScheduledThreadPoolExecutor timer;
    /** 진행 중인 조회: 사용자에게 준 future → 결과를 확정하는 내부 future. */
    private final Map<CompletableFuture<GeoResult>, CompletableFuture<GeoResult>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;
    // SOLUTION-REMOVE-END

    private GeoClient(Builder builder) {
        this.transport = builder.transport;
        this.userCallbackExecutor = builder.callbackExecutor;
        this.requestTimeout = builder.requestTimeout;
        // TODO(P4): 라이브러리가 소유할 자원을 만든다 (R2, R10, R11, 체크리스트 #19)
        // SOLUTION-REMOVE-BEGIN
        this.ownedCallbackPool = userCallbackExecutor == null ? Executors.newFixedThreadPool(2, Pools.namedFactory("geo-callback")) : null;
        this.callbackExecutor = userCallbackExecutor != null ? userCallbackExecutor : ownedCallbackPool;
        this.timer = new ScheduledThreadPoolExecutor(1, Pools.namedFactory("geo-timer"));
        this.timer.setRemoveOnCancelPolicy(true);
        // SOLUTION-REMOVE-END
    }

    public static Builder builder(Transport transport) {
        return new Builder(transport);
    }

    /**
     * IP 의 위치를 조회한다.
     *
     * @param ip 조회할 IP (null·빈 문자열 불가)
     * @return 조회 결과 stage. (계약은 TODO(P4): 문서화)
     */
    public CompletionStage<GeoResult> lookup(String ip) {
        // TODO(P4): DECISION.md 를 먼저 채운 뒤 구현한다
        // SOLUTION-BEGIN
        CompletableFuture<GeoResult> result = new CompletableFuture<>();       // 호출마다 새 future: 공유 상태를 내주지 않는다 (#17)
        if (closed) {
            result.completeExceptionally(new IllegalStateException("GeoClient is closed"));
            return result;
        }
        if (ip == null || ip.isBlank()) {
            result.completeExceptionally(new IllegalArgumentException("ip must not be blank"));  // 던지지 않고 실패한 stage
            return result;
        }
        CompletableFuture<String> request;
        try {
            request = transport.send("/geo/" + ip);
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
            return result;
        }
        // 결과를 정하는 사건(응답·타임아웃·close)이 여럿이라 서로 경쟁한다. 내부 future 하나로 "먼저 온 것"을 동기적으로 확정한 뒤
        // 사용자 future 로 전달한다. (그러지 않으면 타임아웃 → request.cancel → CancellationException 전달이 타임아웃 전달을 앞지를 수 있다)
        CompletableFuture<GeoResult> decided = new CompletableFuture<>();
        pending.put(result, decided);
        ScheduledFuture<?> timeout = timer.schedule(() -> {                    // [geo-timer] 신호만 보낸다
            decided.completeExceptionally(new TimeoutException(
                    "geo lookup timed out after " + requestTimeout.toMillis() + "ms: " + ip));
            request.cancel(true);                                              // 전송 자원 반환 (R10: 상류로 직접 전파)
        }, requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        request.whenComplete((body, ex) -> {                                   // [transport I/O 스레드] 확정만 하고 넘긴다
            if (ex == null) {
                decided.complete(new GeoResult(ip, body));
            } else {
                decided.completeExceptionally(unwrap(ex));
            }
        });
        decided.whenComplete((v, ex) -> deliver(result, v, ex));              // [확정한 스레드] → callbackExecutor 로 전달
        result.whenComplete((v, ex) -> {                                       // 어떤 이유로 끝나든(성공·타임아웃·사용자 cancel·close) 정리
            pending.remove(result);
            timeout.cancel(false);
            request.cancel(true);                                              // 이미 끝난 요청이면 아무 일도 없다. 아니면 전송 자원 반환
        });
        return result;
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    /** 결과 전달은 항상 콜백 executor 에서 한다 → 사용자의 non-async 콜백은 그 스레드에서 실행된다 (R1). */
    private void deliver(CompletableFuture<GeoResult> result, GeoResult value, Throwable error) {
        if (result.isDone()) {
            return;
        }
        Runnable complete = () -> {
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(unwrap(error));
            }
        };
        try {
            callbackExecutor.execute(complete);
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("callback executor rejected the completion", e));
        }
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
    // SOLUTION-REMOVE-END

    /**
     * 클라이언트를 닫는다. (계약은 TODO(P4): 진행 중인 조회는? 이후 호출은? 라이브러리 스레드는?)
     */
    @Override
    public void close() {
        // SOLUTION-BEGIN
        closed = true;
        for (CompletableFuture<GeoResult> decided : pending.values()) {
            decided.completeExceptionally(new IllegalStateException("GeoClient closed while lookup was in flight"));
        }
        timer.shutdownNow();
        if (ownedCallbackPool != null) {
            ownedCallbackPool.shutdown();                                      // 이미 맡은 전달(위의 실패 통지)은 끝내고 종료
        }
        // SOLUTION-END
    }

    /** 빌더. */
    public static final class Builder {

        private final Transport transport;
        private Executor callbackExecutor;
        private Duration requestTimeout = Duration.ofSeconds(1);

        private Builder(Transport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
        }

        /** 결과(성공·실패·타임아웃)를 전달할 executor. 지정하지 않으면 라이브러리가 소유한 작은 풀을 쓴다. */
        public Builder callbackExecutor(Executor executor) {
            this.callbackExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** 조회 하나의 최대 대기 시간. 기본 1초. */
        public Builder requestTimeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.requestTimeout = timeout;
            return this;
        }

        public GeoClient build() {
            return new GeoClient(this);
        }
    }
}
