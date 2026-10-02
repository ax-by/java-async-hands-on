package async.m08.bugs;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeEventLoopClient;
import async.m08.support.Log;

/**
 * C06. 상품 상세의 "함께 본 상품" 추천.
 *
 * <p>요구사항: 추천 서버가 실패하면 인기 상품 목록({@link #POPULAR})으로 대체해 페이지가 깨지지 않게 한다.
 *
 * <p>작성자 메모: "실패하면 recs 가 null 로 넘어오니까, 로그와 메트릭만 남기고 다음 단계에서 null 이면 인기 상품으로 바꾼다."
 *
 * <p><b>관측 조건</b>: 추천 서버(비동기 클라이언트)가 30ms 후 {@code IllegalStateException("reco 503")} 으로 실패한다.
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C06Recommendation {

    public static final String POPULAR = "popular[best-1,best-2,best-3]";

    private final FakeEventLoopClient recoClient;

    public C06Recommendation(FakeEventLoopClient recoClient) {
        this.recoClient = recoClient;
    }

    public CompletableFuture<String> forProduct(String sku) {
        return recoClient.fetch(sku)
                .whenComplete((recs, ex) -> {
                    if (ex != null) {
                        Log.warn("추천 실패, 인기 상품으로 대체: " + sku, ex);
                    }
                })
                .thenApply(recs -> recs != null ? "recs[" + recs + "]" : POPULAR);
    }
}
