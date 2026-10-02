package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.m08.support.Log;

/**
 * C04 고친 버전. 요구사항: 주문 저장 후 "receipt(행)" 으로 응답한다. 포인트 적립은 응답을 기다리게 하지 않는다(best-effort).
 * 적립이 실패해도 주문은 성공이지만, <b>그 예외를 담은 ERROR 로그</b>를 반드시 남긴다. 주문 저장 실패도 ERROR 로그를 남기고 전파한다.
 */
public final class C04OrderPlacement {

    private final FakeBlockingService orderDb;
    private final FakeBlockingService loyaltyDb;
    private final Executor io;

    public C04OrderPlacement(FakeBlockingService orderDb, FakeBlockingService loyaltyDb, Executor io) {
        this.orderDb = orderDb;
        this.loyaltyDb = loyaltyDb;
        this.io = io;
    }

    public CompletableFuture<String> place(String orderId) {
        // TODO(C04): 반환값을 버리는 future 가 있는가? 그 분기의 실패는 누가 관찰하는가? (R6)
        // SOLUTION-BEGIN
        CompletableFuture<String> saved = CompletableFuture.supplyAsync(() -> orderDb.call(orderId), io);

        saved.thenAcceptAsync(row -> loyaltyDb.call(orderId), io)
                .exceptionally(ex -> {                          // 이 분기의 끝: 아무도 기다리지 않으므로 여기서 관찰한다
                    Log.error("포인트 적립 실패(보정 필요): " + orderId, ex);
                    return null;
                });

        return saved
                .thenApply(row -> "receipt(" + row + ")")
                .whenComplete((receipt, ex) -> {
                    if (ex != null) {
                        Log.error("주문 처리 실패: " + orderId, ex);
                    }
                });
        // SOLUTION-END
    }
}
