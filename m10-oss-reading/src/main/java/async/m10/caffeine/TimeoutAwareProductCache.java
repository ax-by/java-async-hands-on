package async.m10.caffeine;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;

/**
 * E2. 호출자별 타임아웃을 지원하는 캐시 파사드 (기여 시뮬레이션: "getAsync 에 타임아웃 인자 추가" PR).
 *
 * <p>첫 PR 은 {@code return cache.get(id).orTimeout(timeout)} 이었다.
 * 이 PR 을 승인할지 예측 C1, C5 의 결과와 guides/01-caffeine.md 읽기 과제 3, 4 로 판단하고 설명할 수 있어야 한다 (O2, R10).
 */
public final class TimeoutAwareProductCache {

    private final AsyncLoadingCache<String, String> cache;

    public TimeoutAwareProductCache(AsyncLoadingCache<String, String> cache) {
        this.cache = cache;
    }

    /**
     * 요구사항
     * <ol>
     *   <li>이 호출자는 {@code timeout} 안에 값이 없으면 {@code TimeoutException}(원인)으로 실패한다.</li>
     *   <li>그래도 <b>로드는 계속</b>되고, 같은 키를 기다리는 다른 호출자와 캐시 항목에는 아무 영향이 없다.</li>
     *   <li>호출자가 반환된 future 를 {@code cancel} 해도 다른 호출자와 캐시 항목에 영향이 없다.</li>
     *   <li>Java 17 API 만 사용한다.</li>
     * </ol>
     */
    public CompletableFuture<String> getAsync(String id, Duration timeout) {
        // TODO(E2): cache.get 이 준 future 를 다른 호출자도 받는다면(C1) 그 상태를 바꾸지 말고, "내 것"인 의존 future 에 타임아웃을 건다 (O2, R6, R10)
        throw new UnsupportedOperationException("TODO");
    }
}
