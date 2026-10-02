package async.m07vt;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.TimeUnit;

import async.harness.FakeBlockingService;

/**
 * m07-vt 구현 실습. 같은 "상품 상세" A 설계를 Java 21+/25 스타일로 쓴다. 채점: ProductPageTest
 *
 * <p>결과 형식은 항상 {@code "{product 결과}|{price 결과}|{review 결과}"} 다.
 */
public final class ProductPage {

    private ProductPage() {
    }

    /** 요청의 traceId. 호출자가 {@code ScopedValue.where(TRACE, ...)} 로 바인딩한다. */
    public static final ScopedValue<String> TRACE = ScopedValue.newInstance();

    /**
     * X1. 가상 스레드 executor 와 <b>평범한 blocking 코드</b>(submit + Future.get)로 세 호출을 동시에 실행하라.
     * <ul>
     *   <li>총 소요시간 ≈ 가장 느린 호출 (합이 아님)</li>
     *   <li>각 호출은 가상 스레드에서 실행된다</li>
     *   <li>메서드가 끝날 때 executor 도 닫힌다 (try-with-resources)</li>
     *   <li>하나가 실패하면 <b>그 원래 예외</b>(RuntimeException)를 던진다. ExecutionException 으로 감싸서 던지지 않는다.</li>
     * </ul>
     * 생각해 볼 것: 하나가 실패해도 나머지는 계속 돈다. 그리고 close() 는 무엇을 기다리는가?
     */
    public static String loadWithVirtualThreads(FakeBlockingService product, FakeBlockingService price,
            FakeBlockingService review, String key) throws InterruptedException {
        // TODO(X1): Executors.newVirtualThreadPerTaskExecutor(), 시작을 먼저 모두 (R7), ExecutionException 의 cause 를 던진다 (R8)
        // SOLUTION-BEGIN
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> p = vt.submit(() -> product.call(key));
            Future<String> pr = vt.submit(() -> price.call(key));
            Future<String> r = vt.submit(() -> review.call(key));
            try {
                return p.get() + "|" + pr.get() + "|" + r.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException(e.getCause());
            }
        }
        // SOLUTION-END
    }

    /**
     * X2. 같은 일을 {@link StructuredTaskScope} 로 하라. 추가 요구사항:
     * <ul>
     *   <li>하나가 실패하면 <b>즉시</b>(가장 느린 호출을 기다리지 않고) 그 원래 예외(RuntimeException)를 던지고,
     *       나머지 호출은 interrupt 로 취소된다.</li>
     *   <li>전체 타임아웃 {@code timeout}. 넘으면 {@link StructuredTaskScope.TimeoutException} 을 그대로 던지고 진행 중인 호출은 취소된다.</li>
     * </ul>
     */
    public static String loadStructured(FakeBlockingService product, FakeBlockingService price,
            FakeBlockingService review, String key, Duration timeout) throws InterruptedException {
        // TODO(X2): StructuredTaskScope.open(joiner, cfg -> cfg.withTimeout(...)), FailedException 의 cause (VT2)
        // SOLUTION-BEGIN
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>awaitAllSuccessfulOrThrow(),
                cfg -> cfg.withTimeout(timeout))) {
            Subtask<String> p = scope.fork(() -> product.call(key));
            Subtask<String> pr = scope.fork(() -> price.call(key));
            Subtask<String> r = scope.fork(() -> review.call(key));
            try {
                scope.join();
            } catch (StructuredTaskScope.FailedException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw e;
            }
            return p.get() + "|" + pr.get() + "|" + r.get();
        }
        // SOLUTION-END
    }

    /** X3 의 외부 API. 구현체는 호출된 스레드에서 {@link #TRACE} 를 읽어 로그를 남긴다(MDC 대신 ScopedValue). */
    public interface TracedApi {
        String call(String key);
    }

    /**
     * X3. 요청 하나를 처리한다: {@link #TRACE} 를 traceId 로 바인딩한 범위 안에서 세 API 를 <b>동시에</b> 호출하고
     * {@code "{product}|{price}|{review}"} 를 반환한다.
     * <ul>
     *   <li>각 API 는 <b>자기가 실행되는 스레드에서</b> TRACE 를 읽는다. 세 호출 모두 traceId 를 봐야 한다.</li>
     *   <li>ThreadLocal/MDC 를 쓰지 않는다. ScopedValue 는 어떤 동시성 도구로 넘긴 스레드에 전달되는가? (V06)</li>
     *   <li>메서드가 끝나면 호출 스레드에서 TRACE 는 바인딩되지 않은 상태여야 한다.</li>
     * </ul>
     */
    public static String handleRequest(String traceId, TracedApi product, TracedApi price, TracedApi review, String key)
            throws Exception {
        // TODO(X3): ScopedValue.where(TRACE, traceId).call(() -> { StructuredTaskScope ... }) (VT4)
        // SOLUTION-BEGIN
        return ScopedValue.where(TRACE, traceId).call(() -> {
            try (var scope = StructuredTaskScope.open()) {
                Subtask<String> p = scope.fork(() -> product.call(key));
                Subtask<String> pr = scope.fork(() -> price.call(key));
                Subtask<String> r = scope.fork(() -> review.call(key));
                scope.join();
                return p.get() + "|" + pr.get() + "|" + r.get();
            }
        });
        // SOLUTION-END
    }

    /** (참고용, 채점 안 함) X1 의 Java 17 버전: 같은 일을 CF 로. 비교해서 읽어 보라. */
    public static String loadWithCompletableFuture(FakeBlockingService product, FakeBlockingService price,
            FakeBlockingService review, String key, ExecutorService io) {
        var p = java.util.concurrent.CompletableFuture.supplyAsync(() -> product.call(key), io);
        var pr = java.util.concurrent.CompletableFuture.supplyAsync(() -> price.call(key), io);
        var r = java.util.concurrent.CompletableFuture.supplyAsync(() -> review.call(key), io);
        return java.util.concurrent.CompletableFuture.allOf(p, pr, r)
                .thenApply(v -> p.join() + "|" + pr.join() + "|" + r.join())
                .orTimeout(1, TimeUnit.SECONDS)
                .join();
    }
}
