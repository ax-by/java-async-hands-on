package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import async.harness.FakeEventLoopClient;

/**
 * C11. 배송비 견적 (두 운송사 API 를 동시에 부르고 싼 쪽을 고른다. 둘 다 비동기 클라이언트).
 *
 * <p>요구사항: 운송사별 응답은 최대 300ms 까지만 기다린다. 한쪽만 응답하면 그쪽 견적, 둘 다 없으면 {@code "quote-unavailable"}.
 *
 * <p>작성자 메모: "A 사는 자주 느려서 300ms 에서 자른다. B 사는 SLA 99.99% 라 안정적이고, 실패하면 exceptionally 로 처리된다."
 *
 * <p><b>관측 조건</b>: carrierA 50ms 정상 응답. carrierB 는 연결은 됐지만 응답을 보내지 않는다(60초 동안 무응답).
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C11ShippingQuote {

    private final FakeEventLoopClient carrierA;
    private final FakeEventLoopClient carrierB;

    public C11ShippingQuote(FakeEventLoopClient carrierA, FakeEventLoopClient carrierB) {
        this.carrierA = carrierA;
        this.carrierB = carrierB;
    }

    public CompletableFuture<String> cheapest(String orderId) {
        CompletableFuture<String> a = carrierA.fetch(orderId)
                .orTimeout(300, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> null);
        CompletableFuture<String> b = carrierB.fetch(orderId)
                .exceptionally(ex -> null);
        return a.thenCombine(b, C11ShippingQuote::pick);
    }

    static String pick(String quoteA, String quoteB) {
        if (quoteA == null && quoteB == null) {
            return "quote-unavailable";
        }
        if (quoteA == null) {
            return quoteB;
        }
        if (quoteB == null) {
            return quoteA;
        }
        return quoteA.compareTo(quoteB) <= 0 ? quoteA : quoteB;
    }
}
