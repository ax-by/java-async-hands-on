package async.m08.bugs;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;

/**
 * C02. 상품 상세 조회. WebFlux 게이트웨이(이벤트 루프)가 {@link #find(String)} 의 future 에 콜백을 붙여 응답을 만든다.
 *
 * <p>작성자 메모: "캐시 히트율이 99% 라 대부분 즉시 끝난다. 그래서 굳이 스레드를 넘기지 않고 completedFuture 로 돌려준다.
 * 시그니처는 future 라서 나중에 비동기 캐시로 바꿔도 호출자는 그대로다."
 *
 * <p><b>관측 조건</b>: 캐시가 비어 있는 상태(배포 직후)에서 {@code find("p-1")} 을 호출한다. productDb 지연 150ms, db 풀 스레드 4개.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C02ProductDetail {

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final FakeBlockingService productDb;
    private final Executor dbPool;

    public C02ProductDetail(FakeBlockingService productDb, Executor dbPool) {
        this.productDb = productDb;
        this.dbPool = dbPool;
    }

    public CompletableFuture<String> find(String sku) {
        String detail = cache.computeIfAbsent(sku, productDb::call);
        return CompletableFuture.completedFuture(detail);
    }

    /** 배포 직후 배치가 호출하는 캐시 예열. */
    public CompletableFuture<Void> warmUp(List<String> skus) {
        return CompletableFuture.runAsync(() -> skus.forEach(sku -> cache.computeIfAbsent(sku, productDb::call)), dbPool);
    }
}
