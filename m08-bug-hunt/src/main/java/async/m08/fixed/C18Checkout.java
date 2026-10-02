package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;

/**
 * C18 고친 버전. 요구사항: "주문행 + 예약행" 을 반환한다. 동시 요청이 풀 크기의 몇 배여도 모두 완료되어야 한다.
 * {@link #reserveStock(String)} 은 그대로 둔다(다른 서비스가 쓰는 공용 메서드).
 */
public final class C18Checkout {

    private final FakeBlockingService orderDb;
    private final FakeBlockingService inventoryDb;
    private final Executor taskExecutor;

    public C18Checkout(FakeBlockingService orderDb, FakeBlockingService inventoryDb, Executor taskExecutor) {
        this.orderDb = orderDb;
        this.inventoryDb = inventoryDb;
        this.taskExecutor = taskExecutor;
    }

    public CompletableFuture<String> checkout(String orderId) {
        // TODO(C18): join 하는 스레드와 그것이 기다리는 작업은 각각 어느 풀에 있는가? 동시 요청이 풀 크기보다 많으면? (R11, R13)
        throw new UnsupportedOperationException("TODO");
    }

    /** 재고 예약. 다른 서비스에서도 쓰는 공용 메서드라 future 를 반환한다. (수정하지 않는다) */
    public CompletableFuture<String> reserveStock(String order) {
        return CompletableFuture.supplyAsync(() -> inventoryDb.call(order), taskExecutor);
    }
}
