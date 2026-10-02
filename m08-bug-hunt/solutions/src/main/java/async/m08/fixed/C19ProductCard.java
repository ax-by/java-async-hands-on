package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.harness.Pools;
import async.harness.Pools.NamedPool;

/**
 * C19 고친 버전. 요구사항: "카탈로그 / 가격" 카드를 만든다. enrich 풀(스레드 2, 큐 4)의 크기는 유지한다.
 * 피크에도 <b>이벤트 루프를 절대 막지 않는다</b>. enrich 풀이 가득 차면 가격 없이 "카탈로그 / price:unknown" 으로 응답한다(부하 차단).
 * 카탈로그 조회 자체가 실패하면 그 실패를 전파한다.
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

    /** enrich 전용 풀 설정. 스레드 2개, 큐 4칸은 유지한다. */
    static NamedPool newEnrichPool() {
        // TODO(C19): 큐가 찼을 때 작업은 어느 스레드에서 실행되는가? (R3 예외 1, R11)
        // SOLUTION-BEGIN
        return Pools.bounded("enrich", 2, 4, new ThreadPoolExecutor.AbortPolicy());
        // SOLUTION-END
    }

    public CompletableFuture<String> card(String sku) {
        // TODO(C19): 거절(RejectedExecutionException)을 어떻게 응답으로 바꿀 것인가? (R8, R9)
        // SOLUTION-BEGIN
        CompletableFuture<String> item = catalogClient.fetch(sku);
        return item.thenApplyAsync(i -> i + " / " + priceDb.call(sku), enrichPool)   // 거절되면 이 단계가 실패로 완료된다
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof RejectedExecutionException) {
                        return item.join() + " / price:unknown";   // item 은 이미 완료됨 → 막지 않음 (R5)
                    }
                    throw ex instanceof CompletionException ? (CompletionException) ex : new CompletionException(ex);
                });
        // SOLUTION-END
    }

    @Override
    public void close() {
        enrichPool.close();
    }
}
