package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import async.harness.FakeEventLoopClient;

/** C07 고친 버전. 요구사항: "품절"/"구매 가능" 배지. 300ms 초과나 실패면 "재고 확인 중". */
public final class C07StockBadge {

    private final FakeEventLoopClient inventoryClient;

    public C07StockBadge(FakeEventLoopClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    public CompletableFuture<String> badge(String sku) {
        // TODO(C07): 각 콜백은 어느 스레드에서 실행되고, 그 본문에 blocking 이 있는가? (R1, R4)
        throw new UnsupportedOperationException("TODO");
    }
}
