package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.m08.support.AuthContext;

/**
 * C20 고친 버전. 요구사항: 제출한 쪽의 사용자(없으면 없음)로 작업을 실행하고, 작업이 끝나면 작업 스레드를
 * <b>원래 상태로 되돌린다</b>. 로그인 정보가 없는 호출은 "anonymous" 로 조회해야 한다.
 */
public final class C20OrderExport {

    private final FakeBlockingService exportDb;
    private final Executor exportExecutor;

    public C20OrderExport(FakeBlockingService exportDb, Executor pool) {
        this.exportDb = exportDb;
        this.exportExecutor = securityContextAware(pool);
    }

    /** 제출 시점의 사용자를 작업 스레드로 넘겨 주는 데코레이터. */
    static Executor securityContextAware(Executor delegate) {
        // TODO(C20): 작업 전 무엇을 저장하고, 작업 후 무엇으로 되돌려야 하는가? (R12)
        // SOLUTION-BEGIN
        return task -> {
            String user = AuthContext.get();                  // [제출 스레드] 캡처 (null 이어도 캡처한다)
            delegate.execute(() -> {
                String previous = AuthContext.get();          // [작업 스레드] 원래 값
                AuthContext.set(user);                        // null 이면 비운다 → 이전 요청의 값이 보이지 않는다
                try {
                    task.run();
                } finally {
                    AuthContext.set(previous);                // 반드시 복원
                }
            });
        };
        // SOLUTION-END
    }

    public CompletableFuture<String> export(String query) {
        return CompletableFuture.supplyAsync(
                () -> exportDb.call(AuthContext.currentUserOr("anonymous") + "/" + query), exportExecutor);
    }
}
