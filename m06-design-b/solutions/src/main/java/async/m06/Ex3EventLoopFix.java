package async.m06;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/**
 * E3. 이벤트 루프를 막는 파이프라인 고치기. 채점: Ex3EventLoopFixTest
 *
 * <p>상황: 상품 페이지 HTML 캐시. Redis(Lettuce, 이벤트 루프 기반)에서 원본 데이터를 읽고,
 * 템플릿으로 렌더링(CPU 80ms)한 뒤, 누가 무엇을 봤는지 감사 로그 DB 에 기록(JDBC 50ms)한다.
 * 운영에서 "Redis 응답이 p99 에서만 수백 ms 씩 튄다"는 장애가 났다. 아래 {@link #renderBroken} 이 운영 코드다.
 */
public final class Ex3EventLoopFix {

    private Ex3EventLoopFix() {
    }

    /** 현재 운영 코드. <b>수정하지 않는다.</b> 무엇이 어느 스레드에서 실행되는지 R1 로 추적해 보라. */
    public static CompletableFuture<String> renderBroken(FakeEventLoopClient cache, String key,
                                                        Function<String, String> render, FakeBlockingService auditLog) {
        return cache.fetch(key)
                .thenApply(render)                     // CPU 80ms
                .thenApply(html -> {
                    auditLog.call(key);                // JDBC 50ms
                    return html;
                });
    }

    /**
     * renderBroken 과 같은 결과를 내되, 이벤트 루프에서는 아무것도 오래 하지 않도록 고쳐라.
     *
     * <ul>
     *   <li>render(CPU 작업)는 cpu 풀에서, auditLog.call(blocking)은 blocking 풀에서 실행한다 (R2, R11)</li>
     *   <li>감사 로그 기록이 끝난 뒤에 결과를 완료한다. 기록이 실패하면 결과도 그 예외로 실패한다 (R8)</li>
     *   <li>즉시 반환한다 (R3)</li>
     * </ul>
     */
    public static CompletableFuture<String> render(FakeEventLoopClient cache, String key, Function<String, String> render,
                                                   FakeBlockingService auditLog, Executor cpu, Executor blocking) {
        // TODO(E3): 각 단계의 실행 스레드를 xxxAsync(fn, executor) 로 고정한다. 어느 풀에 무엇을 보낼지가 핵심 (R1, R2, R4)
        // SOLUTION-BEGIN
        return cache.fetch(key)
                .thenApplyAsync(render, cpu)
                .thenApplyAsync(html -> {
                    auditLog.call(key);
                    return html;
                }, blocking);
        // SOLUTION-END
    }
}
