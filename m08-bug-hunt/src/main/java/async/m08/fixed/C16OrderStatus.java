package async.m08.fixed;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.m08.support.Log;
import async.m08.support.Mdc;

/**
 * C16 고친 버전. 요구사항: 원본과 같은 세 줄의 로그("배송 상태 조회 요청", "주문 조회", "배송 상태 수신")를 남기고
 * "status(응답)" 을 반환한다. <b>모든 로그에 요청의 traceId 가 찍혀야 한다.</b>
 */
public final class C16OrderStatus {

    private final FakeBlockingService orderDb;
    private final FakeEventLoopClient deliveryClient;
    private final Executor mdcAwareIo;

    public C16OrderStatus(FakeBlockingService orderDb, FakeEventLoopClient deliveryClient, Executor mdcAwareIo) {
        this.orderDb = orderDb;
        this.deliveryClient = deliveryClient;
        this.mdcAwareIo = mdcAwareIo;
    }

    public CompletableFuture<String> status(String orderId) {
        // TODO(C16): 마지막 콜백은 누가 실행하는가? thenApplyAsync(fn, mdcAwareIo) 로 바꾸면 데코레이터는
        //  "어느 스레드에서" MDC 를 캡처하게 되는가? (R1, R12)
        throw new UnsupportedOperationException("TODO");
    }

}
