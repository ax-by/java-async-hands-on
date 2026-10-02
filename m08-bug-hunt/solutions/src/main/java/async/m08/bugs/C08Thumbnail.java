package async.m08.bugs;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import async.harness.FakeBlockingService;

/**
 * C08. 상품 목록 썸네일 URL 일괄 조회 (CDN 메타데이터 API 는 RestTemplate, 즉 blocking).
 *
 * <p>작성자 메모: "썸네일 조회는 짧은 HTTP 호출이라 기본 executor 로 충분하다. 리사이즈 같은 무거운 작업만 image 풀을 쓴다."
 *
 * <p><b>관측 조건</b>: SKU 개수 = commonPool 병렬도 × 2, imageCdn 지연 300ms, image 풀 스레드 32개.
 * 실행 도중 같은 JVM 의 다른 코드가 commonPool 에 작은 작업(parallel stream 등)을 하나 제출한다.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C08Thumbnail {

    private final FakeBlockingService imageCdn;
    private final Executor imagePool;

    public C08Thumbnail(FakeBlockingService imageCdn, Executor imagePool) {
        this.imageCdn = imageCdn;
        this.imagePool = imagePool;
    }

    public CompletableFuture<List<String>> thumbnails(List<String> skus) {
        List<CompletableFuture<String>> futures = skus.stream()
                .map(sku -> CompletableFuture.supplyAsync(() -> imageCdn.call("thumb/" + sku)))
                .collect(Collectors.toList());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    /** 원본 이미지 리사이즈. 무거운 작업이라 image 풀에서 실행한다. */
    public CompletableFuture<String> resize(String sku) {
        return CompletableFuture.supplyAsync(() -> imageCdn.call("resize/" + sku), imagePool);
    }
}
