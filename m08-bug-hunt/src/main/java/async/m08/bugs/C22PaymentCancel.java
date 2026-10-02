package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.m08.support.Log;

/**
 * C22. 결제 취소. 장애 추적을 위해 이 요청의 모든 로그에 traceId(MDC)가 찍혀야 한다.
 *
 * <p>{@code mdcAwareIo} 는 MDC 를 복사·복원해 주는 올바른 데코레이터({@link async.m08.support.MdcExecutor})로 감싼
 * io 풀이다(Spring 의 TaskDecorator 설정과 같다). pgClient 는 비동기 PG 클라이언트, refundDb 는 JDBC 다.
 *
 * <p>작성자 메모: "non-async 콜백은 이벤트 루프에서 돌아 MDC 가 없다는 걸 알고 있다(지난번 장애). 그래서 PG 응답 이후는
 * TaskDecorator 가 걸린 io 풀로 넘겼다. JDBC 도 격리되고 traceId 도 유지된다."
 *
 * <p><b>관측 조건</b>: 요청 스레드 MDC 에 traceId=t-77. pgClient 50ms, refundDb 30ms, io 풀 스레드 4개.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
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
        Log.info("결제 취소 요청: " + paymentId);
        return pgClient.fetch("cancel/" + paymentId)
                .thenApplyAsync(result -> {
                    Log.info("PG 취소 완료: " + result);
                    return refundDb.call(result);
                }, mdcAwareIo);
    }
}
