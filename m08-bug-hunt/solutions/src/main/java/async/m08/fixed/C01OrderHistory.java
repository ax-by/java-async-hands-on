package async.m08.fixed;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import async.harness.FakeBlockingService;

/** C01 고친 버전. 요구사항: 주문 N건을 io 풀에서 <b>동시에</b> 읽고, 입력 순서대로 "summary(행)" 목록을 반환한다. */
public final class C01OrderHistory {

    private final FakeBlockingService orderDb;
    private final Executor io;

    public C01OrderHistory(FakeBlockingService orderDb, Executor io) {
        this.orderDb = orderDb;
        this.io = io;
    }

    public List<String> recentOrders(List<String> orderIds) {
        // TODO(C01): stream 의 각 원소에서 future 는 언제 만들어지고 언제 기다려지는가? (R7, R5)
        // SOLUTION-BEGIN
        List<CompletableFuture<String>> started = orderIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> orderDb.call(id), io))
                .collect(Collectors.toList());              // 여기서 모두 시작된다 (stream 은 lazy 라 collect 가 필요)
        return started.stream()
                .map(CompletableFuture::join)                // [caller] [blocking: 가장 느린 것까지만]
                .map(row -> "summary(" + row + ")")
                .collect(Collectors.toList());
        // SOLUTION-END
    }
}
