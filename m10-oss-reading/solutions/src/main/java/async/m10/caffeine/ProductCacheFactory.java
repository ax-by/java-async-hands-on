package async.m10.caffeine;

import java.time.Duration;
import java.util.concurrent.Executor;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import async.harness.FakeBlockingService;

/**
 * E1. 상품 캐시 팩토리 (기여 시뮬레이션: 팀 공용 캐시 모듈에 "올바른 기본 설정"을 넣는 PR).
 *
 * <p>기존 코드는 {@code Caffeine.newBuilder().buildAsync(repo::call)} 한 줄이었다. 리뷰에서 다음 지적을 받았다.
 * <ul>
 *   <li>로더는 JDBC(blocking)인데 어느 스레드에서 실행되는가? (guides/01-caffeine.md 1번 읽기 과제, R11)</li>
 *   <li>같은 키 동시 요청은 DB 를 몇 번 때리는가? 한 번이라면 그 보장은 어디서 오는가?</li>
 * </ul>
 */
public final class ProductCacheFactory {

    private ProductCacheFactory() {
    }

    /**
     * 요구사항
     * <ol>
     *   <li>로더({@code repo.call(id)}, blocking)는 <b>반드시 {@code loaderPool}</b> 에서 실행된다. commonPool 금지.</li>
     *   <li>최대 10,000 항목, 쓰기 후 10분 만료.</li>
     *   <li>같은 키의 동시 요청은 로더를 한 번만 호출한다. 실패한 로드는 캐시에 남지 않는다(다음 요청이 다시 로드).</li>
     * </ol>
     */
    public static AsyncLoadingCache<String, String> create(FakeBlockingService repo, Executor loaderPool) {
        // TODO(E1): Caffeine 이 로더를 실행하는 executor 는 어디서 정하는가? (Caffeine.executor, R11, O1)
        // SOLUTION-BEGIN
        return Caffeine.newBuilder()
                .executor(loaderPool)
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .buildAsync(repo::call);
        // SOLUTION-END
    }
}
