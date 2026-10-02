package async.m08.bugs;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeEventLoopClient;

/**
 * C10. 장바구니 금액 계산 (설계 B: 장바구니는 Redis, 가격은 가격 서비스. 둘 다 Netty 기반 비동기 클라이언트).
 *
 * <p>작성자 메모: "가격은 장바구니 내용이 있어야 조회할 수 있어서 순차다. 두 클라이언트 모두 비동기라 스레드를 잡지 않는다."
 *
 * <p><b>관측 조건</b>: 장바구니 5개 동시 요청. cartStore 지연 20ms, pricingClient 지연 100ms. 클라이언트마다 이벤트 루프 1개.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C10CartPricing {

    private final FakeEventLoopClient cartStore;
    private final FakeEventLoopClient pricingClient;

    public C10CartPricing(FakeEventLoopClient cartStore, FakeEventLoopClient pricingClient) {
        this.cartStore = cartStore;
        this.pricingClient = pricingClient;
    }

    public CompletableFuture<String> priceCart(String cartId) {
        return cartStore.fetch(cartId)
                .thenApply(cart -> {
                    String price = pricingClient.fetch(cart).join();
                    return cart + " = " + price;
                });
    }
}
