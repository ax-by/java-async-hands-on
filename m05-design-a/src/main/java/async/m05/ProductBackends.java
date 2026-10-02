package async.m05;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import async.harness.FakeBlockingService;
import async.harness.ThreadRecorder;

/**
 * 상품 상세 API 가 호출하는 네 개의 다운스트림. 모두 <b>blocking</b> 클라이언트다(서블릿 기반 Java 17 서비스).
 *
 * <table>
 *   <tr><th>소스</th><th>클라이언트</th><th>평소 지연</th><th>필수?</th><th>실패 시</th></tr>
 *   <tr><td>product</td><td>JDBC</td><td>80ms</td><td>필수</td><td>요청 전체 실패</td></tr>
 *   <tr><td>price</td><td>RestTemplate</td><td>120ms</td><td>필수</td><td>요청 전체 실패</td></tr>
 *   <tr><td>stock</td><td>RestTemplate</td><td>100ms</td><td>선택</td><td>"UNKNOWN"</td></tr>
 *   <tr><td>reviews</td><td>JDBC</td><td>150ms</td><td>선택</td><td>빈 목록</td></tr>
 * </table>
 *
 * 모든 메서드는 호출 스레드를 지연시간만큼 막는다. 테스트는 {@link #productDb()} 등으로 지연과 실패를 조작하고,
 * {@link #maxTotalConcurrency()} 로 네 호출이 실제로 동시에 진행됐는지 확인한다. <b>이 파일은 수정하지 않는다.</b>
 */
public final class ProductBackends {

    public static final long PRODUCT_MS = 80;
    public static final long PRICE_MS = 120;
    public static final long STOCK_MS = 100;
    public static final long REVIEWS_MS = 150;

    private final FakeBlockingService productDb = new FakeBlockingService("product-db", PRODUCT_MS);
    private final FakeBlockingService priceApi = new FakeBlockingService("price-api", PRICE_MS);
    private final FakeBlockingService stockApi = new FakeBlockingService("stock-api", STOCK_MS);
    private final FakeBlockingService reviewDb = new FakeBlockingService("review-db", REVIEWS_MS);

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile ThreadRecorder recorder;

    /** (측정 장치) 각 호출이 실행된 스레드를 rec 에 "product", "price", "stock", "reviews" 로 기록한다. */
    public ProductBackends recordTo(ThreadRecorder rec) {
        this.recorder = rec;
        return this;
    }

    /** 필수. JDBC. */
    public String product(String productId) {
        return track("product", () -> productDb.call(productId));
    }

    /** 필수. RestTemplate. */
    public String price(String productId) {
        return track("price", () -> priceApi.call(productId));
    }

    /** 선택. RestTemplate. */
    public String stock(String productId) {
        return track("stock", () -> stockApi.call(productId));
    }

    /** 선택. JDBC. */
    public List<String> reviews(String productId) {
        String raw = track("reviews", () -> reviewDb.call(productId));
        return List.of(raw + "#1", raw + "#2");
    }

    private <T> T track(String source, Supplier<T> call) {
        ThreadRecorder rec = recorder;
        if (rec != null) {
            rec.mark(source);
        }
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            return call.get();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 네 소스를 합쳐 동시에 진행된 호출의 최대 개수. */
    public int maxTotalConcurrency() {
        return maxInFlight.get();
    }

    /** 네 소스를 합쳐 지금 진행 중인 호출 수. */
    public int inFlight() {
        return inFlight.get();
    }

    public FakeBlockingService productDb() {
        return productDb;
    }

    public FakeBlockingService priceApi() {
        return priceApi;
    }

    public FakeBlockingService stockApi() {
        return stockApi;
    }

    public FakeBlockingService reviewDb() {
        return reviewDb;
    }
}
