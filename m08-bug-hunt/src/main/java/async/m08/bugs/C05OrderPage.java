package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/**
 * C05. 주문 상세 페이지 (설계 B: 주문 API 는 WebClient 기반 비동기 클라이언트).
 *
 * <p>작성자 메모: "무거운 이력 조회는 db 풀로 격리했다. 쿠폰 조회는 인덱스를 타서 평소 3ms 라 그냥 이어서 조회한다."
 *
 * <p><b>관측 조건</b>: 주문 10건 동시 요청. orderClient 지연 50ms(이벤트 루프 1개), couponRepository 50ms(부하 시 p99),
 * historyRepository 30ms, db 풀 스레드 8개.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C05OrderPage {

    private final FakeEventLoopClient orderClient;
    private final FakeBlockingService couponRepository;
    private final FakeBlockingService historyRepository;
    private final Executor dbPool;

    public C05OrderPage(FakeEventLoopClient orderClient, FakeBlockingService couponRepository,
                        FakeBlockingService historyRepository, Executor dbPool) {
        this.orderClient = orderClient;
        this.couponRepository = couponRepository;
        this.historyRepository = historyRepository;
        this.dbPool = dbPool;
    }

    public CompletableFuture<String> orderPage(String orderId) {
        return orderClient.fetch(orderId)
                .thenApply(order -> order + " / " + couponRepository.call(orderId))
                .thenApplyAsync(page -> page + " / " + historyRepository.call(orderId), dbPool);
    }
}
