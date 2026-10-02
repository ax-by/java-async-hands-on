package async.m04;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

import async.harness.Pools;
import async.harness.Pools.NamedPool;

/**
 * m04 구현 실습 E1~E3. (E4 는 {@link OrderAssembler}, E5 는 {@link DownstreamClients})
 * 채점: ExercisesTest
 */
public final class Exercises {

    private Exercises() {
    }

    // ------------------------------------------------------------------------------------------------
    // E1. 숫자로 풀 크기 정하기 (R11, Little 의 법칙)
    // ------------------------------------------------------------------------------------------------

    /**
     * 풀 크기를 정하는 데 필요한 입력.
     *
     * @param peakRps              피크 시 초당 요청 수
     * @param callsPerRequest      요청 하나가 이 풀에 제출하는 blocking 호출 수
     * @param blockingMillisPerCall 호출 하나가 스레드를 점유하는 시간 (ms)
     * @param headroomPercent      여유율 (%). 100 이면 여유 없음, 150 이면 1.5배
     * @param downstreamLimit      다운스트림이 동시에 받아 줄 수 있는 최대 호출 수 (예: DB 커넥션 풀 크기)
     * @param maxQueueWaitMillis   큐에서 기다려도 되는 최대 시간 (ms). 이보다 오래 기다릴 작업은 받지 말고 거절해야 한다
     */
    public record SizingInput(int peakRps, int callsPerRequest, int blockingMillisPerCall, int headroomPercent,
                              int downstreamLimit, int maxQueueWaitMillis) {
    }

    /**
     * @param threads             풀 스레드 수
     * @param queueCapacity       큐 용량
     * @param limitedByDownstream 계산한 스레드 수가 다운스트림 한도에 막혀 잘렸는가
     */
    public record PoolPlan(int threads, int queueCapacity, boolean limitedByDownstream) {
    }

    /**
     * E1. 다음 규칙대로 풀 계획을 계산하라. <b>정수 연산</b>으로 계산한다(부동소수점 오차로 1이 늘어나지 않게).
     * <ol>
     *   <li>필요 동시성(Little) = peakRps × callsPerRequest × blockingMillisPerCall / 1000</li>
     *   <li>threads = ceil(필요 동시성 × headroomPercent / 100)</li>
     *   <li>threads 가 downstreamLimit 보다 크면 downstreamLimit 으로 자르고 limitedByDownstream = true.
     *       (스레드를 더 늘려 봐야 다운스트림 커넥션을 기다리며 막힐 뿐이다)</li>
     *   <li>queueCapacity = floor(threads × maxQueueWaitMillis / blockingMillisPerCall).
     *       풀이 1ms 에 처리하는 작업 수가 threads / blockingMillisPerCall 이므로, maxQueueWaitMillis 동안 처리할 수 있는 만큼만 줄을 세운다.</li>
     * </ol>
     * 예: 200 RPS × 3회 × 50ms = 30 → 여유 100% 면 threads 30, 대기 100ms 면 queue 60.
     */
    public static PoolPlan sizePool(SizingInput in) {
        // TODO(E1): 곱셈을 먼저 하고 나눗셈은 마지막에 한 번만. 올림은 (a + b - 1) / b (R11)
        // SOLUTION-BEGIN
        long numerator = (long) in.peakRps() * in.callsPerRequest() * in.blockingMillisPerCall() * in.headroomPercent();
        long denominator = 1000L * 100L;
        int threads = (int) ((numerator + denominator - 1) / denominator);
        boolean limited = false;
        if (threads > in.downstreamLimit()) {
            threads = in.downstreamLimit();
            limited = true;
        }
        int queue = (int) ((long) threads * in.maxQueueWaitMillis() / in.blockingMillisPerCall());
        return new PoolPlan(threads, queue, limited);
        // SOLUTION-END
    }

    // ------------------------------------------------------------------------------------------------
    // E2. 거절을 "실패한 future"로 바꾸는 안전한 제출 (R3 예외 2)
    // ------------------------------------------------------------------------------------------------

    /**
     * E2. {@code supplyAsync(work, executor)} 와 같지만, executor 가 작업을 거절하면 예외를 <b>던지지 않고</b>
     * {@link RejectedExecutionException} 으로 실패한 future 를 반환하라.
     *
     * <p>왜 필요한가: 호출자는 "future 를 반환하는 메서드는 실패도 future 로 알려 준다"고 기대하고 {@code exceptionally} 로 폴백을 단다.
     * 그런데 AbortPolicy 는 {@code supplyAsync} 호출 자체에서 예외를 던지므로 그 폴백을 건너뛰고 호출자의 스택으로 튀어 나간다.
     */
    public static <T> CompletableFuture<T> supplySafely(Supplier<T> work, Executor executor) {
        // TODO(E2): 거절은 동기적으로 던져진다(R3 예외 2). 그 예외를 어디서 잡아야 할까? Java 9+ 의 failedFuture 를 써도 된다
        // SOLUTION-BEGIN
        try {
            return CompletableFuture.supplyAsync(work, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
        // SOLUTION-END
    }

    // ------------------------------------------------------------------------------------------------
    // E3. 요구사항에 맞는 큐와 거절 정책 고르기 (R3, R11)
    // ------------------------------------------------------------------------------------------------

    /**
     * E3. 결제 승인 API 호출 전용 풀을 만들어라. 풀 이름은 {@code "payment"} 다 ({@link Pools} 를 써도 된다).
     *
     * <p>요구사항
     * <ul>
     *   <li>결제사 계약상 동시 호출은 최대 8개다. 호출 하나는 약 100ms 걸린다.</li>
     *   <li>큐에서 최대 200ms 까지는 기다려도 된다(E1 의 규칙으로 큐 용량을 계산하라). 그 이상 밀리면 기다리게 하지 말고 즉시 실패시켜
     *       컨트롤러가 503 을 돌려줘야 한다.</li>
     *   <li>Tomcat 요청 스레드가 결제 호출을 <b>직접 실행해서는 안 된다</b>(요청 스레드가 고갈되면 결제와 무관한 API 까지 멈춘다).</li>
     *   <li><b>조용히 버려지는 요청이 0건</b>이어야 한다(응답을 영원히 기다리는 요청이 없어야 한다).</li>
     * </ul>
     * 어떤 큐(무제한/제한, 용량)와 어떤 거절 정책(Abort / CallerRuns / Discard / DiscardOldest)이 이 요구를 모두 만족하는가?
     * 선택 이유는 PREDICT.md 서술 문제 4번에 적는다.
     */
    public static NamedPool paymentPool() {
        // TODO(E3): 각 거절 정책이 S02 에서 어떤 Fate 를 만들었는지 떠올려라 (R3, R11)
        // SOLUTION-BEGIN
        return Pools.bounded("payment", 8, 16, new ThreadPoolExecutor.AbortPolicy());
        // SOLUTION-END
    }
}
