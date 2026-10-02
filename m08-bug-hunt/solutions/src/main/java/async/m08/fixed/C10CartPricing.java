package async.m08.fixed;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeEventLoopClient;

/** C10 고친 버전. 요구사항: "장바구니 = 가격" 문자열. 어떤 이벤트 루프도 막지 않는다. */
public final class C10CartPricing {

    private final FakeEventLoopClient cartStore;
    private final FakeEventLoopClient pricingClient;

    public C10CartPricing(FakeEventLoopClient cartStore, FakeEventLoopClient pricingClient) {
        this.cartStore = cartStore;
        this.pricingClient = pricingClient;
    }

    public CompletableFuture<String> priceCart(String cartId) {
        // TODO(C10): 람다 안에서 다른 future 의 결과를 어떻게 얻고 있는가? 그 람다는 어느 스레드에서 실행되는가? (R4, R13)
        // SOLUTION-BEGIN
        return cartStore.fetch(cartId)
                .thenCompose(cart -> pricingClient.fetch(cart)        // [cart event-loop] 요청만 보내고 반환 (non-blocking)
                        .thenApply(price -> cart + " = " + price));    // [pricing event-loop] 가벼운 조합
        // SOLUTION-END
    }
}
