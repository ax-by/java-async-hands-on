package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import async.harness.FakeBlockingService;

/**
 * C14 고친 버전. 요구사항: 200ms 안에 집계가 끝나면 그 결과(reportDb 응답 문자열), 아니면 "cached-report(query)".
 * 시간 초과된 집계는 <b>interrupt 로 중단</b>해 report 풀 스레드와 커넥션을 즉시 돌려준다.
 * (reportDb 는 interrupt 되면 즉시 중단한다. 실무에서는 JDBC {@code Statement.cancel()}/쿼리 타임아웃이 이 역할을 한다.)
 */
public final class C14SalesReport {

    private final FakeBlockingService reportDb;
    private final ExecutorService reportPool;

    public C14SalesReport(FakeBlockingService reportDb, ExecutorService reportPool) {
        this.reportDb = reportDb;
        this.reportPool = reportPool;
    }

    public CompletableFuture<String> salesReport(String query) {
        // TODO(C14): orTimeout 은 무엇을 멈추고 무엇을 멈추지 못하는가? 실제 작업을 멈출 손잡이는? (R10)
        // SOLUTION-BEGIN
        CompletableFuture<String> result = new CompletableFuture<>();
        Future<?> task = reportPool.submit(() -> {
            try {
                result.complete(reportDb.call(query));        // [report] [blocking]
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        result.orTimeout(200, TimeUnit.MILLISECONDS)          // result 자체에 타임아웃을 건다 (orTimeout 은 this 를 반환)
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        task.cancel(true);                    // 실제 작업에 interrupt → 스레드 반환
                    }
                });
        return result.exceptionally(ex -> "cached-report(" + query + ")");
        // SOLUTION-END
    }
}
