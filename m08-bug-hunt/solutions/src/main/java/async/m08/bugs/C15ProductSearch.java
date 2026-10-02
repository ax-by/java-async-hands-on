package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;

/**
 * C15. 상품 검색 (검색 DB 는 JDBC).
 *
 * <p>작성자 메모: "non-async 콜백은 어느 스레드에서 돌지 모른다(R1)고 배워서, 모든 단계를 Async + 명시 executor 로 고정했다.
 * 요청 스레드는 절대 잡지 않는다."
 *
 * <p><b>관측 조건</b>: 검색 6건을 연달아 제출한다(첫 요청이 가장 먼저). searchDb 지연 100ms, io 풀 스레드 2개(큐 무제한).
 * parse/filter/toResponse 는 각각 1ms 미만의 CPU 작업이다. 첫 요청의 응답시간을 본다.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C15ProductSearch {

    private final FakeBlockingService searchDb;
    private final Executor io;

    public C15ProductSearch(FakeBlockingService searchDb, Executor io) {
        this.searchDb = searchDb;
        this.io = io;
    }

    public CompletableFuture<String> search(String query) {
        return CompletableFuture.supplyAsync(() -> searchDb.call(query), io)
                .thenApplyAsync(C15ProductSearch::parse, io)
                .thenApplyAsync(C15ProductSearch::filterVisible, io)
                .thenApplyAsync(C15ProductSearch::toResponse, io);
    }

    static String parse(String raw) {
        return "parsed(" + raw + ")";
    }

    static String filterVisible(String parsed) {
        return "visible(" + parsed + ")";
    }

    static String toResponse(String visible) {
        return "response(" + visible + ")";
    }
}
