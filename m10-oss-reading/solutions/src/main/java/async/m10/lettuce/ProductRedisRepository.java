package async.m10.lettuce;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.lettuce.core.api.async.RedisAsyncCommands;

import async.harness.FakeBlockingService;

/**
 * E6. Lettuce 기반 상품 저장소의 blocking 콜백 버그 수정 (기여 시뮬레이션: 버그 리포트 → 수정 PR).
 *
 * <p>버그 리포트: "상품 조회 API 를 배포한 뒤 Redis 관련 지연 지표가 나빠졌다." 리뷰어는 아래 코드를 의심한다.
 * <pre>{@code
 * // 현재 코드 (버그)
 * return redis.get("product:" + id)
 *         .thenApply(name -> { auditDb.call("viewed:" + name); return name; })   // JDBC INSERT
 *         .toCompletableFuture();
 * }</pre>
 * L1, L2 의 결과로 원인을 설명할 수 있어야 한다. 리뷰 코멘트 양식은 docs/review-checklist.md 를 따른다.
 */
public final class ProductRedisRepository {

    private final RedisAsyncCommands<String, String> redis;
    private final FakeBlockingService auditDb;
    private final Executor dbPool;

    public ProductRedisRepository(RedisAsyncCommands<String, String> redis, FakeBlockingService auditDb, Executor dbPool) {
        this.redis = redis;
        this.auditDb = auditDb;
        this.dbPool = dbPool;
    }

    /**
     * 요구사항
     * <ol>
     *   <li>Redis 에서 {@code "product:" + id} 의 이름을 읽고, 감사 로그 {@code auditDb.call("viewed:" + name)} 을 남긴 뒤 이름을 돌려준다.</li>
     *   <li>감사 로그(blocking)는 <b>반드시 dbPool</b> 에서 실행한다. Lettuce 이벤트 루프에서 blocking 0건(BlockHound 로 검사).</li>
     *   <li>감사 로그가 300ms 걸리는 동안에도 같은 연결의 다른 명령은 지연되지 않는다.</li>
     *   <li>호출 스레드를 막지 않는다(즉시 future 반환).</li>
     * </ol>
     */
    public CompletableFuture<String> findNameWithAudit(String id) {
        // TODO(E6): 콜백의 실행 스레드를 R1 에 맡기지 말고 고정한다 (R1, R2, R4, O1)
        // SOLUTION-BEGIN
        return redis.get("product:" + id).toCompletableFuture()
                .thenApplyAsync(name -> {
                    auditDb.call("viewed:" + name);
                    return name;
                }, dbPool);
        // SOLUTION-END
    }
}
