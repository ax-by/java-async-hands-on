package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/**
 * C17. 주문 상세 (설계 B + 격리 구간: 주문은 비동기 클라이언트, 레거시 정산 정보는 아직 JDBC 뿐이다).
 *
 * <p>작성자 메모: "정산 테이블은 JDBC 밖에 없어서 그 호출만 jdbc 풀로 보냈다. 나머지는 non-blocking."
 *
 * <p><b>관측 조건</b>: 주문 20건 동시 요청. orderClient 50ms(이벤트 루프 1개), legacyDb 80ms, jdbc 풀 스레드 20개(큐 무제한).
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
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
        return orderClient.fetch(orderId)
                .thenCompose(order -> CompletableFuture.supplyAsync(() -> legacyDb.call(order), jdbcPool))
                .thenApply(settlement -> "detail(" + settlement + ")");
    }
}
