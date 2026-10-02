package async.m08.fixed;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeEventLoopClient;

/**
 * C13 고친 버전. 요구사항: 재고 예약("reserve:{id}")과 결제 매입("capture:{주문}")이 <b>모두 끝난 뒤</b> 완료되는 future.
 * 둘 중 하나라도 실패하면 결과 future 도 실패한다.
 */
public final class C13Fulfillment {

    private final FakeEventLoopClient inventoryClient;
    private final FakeEventLoopClient orderClient;
    private final FakeEventLoopClient paymentClient;

    public C13Fulfillment(FakeEventLoopClient inventoryClient, FakeEventLoopClient orderClient, FakeEventLoopClient paymentClient) {
        this.inventoryClient = inventoryClient;
        this.orderClient = orderClient;
        this.paymentClient = paymentClient;
    }

    public CompletableFuture<Void> fulfill(String orderId) {
        // TODO(C13): 함수가 future 를 반환하면 무엇을 써야 하는가? 타입을 끝까지 적어 보라 (R13)
        throw new UnsupportedOperationException("TODO");
    }
}
