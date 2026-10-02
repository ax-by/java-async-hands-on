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
        throw new UnsupportedOperationException("TODO");
    }
}
