package async.m09.review;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import async.m09.minikv.FakeTransport;

/**
 * <b>리뷰 대상</b>: 사내 공용 라이브러리 "kv-client v0.1" 의 첫 PR. <b>이 파일은 수정하지 않는다.</b>
 * REVIEW.md 에 리뷰 코멘트를 쓴다. (증상은 ReviewEvidenceTest 가 재현한다. 리뷰를 쓴 뒤에 읽어라.)
 *
 * <p>PR 설명: "비동기 KV 클라이언트를 추가합니다. 같은 키 동시 조회는 캐시로 합쳤고, 동기 API 도 제공합니다.
 * 성능을 위해 CompletableFuture 를 사용했습니다."
 */
public class BadKvClient {

    private final FakeTransport transport;
    private final Map<String, CompletableFuture<String>> cache = new ConcurrentHashMap<>();

    public BadKvClient(FakeTransport transport) {
        this.transport = transport;
    }

    /**
     * 값을 비동기로 조회한다.
     */
    public CompletableFuture<String> getAsync(String key) {
        return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> fetch(k)));
    }

    /**
     * 캐시를 거치지 않고 최신 값을 비동기로 조회한다.
     */
    public CompletableFuture<String> getFreshAsync(String key) {
        return CompletableFuture.completedFuture(fetch(key));
    }

    /**
     * 값을 조회한다.
     */
    public String get(String key) {
        return getAsync(key).join();
    }

    private String fetch(String key) {
        CompletableFuture<String> response = new CompletableFuture<>();
        transport.send(key, (value, error) -> {
            if (error != null) {
                response.completeExceptionally(error);
            } else {
                response.complete(value);
            }
        });
        try {
            return response.get();
        } catch (Exception e) {
            throw new RuntimeException("kv fetch failed", e);
        }
    }
}
