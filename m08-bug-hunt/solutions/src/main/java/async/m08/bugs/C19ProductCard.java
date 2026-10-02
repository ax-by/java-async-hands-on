package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;

/**
 * C19. 상품 카드 (설계 B: 카탈로그는 비동기 클라이언트, 가격은 JDBC 라 enrich 풀로 격리).
 *
 * <p>작성자 메모: "JDBC 는 enrich 풀로 격리했다(thenApplyAsync + 전용 풀). 풀은 bounded 로 만들었고,
 * 큐가 차더라도 요청을 버리지 않도록 CallerRunsPolicy 를 걸었다. 부하 테스트 전 기능 테스트는 모두 통과."
 *
 * <p><b>관측 조건</b>: 카탈로그 응답 50ms(이벤트 루프 1개), priceDb 100ms. enrich 풀은 아래 {@link #newEnrichPool()} 설정.
 * 동시 요청 3건(평시)과 20건(피크)으로 각각 실행한다.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C19ProductCard implements AutoCloseable {

    private final FakeEventLoopClient catalogClient;
    private final FakeBlockingService priceDb;
    private final NamedPool enrichPool;

    public C19ProductCard(FakeEventLoopClient catalogClient, FakeBlockingService priceDb) {
        this.catalogClient = catalogClient;
        this.priceDb = priceDb;
        this.enrichPool = newEnrichPool();
    }

    /** enrich 전용 풀 설정 (@Bean 메서드에 해당). 스레드 2개, 큐 4칸. */
    static NamedPool newEnrichPool() {
        return Pools.bounded("enrich", 2, 4, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public CompletableFuture<String> card(String sku) {
        return catalogClient.fetch(sku)
                .thenApplyAsync(item -> item + " / " + priceDb.call(sku), enrichPool);
    }

    @Override
    public void close() {
        enrichPool.close();
    }
}
