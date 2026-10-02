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
        // SOLUTION-BEGIN
        Map<String, String> mdc = Mdc.getCopyOfContextMap();               // [caller] 조립 시점에 캡처 (MDC 있음)
        Log.info("배송 상태 조회 요청: " + orderId);
        return CompletableFuture.supplyAsync(() -> {
                    Log.info("주문 조회: " + orderId);                        // [io] 데코레이터가 제출 시점(caller)의 MDC 복사
                    return orderDb.call(orderId);
                }, mdcAwareIo)
                .thenCompose(order -> deliveryClient.fetch(order))            // 결과는 event-loop 가 완료시킨다
                .thenApply(withMdc(mdc, delivery -> {                         // [event-loop] 가벼운 단계. 캡처한 MDC 를 설치·복원
                    Log.info("배송 상태 수신: " + delivery);
                    return "status(" + delivery + ")";
                }));
        // 주의: .thenApplyAsync(fn, mdcAwareIo) 는 고쳐지지 않는다. 이 단계의 제출은 앞 단계를 완료시킨
        // event-loop 스레드에서 일어나므로, 데코레이터는 event-loop 의 (빈) MDC 를 캡처한다.
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    /** 조립 시점에 캡처한 MDC 를 콜백 실행 동안만 설치하고 원래대로 복원한다 (Micrometer ContextSnapshot.wrap 과 같은 방식). */
    static <T, R> Function<T, R> withMdc(Map<String, String> captured, Function<T, R> fn) {
        return input -> {
            Map<String, String> previous = Mdc.getCopyOfContextMap();
            Mdc.setContextMap(captured);
            try {
                return fn.apply(input);
            } finally {
                Mdc.setContextMap(previous);
            }
        };
    }
    // SOLUTION-REMOVE-END
}
