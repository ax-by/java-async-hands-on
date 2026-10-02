package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import async.harness.FakeBlockingService;

/**
 * C14. 관리자 매출 리포트 (집계 쿼리는 JDBC, 가끔 수 초 걸린다).
 *
 * <p>요구사항: 200ms 안에 집계가 안 끝나면 캐시된 리포트로 응답한다. report 풀(스레드 2개)은 다른 리포트와 공유한다.
 *
 * <p>작성자 메모: "orTimeout 으로 200ms 에 자르고 캐시로 폴백한다. 느린 쿼리가 있어도 사용자는 200ms 안에 응답을 받는다."
 *
 * <p><b>관측 조건</b>: 리포트 요청 2건 동시. reportDb 지연 2000ms. report 풀 스레드 2개.
 * 모든 응답을 받은 뒤 100ms 시점에 reportDb 와 풀 상태를 본다.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C14SalesReport {

    private final FakeBlockingService reportDb;
    private final ExecutorService reportPool;

    public C14SalesReport(FakeBlockingService reportDb, ExecutorService reportPool) {
        this.reportDb = reportDb;
        this.reportPool = reportPool;
    }

    public CompletableFuture<String> salesReport(String query) {
        return CompletableFuture.supplyAsync(() -> reportDb.call(query), reportPool)
                .orTimeout(200, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> "cached-report(" + query + ")");
    }
}
