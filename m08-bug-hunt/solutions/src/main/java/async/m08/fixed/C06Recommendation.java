package async.m08.fixed;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeEventLoopClient;
import async.m08.support.Log;

/**
 * C06 고친 버전. 요구사항: 성공이면 "recs[응답]", 실패면 WARN 로그(예외 포함)를 남기고 {@link #POPULAR} 로 대체한다.
 */
public final class C06Recommendation {

    public static final String POPULAR = async.m08.bugs.C06Recommendation.POPULAR;

    private final FakeEventLoopClient recoClient;

    public C06Recommendation(FakeEventLoopClient recoClient) {
        this.recoClient = recoClient;
    }

    public CompletableFuture<String> forProduct(String sku) {
        // TODO(C06): 결과를 바꾸는 API 는 무엇인가? 실패하면 thenApply 는 실행되는가? (R8, R9)
        // SOLUTION-BEGIN
        return recoClient.fetch(sku)
                .thenApply(recs -> "recs[" + recs + "]")
                .exceptionally(ex -> {
                    Log.warn("추천 실패, 인기 상품으로 대체: " + sku, ex);
                    return POPULAR;
                });
        // SOLUTION-END
    }
}
