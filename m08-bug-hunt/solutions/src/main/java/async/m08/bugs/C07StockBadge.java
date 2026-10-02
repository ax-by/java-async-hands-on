package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import async.harness.FakeEventLoopClient;

/**
 * C07. 상품 목록의 재고 배지 (설계 B: 재고는 Redis, Lettuce 비동기 클라이언트).
 *
 * <p>요구사항: 응답이 300ms 를 넘거나 Redis 가 실패하면 "재고 확인 중" 배지를 보여준다.
 *
 * <p>작성자 메모: "변환은 문자열 처리뿐이라 이벤트 루프에서 바로 해도 된다. Async 로 넘기면 오히려 손해."
 *
 * <p><b>관측 조건</b>: 상품 30개 동시 요청, inventoryClient 지연 50ms(이벤트 루프 1개).
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C07StockBadge {

    private final FakeEventLoopClient inventoryClient;

    public C07StockBadge(FakeEventLoopClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    public CompletableFuture<String> badge(String sku) {
        return inventoryClient.fetch(sku)
                .thenApply(raw -> raw.substring(raw.indexOf(':') + 1))
                .thenApply(stockKey -> stockKey.endsWith("-0") ? "품절" : "구매 가능")
                .orTimeout(300, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> "재고 확인 중");
    }
}
