package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.m08.support.AuthContext;

/**
 * C20. 주문 내보내기. 현재 사용자 권한으로 조회한다. 로그인하지 않은 호출(야간 배치 등)은 {@code anonymous} 권한(공개 데이터만)이다.
 *
 * <p>작성자 메모: "비동기 스레드에서 SecurityContext 가 비는 문제가 있어서, TaskDecorator 처럼 제출 시점의 사용자를
 * 작업 스레드로 넘기는 데코레이터를 만들었다. 사용자가 없을 때는 건드리지 않는다."
 *
 * <p><b>관측 조건</b>: export 풀 스레드 1개. alice 가 로그인한 요청에서 {@code export("q1")} 을 호출하고 끝난 뒤,
 * 로그인 정보가 없는 야간 배치가 {@code export("nightly")} 를 호출한다. exportDb 30ms.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C20OrderExport {

    private final FakeBlockingService exportDb;
    private final Executor exportExecutor;

    public C20OrderExport(FakeBlockingService exportDb, Executor pool) {
        this.exportDb = exportDb;
        this.exportExecutor = securityContextAware(pool);
    }

    /** 제출 시점의 사용자를 작업 스레드로 넘겨 주는 데코레이터 (Spring TaskDecorator 와 같은 역할). */
    static Executor securityContextAware(Executor delegate) {
        return task -> {
            String user = AuthContext.get();
            delegate.execute(() -> {
                if (user != null) {
                    AuthContext.set(user);
                }
                task.run();
            });
        };
    }

    public CompletableFuture<String> export(String query) {
        return CompletableFuture.supplyAsync(
                () -> exportDb.call(AuthContext.currentUserOr("anonymous") + "/" + query), exportExecutor);
    }
}
