package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.m08.support.Log;

/**
 * C04. 주문 접수 후 포인트 적립.
 *
 * <p>요구사항: 포인트 적립은 주문 응답을 늦추면 안 된다(best-effort). 적립이 실패해도 주문은 성공이지만,
 * <b>실패는 반드시 ERROR 로그로 남겨</b> 운영자가 보정할 수 있어야 한다.
 *
 * <p>작성자 메모: "적립은 따로 비동기로 돌리고, 실패 로그는 맨 끝 whenComplete 에서 한 번에 남긴다."
 *
 * <p><b>관측 조건</b>: orderDb 50ms 성공, loyaltyDb 50ms 후 {@code IllegalStateException("포인트 DB 장애")}. io 풀 스레드 4개.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
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
        CompletableFuture<String> saved = CompletableFuture.supplyAsync(() -> orderDb.call(orderId), io);

        saved.thenAcceptAsync(row -> loyaltyDb.call(orderId), io);

        return saved
                .thenApply(row -> "receipt(" + row + ")")
                .whenComplete((receipt, ex) -> {
                    if (ex != null) {
                        Log.error("주문 처리 실패: " + orderId, ex);
                    }
                });
    }
}
