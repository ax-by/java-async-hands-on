package async.m08.fixed;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.m08.support.Log;
import async.m08.support.Mdc;

/**
 * C22 고친 버전. 요구사항: 원본과 같은 두 줄의 로그("결제 취소 요청", "PG 취소 완료")를 남기고 refundDb 응답을 반환한다.
 * refundDb(JDBC)는 이벤트 루프가 아닌 io 풀에서 실행한다. <b>모든 로그에 요청의 traceId 가 찍혀야 한다.</b>
 */
public final class C22PaymentCancel {

    private final FakeEventLoopClient pgClient;
    private final FakeBlockingService refundDb;
    private final Executor mdcAwareIo;

    public C22PaymentCancel(FakeEventLoopClient pgClient, FakeBlockingService refundDb, Executor mdcAwareIo) {
        this.pgClient = pgClient;
        this.refundDb = refundDb;
        this.mdcAwareIo = mdcAwareIo;
    }

    public CompletableFuture<String> cancel(String paymentId) {
        // TODO(C22): 데코레이터는 "누가 execute() 를 호출할 때" MDC 를 캡처하는가? 그 스레드는 누구인가? (R1, R12)
        // SOLUTION-BEGIN
        Map<String, String> mdc = Mdc.getCopyOfContextMap();          // [caller] 조립 시점에 캡처
        Executor requestScoped = task -> mdcAwareIo.execute(() -> {    // 요청마다: 캡처한 MDC 를 설치하는 executor
            Map<String, String> previous = Mdc.getCopyOfContextMap();  // (Micrometer: ContextSnapshot.captureAll().wrapExecutor(pool))
            Mdc.setContextMap(mdc);
            try {
                task.run();
            } finally {
                Mdc.setContextMap(previous);
            }
        });
        Log.info("결제 취소 요청: " + paymentId);
        return pgClient.fetch("cancel/" + paymentId)
                .thenApplyAsync(result -> {                            // [io] execute() 는 event-loop 가 부르지만 MDC 는 조립 시점 값
                    Log.info("PG 취소 완료: " + result);
                    return refundDb.call(result);                      // [io] [blocking]
                }, requestScoped);
        // SOLUTION-END
    }
}
