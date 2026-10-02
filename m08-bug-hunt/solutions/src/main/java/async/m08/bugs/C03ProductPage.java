package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;

/**
 * C03. 상품 페이지 조립 (설계 A: 독립적인 JDBC 조회 3개를 병렬로).
 * 전체 타임아웃은 호출자(컨트롤러)가 {@code orTimeout(800ms)} 으로 걸고 503 으로 바꾼다.
 *
 * <p>작성자 메모: "세 조회는 서로 독립이라 먼저 모두 시작하고 allOf 로 모은다. 순차 100+120+80 → 병렬 120ms."
 *
 * <p><b>관측 조건</b>: productDb 100ms, stockDb 120ms, reviewDb 80ms, io 풀 스레드 8개. 호출 스레드는 요청 스레드.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
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
        CompletableFuture<String> info = CompletableFuture.supplyAsync(() -> productDb.call(sku), io);
        CompletableFuture<String> stock = CompletableFuture.supplyAsync(() -> stockDb.call(sku), io);
        CompletableFuture<String> reviews = CompletableFuture.supplyAsync(() -> reviewDb.call(sku), io);
        return CompletableFuture.allOf(info, stock, reviews)
                .thenApply(v -> String.join(" | ", info.join(), stock.join(), reviews.join()));
    }
}
