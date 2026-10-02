package async.m04;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;

/**
 * E4. 주문 상세 조립기. 주문 1건(orderDb)을 조회한 뒤, 그 주문의 상품 {@link #ITEMS_PER_ORDER}개(itemDb)를 병렬로 조회한다.
 * 두 조회 모두 blocking 이고, 모두 같은 {@code io} 풀에서 실행해야 한다(이 서비스는 풀을 하나만 받는다).
 */
public final class OrderAssembler {

    public static final int ITEMS_PER_ORDER = 3;

    public record OrderView(String order, List<String> items) {
    }

    private final FakeBlockingService orderDb;
    private final FakeBlockingService itemDb;
    private final Executor io;

    public OrderAssembler(FakeBlockingService orderDb, FakeBlockingService itemDb, Executor io) {
        this.orderDb = orderDb;
        this.itemDb = itemDb;
        this.io = io;
    }

    /**
     * 운영 중인 코드. 평소에는 잘 동작하지만, 동시 요청이 몰리면 <b>모든 요청이 영원히 멈출 수 있다</b>.
     * 어떤 조건에서 멈추는지는 S06 에서 예측한다. 이 메서드는 수정하지 않는다(비교용).
     */
    public CompletableFuture<OrderView> loadJoining(String orderId) {
        return CompletableFuture.supplyAsync(() -> {
            String order = orderDb.call(orderId);
            List<CompletableFuture<String>> items = new ArrayList<>();
            for (int i = 1; i <= ITEMS_PER_ORDER; i++) {
                String key = orderId + "-item" + i;
                items.add(CompletableFuture.supplyAsync(() -> itemDb.call(key), io));
            }
            List<String> loaded = new ArrayList<>();
            for (CompletableFuture<String> item : items) {
                loaded.add(item.join());                       // io 스레드 위에서 io 풀의 작업을 기다린다
            }
            return new OrderView(order, loaded);
        }, io);
    }

    /**
     * E4. {@link #loadJoining} 과 같은 결과를 내되, <b>풀 크기와 동시 요청 수에 상관없이 절대 멈추지 않게</b> 고쳐라.
     * 풀은 그대로 하나({@code io})만 쓴다. 상품 조회는 여전히 병렬이어야 한다.
     * 상품 순서는 item1, item2, item3 순서를 유지한다.
     */
    public CompletableFuture<OrderView> load(String orderId) {
        // TODO(E4): "기다리기(join)" 대신 "이어 붙이기(compose)". 풀 스레드는 blocking 호출 중일 때만 점유되어야 한다 (R11, R13, R5)
        throw new UnsupportedOperationException("TODO");
    }
}
