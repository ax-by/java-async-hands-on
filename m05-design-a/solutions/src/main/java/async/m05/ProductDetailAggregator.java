package async.m05;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 상품 상세 집계기 (설계 A: 병렬 blocking fan-out). 코드를 쓰기 전에 DECISION.md 를 먼저 채운다.
 *
 * <p>사용하는 쪽(Spring MVC 컨트롤러)은 이렇게 쓴다고 가정한다.
 * <pre>{@code
 * @GetMapping("/products/{id}")
 * CompletableFuture<ProductDetail> detail(@PathVariable String id) {
 *     return aggregator.load(id);     // Spring MVC 가 future 를 받아 비동기 응답으로 처리한다
 * }
 * }</pre>
 * 그래서 {@code load} 는 요청 스레드를 막지 않고 즉시 future 를 반환해야 한다.
 *
 * <h2>요구사항 (E1)</h2>
 * <ol>
 *   <li>네 소스(product, price, stock, reviews)를 <b>생성자로 받은 io 풀에서 동시에</b> 호출한다.
 *       commonPool 이나 호출 스레드에서 다운스트림을 호출하면 안 된다. 총 소요시간 ≈ 가장 느린 호출(R7).</li>
 *   <li>선택 소스가 실패하면 폴백한다: stock → {@link ProductDetail#UNKNOWN_STOCK}, reviews → 빈 목록.</li>
 *   <li>필수 소스가 실패하면 future 가 {@link ProductDetailException} 으로 실패한다.
 *       reason 은 PRODUCT_UNAVAILABLE / PRICE_UNAVAILABLE, cause 는 다운스트림이 던진 <b>원래 예외</b>(unwrap, R8).</li>
 *   <li>전체 타임아웃 {@link #OVERALL_TIMEOUT_MS}ms. 넘으면 reason TIMEOUT, cause 는 {@link TimeoutException}.</li>
 *   <li>어떤 스레드도 "기다리기만" 하며 점유되면 안 된다(io 스레드는 다운스트림 호출 중일 때만 점유).</li>
 * </ol>
 */
public final class ProductDetailAggregator {

    public static final long OVERALL_TIMEOUT_MS = 300;
    public static final long OPTIONAL_TIMEOUT_MS = 200;

    private final ProductBackends backends;
    private final Executor io;

    public ProductDetailAggregator(ProductBackends backends, Executor io) {
        this.backends = backends;
        this.io = io;
    }

    /** E1. 위 요구사항 1~5 를 만족하는 집계. */
    public CompletableFuture<ProductDetail> load(String productId) {
        // TODO(E1): 넷을 먼저 모두 시작(R3, R7) → 소스별 실패 정책(R9) → allOf 로 모으기(R5, R13) → 전체 타임아웃(R10) → unwrap 후 도메인 예외(R8)
        // SOLUTION-BEGIN
        CompletableFuture<String> product = required(
                CompletableFuture.supplyAsync(() -> backends.product(productId), io),
                ProductDetailException.Reason.PRODUCT_UNAVAILABLE);
        CompletableFuture<String> price = required(
                CompletableFuture.supplyAsync(() -> backends.price(productId), io),
                ProductDetailException.Reason.PRICE_UNAVAILABLE);
        CompletableFuture<String> stock = CompletableFuture.supplyAsync(() -> backends.stock(productId), io)
                .exceptionally(ex -> ProductDetail.UNKNOWN_STOCK);
        CompletableFuture<List<String>> reviews = CompletableFuture.supplyAsync(() -> backends.reviews(productId), io)
                .exceptionally(ex -> List.of());
        return combine(product, price, stock, reviews);
        // SOLUTION-END
    }

    /**
     * E2. {@link #load} 에 <b>선택 소스별 타임아웃</b>({@link #OPTIONAL_TIMEOUT_MS}ms)을 더한다.
     * 리뷰나 재고가 느려도 요청 전체가 실패하지 않고, 200ms 시점에 폴백 값으로 응답해야 한다.
     * 스레드를 막아서 기다리는 방식(get(timeout) 등)은 안 된다. 필수 소스는 여전히 전체 타임아웃만 적용한다.
     */
    public CompletableFuture<ProductDetail> loadWithSourceTimeouts(String productId) {
        // TODO(E2): 스레드를 막지 않는 타임아웃 API 는 무엇인가? 실패와 타임아웃 모두 같은 폴백으로 (R10, R9)
        // SOLUTION-BEGIN
        CompletableFuture<String> product = required(
                CompletableFuture.supplyAsync(() -> backends.product(productId), io),
                ProductDetailException.Reason.PRODUCT_UNAVAILABLE);
        CompletableFuture<String> price = required(
                CompletableFuture.supplyAsync(() -> backends.price(productId), io),
                ProductDetailException.Reason.PRICE_UNAVAILABLE);
        CompletableFuture<String> stock = CompletableFuture.supplyAsync(() -> backends.stock(productId), io)
                .exceptionally(ex -> ProductDetail.UNKNOWN_STOCK)
                .completeOnTimeout(ProductDetail.UNKNOWN_STOCK, OPTIONAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        CompletableFuture<List<String>> reviews = CompletableFuture.supplyAsync(() -> backends.reviews(productId), io)
                .exceptionally(ex -> List.of())
                .completeOnTimeout(List.of(), OPTIONAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return combine(product, price, stock, reviews);
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    private static <T> CompletableFuture<T> required(CompletableFuture<T> call, ProductDetailException.Reason reason) {
        return call.exceptionally(ex -> {
            throw new ProductDetailException(reason, unwrap(ex));
        });
    }

    private static CompletableFuture<ProductDetail> combine(CompletableFuture<String> product, CompletableFuture<String> price,
                                                            CompletableFuture<String> stock,
                                                            CompletableFuture<List<String>> reviews) {
        return CompletableFuture.allOf(product, price, stock, reviews)
                .thenApply(v -> new ProductDetail(product.join(), price.join(), stock.join(), reviews.join())) // 모두 완료 (R5)
                .orTimeout(OVERALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .handle((detail, ex) -> {
                    if (ex == null) {
                        return detail;
                    }
                    Throwable cause = unwrap(ex);
                    if (cause instanceof ProductDetailException) {
                        throw (ProductDetailException) cause;
                    }
                    if (cause instanceof TimeoutException) {
                        throw new ProductDetailException(ProductDetailException.Reason.TIMEOUT, cause);
                    }
                    throw new CompletionException(cause);
                });
    }

    static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
    // SOLUTION-REMOVE-END
}
