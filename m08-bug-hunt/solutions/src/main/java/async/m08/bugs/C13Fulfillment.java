package async.m08.bugs;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeEventLoopClient;

/**
 * C13. 출고 준비. 재고 예약과 결제 매입(capture)이 <b>둘 다 끝나면</b> 완료되는 future 를 돌려준다.
 * 호출자는 {@code fulfill(id).thenRun(() -> sendShippingEmail(id))} 로 출고 안내 메일을 보낸다.
 *
 * <p>작성자 메모: "재고 예약과 결제는 독립이라 동시에 진행한다. 결제는 주문 정보를 먼저 읽어야 해서 order → capture 순서."
 *
 * <p><b>관측 조건</b>: 모든 호출 성공. inventoryClient 50ms, orderClient 50ms, paymentClient(capture) 300ms. 모두 비동기 클라이언트.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
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
        CompletableFuture<String> stock = inventoryClient.fetch("reserve:" + orderId);
        CompletableFuture<?> payment = orderClient.fetch(orderId)
                .thenApply(order -> paymentClient.fetch("capture:" + order));
        return CompletableFuture.allOf(stock, payment);
    }
}
