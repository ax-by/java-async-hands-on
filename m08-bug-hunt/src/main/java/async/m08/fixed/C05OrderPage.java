package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/**
 * C05 고친 버전. 요구사항: "주문 / 쿠폰 / 이력" 형식의 페이지를 만든다. 이벤트 루프에서 blocking 이 0건이어야 한다.
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
        // TODO(C05): 비동기 클라이언트 future 뒤의 blocking 단계는 어디서 실행되는가? (R1, R4)
        throw new UnsupportedOperationException("TODO");
    }
}
