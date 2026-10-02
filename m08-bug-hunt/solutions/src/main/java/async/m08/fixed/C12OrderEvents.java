package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.m08.support.Log;

/**
 * C12 고친 버전. 요구사항: "receipt(행)" 으로 응답한다. 분석 이벤트("order-placed:{id}")는 응답을 기다리게 하지 않고,
 * 실패하면 예외를 담은 WARN 로그만 남긴다.
 */
public final class C12OrderEvents {

    private final FakeBlockingService orderDb;
    private final FakeEventLoopClient analyticsClient;
    private final Executor io;

    public C12OrderEvents(FakeBlockingService orderDb, FakeEventLoopClient analyticsClient, Executor io) {
        this.orderDb = orderDb;
        this.analyticsClient = analyticsClient;
        this.io = io;
    }

    public CompletableFuture<String> place(String orderId) {
        // TODO(C12): 버린 분기의 실패는 어디서 관찰되는가? 응답은 무엇을 기다리는가? (R6, R9)
        // SOLUTION-BEGIN
        CompletableFuture<String> receipt = CompletableFuture.supplyAsync(() -> orderDb.call(orderId), io)
                .thenApply(row -> "receipt(" + row + ")");

        receipt.thenCompose(r -> analyticsClient.fetch("order-placed:" + orderId))
                .exceptionally(ex -> {
                    Log.warn("analytics 이벤트 전송 실패(무시): " + orderId, ex);
                    return null;
                });

        return receipt;
        // SOLUTION-END
    }
}
