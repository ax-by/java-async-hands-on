package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import async.harness.FakeEventLoopClient;

/**
 * C21 고친 버전. 요구사항: 같은 SKU 동시 요청은 가격 API 를 한 번만 호출한다(single-flight 유지).
 * 한 호출자가 받은 future 에 타임아웃·취소·complete 를 해도 <b>다른 호출자의 결과는 바뀌지 않는다</b>.
 * 반환 타입은 그대로 {@code CompletableFuture<String>} 이다.
 */
public final class C21PriceCache {

    private final FakeEventLoopClient priceClient;
    private final ConcurrentMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    public C21PriceCache(FakeEventLoopClient priceClient) {
        this.priceClient = priceClient;
    }

    public CompletableFuture<String> price(String sku) {
        // TODO(C21): 공유하는 내부 future 를 그대로 내주면 무슨 일이 생기는가? orTimeout 은 무엇을 반환하는가? (R6, R10)
        // SOLUTION-BEGIN
        CompletableFuture<String> shared = inflight.computeIfAbsent(sku, key -> priceClient.fetch(key)
                .whenComplete((price, ex) -> inflight.remove(key)));
        return shared.copy();       // 호출자마다 독립된 사본 (Java 9+. Java 8 이면 shared.thenApply(x -> x))
        // SOLUTION-END
    }
}
