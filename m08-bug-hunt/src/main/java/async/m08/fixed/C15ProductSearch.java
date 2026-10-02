package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;

/**
 * C15 고친 버전. 요구사항: "response(visible(parsed(행)))" 을 반환한다. 요청 스레드는 막지 않는다.
 * 가벼운 단계 때문에 바쁜 io 풀 큐에서 다시 기다리지 않는다.
 */
public final class C15ProductSearch {

    private final FakeBlockingService searchDb;
    private final Executor io;

    public C15ProductSearch(FakeBlockingService searchDb, Executor io) {
        this.searchDb = searchDb;
        this.io = io;
    }

    public CompletableFuture<String> search(String query) {
        // TODO(C15): 가벼운 변환에도 스레드 전환(큐 재진입)이 필요한가? (R1, R2)
        throw new UnsupportedOperationException("TODO");
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
