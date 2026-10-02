package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/** C17 고친 버전. 요구사항: "detail(정산 행)". 이벤트 루프 위 blocking 0건. */
public final class C17OrderDetail {

    private final FakeEventLoopClient orderClient;
    private final FakeBlockingService legacyDb;
    private final Executor jdbcPool;

    public C17OrderDetail(FakeEventLoopClient orderClient, FakeBlockingService legacyDb, Executor jdbcPool) {
        this.orderClient = orderClient;
        this.legacyDb = legacyDb;
        this.jdbcPool = jdbcPool;
    }

    public CompletableFuture<String> detail(String orderId) {
        // TODO(C17): thenCompose 함수와 JDBC 호출은 각각 어느 스레드에서 실행되는가? (R1, R3, R4)
        // SOLUTION-BEGIN
        return orderClient.fetch(orderId)
                .thenCompose(order -> CompletableFuture.supplyAsync(() -> legacyDb.call(order), jdbcPool)) // [event-loop] 제출만
                .thenApply(settlement -> "detail(" + settlement + ")");                                    // [jdbc] 가벼움
        // SOLUTION-END
    }
}
