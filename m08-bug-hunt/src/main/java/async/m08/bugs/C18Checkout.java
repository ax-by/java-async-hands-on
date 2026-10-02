package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;

/**
 * C18. 주문 결제 확정. {@code taskExecutor} 는 애플리케이션 공용 bounded 풀이다(Spring 의 기본 {@code taskExecutor} 빈).
 *
 * <p>작성자 메모: "재고 예약 결과가 있어야 다음 단계를 진행할 수 있어서 기다린다. 이 부분은 taskExecutor 스레드에서 돌기 때문에
 * 요청 스레드나 이벤트 루프를 막지 않는다. QA 환경에서 수백 번 테스트했고 문제없었다."
 *
 * <p><b>관측 조건</b>: taskExecutor 스레드 4개(큐 무제한). orderDb 50ms, inventoryDb 50ms.
 * 동시 요청 1건(QA)과 8건(운영 피크)으로 각각 실행하고, 최대 1.5초 기다린다.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
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
        return CompletableFuture.supplyAsync(() -> orderDb.call(orderId), taskExecutor)
                .thenApplyAsync(order -> {
                    String reservation = reserveStock(order).join();
                    return order + " + " + reservation;
                }, taskExecutor);
    }

    /** 재고 예약. 다른 서비스에서도 쓰는 공용 메서드라 future 를 반환한다. */
    public CompletableFuture<String> reserveStock(String order) {
        return CompletableFuture.supplyAsync(() -> inventoryDb.call(order), taskExecutor);
    }
}
