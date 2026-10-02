package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;

/** C03 고친 버전. 요구사항: 세 조회를 io 풀에서 병렬로 실행하고 "info | stock | reviews" 로 합친다. */
public final class C03ProductPage {

    private final FakeBlockingService productDb;
    private final FakeBlockingService stockDb;
    private final FakeBlockingService reviewDb;
    private final Executor io;

    public C03ProductPage(FakeBlockingService productDb, FakeBlockingService stockDb, FakeBlockingService reviewDb, Executor io) {
        this.productDb = productDb;
        this.stockDb = stockDb;
        this.reviewDb = reviewDb;
        this.io = io;
    }

    public CompletableFuture<String> page(String sku) {
        // TODO(C03): 세 조회는 언제 시작되는가? join 하는 시점에 각 future 의 완료가 보장되는가? (R5, R7)
        throw new UnsupportedOperationException("TODO");
    }
}
