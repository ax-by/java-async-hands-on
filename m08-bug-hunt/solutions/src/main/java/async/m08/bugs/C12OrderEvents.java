package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.m08.support.Log;

/**
 * C12. 주문 접수 + 분석 이벤트 전송.
 *
 * <p>요구사항: 분석 이벤트 전송은 best-effort 다. 주문 응답을 기다리게 하지 않고, 실패해도 주문은 성공이다.
 * 단, 전송 실패는 WARN 로그로 남긴다.
 *
 * <p>작성자 메모: "이벤트 future 는 아무도 기다리지 않으니, 실패를 관찰하는 코드를 그 분기 끝에 직접 붙였다."
 *
 * <p><b>관측 조건</b>: orderDb 50ms 성공. analyticsClient(비동기)는 200ms 후 {@code IllegalStateException("analytics 503")}.
 * io 풀 스레드 4개.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
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
        CompletableFuture<String> receipt = CompletableFuture.supplyAsync(() -> orderDb.call(orderId), io)
                .thenApply(row -> "receipt(" + row + ")");

        receipt.thenCompose(r -> analyticsClient.fetch("order-placed:" + orderId))
                .exceptionally(ex -> {
                    Log.warn("analytics 이벤트 전송 실패(무시): " + orderId, ex);
                    return null;
                });

        return receipt;
    }
}
