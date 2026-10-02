package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
import async.m08.support.Log;

/**
 * C16. 주문 배송 상태 조회. 장애 추적을 위해 이 요청의 모든 로그에 traceId(MDC)가 찍혀야 한다.
 *
 * <p>{@code mdcAwareIo} 는 MDC 를 복사·복원해 주는 올바른 데코레이터({@link async.m08.support.MdcExecutor})로 감싼
 * io 풀이다(Spring 의 TaskDecorator 설정과 같다). deliveryClient 는 비동기 클라이언트다.
 *
 * <p>작성자 메모: "TaskDecorator 로 MDC 전파를 설정해 뒀으니 비동기 구간에서도 traceId 가 유지된다."
 *
 * <p><b>관측 조건</b>: 요청 스레드 MDC 에 traceId=t-42. orderDb 50ms, deliveryClient 50ms, io 풀 스레드 4개.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
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
        Log.info("배송 상태 조회 요청: " + orderId);
        return CompletableFuture.supplyAsync(() -> {
                    Log.info("주문 조회: " + orderId);
                    return orderDb.call(orderId);
                }, mdcAwareIo)
                .thenCompose(order -> deliveryClient.fetch(order))
                .thenApply(delivery -> {
                    Log.info("배송 상태 수신: " + delivery);
                    return "status(" + delivery + ")";
                });
    }
}
