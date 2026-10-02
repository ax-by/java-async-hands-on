package async.m04;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeBlockingService;

/**
 * E5. bulkhead(격벽). 상품 페이지 서버가 호출하는 두 다운스트림의 blocking 클라이언트를 감싼다.
 *
 * <ul>
 *   <li>재고(inventory): 빠르고(50ms) 중요하다. 동시 호출 최대 {@link #INVENTORY_THREADS}개.</li>
 *   <li>추천(recommendation): 자주 느려지고(장애 시 수 초) 덜 중요하다. 동시 호출 최대 {@link #RECOMMENDATION_THREADS}개.</li>
 * </ul>
 *
 * <p>요구사항
 * <ol>
 *   <li>추천 API 가 아무리 느려지고 호출이 쌓여도 재고 호출의 응답시간은 영향을 받지 않아야 한다.</li>
 *   <li>재고 호출은 이름이 {@code "inventory"} 인 풀의 스레드(inventory-1 ...)에서,
 *       추천 호출은 이름이 {@code "recommendation"} 인 풀의 스레드에서 실행한다 ({@code Pools.fixed} 를 쓰면 된다).</li>
 *   <li>각 메서드는 호출 스레드를 막지 않고 즉시 future 를 반환한다.</li>
 *   <li>{@link #close()} 는 만든 풀을 모두 닫는다.</li>
 * </ol>
 */
public final class DownstreamClients implements AutoCloseable {

    public static final int INVENTORY_THREADS = 8;
    public static final int RECOMMENDATION_THREADS = 4;

    private final FakeBlockingService inventory;
    private final FakeBlockingService recommendation;

    public DownstreamClients(FakeBlockingService inventory, FakeBlockingService recommendation) {
        this.inventory = inventory;
        this.recommendation = recommendation;
        // TODO(E5): 다운스트림마다 크기가 정해진 풀을 따로 만든다 (R11). 필요한 필드는 직접 추가하라
        throw new UnsupportedOperationException("TODO");
    }

    public CompletableFuture<String> inventory(String sku) {
        // TODO(E5)
        throw new UnsupportedOperationException("TODO");
    }

    public CompletableFuture<String> recommendation(String userId) {
        // TODO(E5)
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void close() {
        // TODO(E5)
        throw new UnsupportedOperationException("TODO");
    }
}
