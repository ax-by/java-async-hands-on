package async.m08.fixed;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import async.harness.FakeBlockingService;

/** C08 고친 버전. 요구사항: SKU 마다 "thumb/{sku}" 를 병렬 조회해 입력 순서대로 반환한다. commonPool 을 쓰지 않는다. */
public final class C08Thumbnail {

    private final FakeBlockingService imageCdn;
    private final Executor imagePool;

    public C08Thumbnail(FakeBlockingService imageCdn, Executor imagePool) {
        this.imageCdn = imageCdn;
        this.imagePool = imagePool;
    }

    public CompletableFuture<List<String>> thumbnails(List<String> skus) {
        // TODO(C08): blocking 작업을 어느 풀에 넣고 있는가? (R2, R11)
        throw new UnsupportedOperationException("TODO");
    }
}
