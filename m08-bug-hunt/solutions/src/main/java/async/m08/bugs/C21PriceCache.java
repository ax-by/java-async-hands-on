package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import async.harness.FakeEventLoopClient;

/**
 * C21. 가격 조회 single-flight 캐시. 같은 SKU 를 동시에 여러 요청이 물으면 가격 API 는 한 번만 호출하고 결과를 나눠 준다.
 * 여러 팀이 쓰는 공용 컴포넌트다. 호출자마다 시간 예산이 다르다(검색 50ms, 상품 상세 1초).
 *
 * <p>작성자 메모: "진행 중인 future 를 공유하니 중복 호출이 사라졌다. 완료되면 맵에서 지워 다음엔 새로 조회한다."
 *
 * <p><b>관측 조건</b>: priceClient 지연 200ms. 검색 화면(호출자 A)이 {@code price("sku-1").orTimeout(50ms)} 를 걸고,
 * 직후 상품 상세(호출자 B)가 {@code price("sku-1")} 를 1초 예산으로 기다린다.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C21PriceCache {

    private final FakeEventLoopClient priceClient;
    private final ConcurrentMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    public C21PriceCache(FakeEventLoopClient priceClient) {
        this.priceClient = priceClient;
    }

    public CompletableFuture<String> price(String sku) {
        return inflight.computeIfAbsent(sku, key -> priceClient.fetch(key)
                .whenComplete((price, ex) -> inflight.remove(key)));
    }
}
