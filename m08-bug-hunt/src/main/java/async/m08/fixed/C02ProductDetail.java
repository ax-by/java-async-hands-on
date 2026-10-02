package async.m08.fixed;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;

/**
 * C02 고친 버전. 요구사항: 캐시 히트면 즉시 완료된 future, 미스면 <b>호출 스레드를 막지 않고</b> db 풀에서 읽어 캐시에 넣는다.
 * 같은 SKU 를 두 번째로 부르면 DB 를 다시 호출하지 않는다.
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
        // TODO(C02): 캐시 미스일 때 DB 호출은 어느 스레드에서, future 를 반환하기 전인가 후인가? (R3, R4)
        throw new UnsupportedOperationException("TODO");
    }
}
