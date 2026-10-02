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
        throw new UnsupportedOperationException("TODO");
    }
}
