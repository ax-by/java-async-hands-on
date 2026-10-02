package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import async.harness.FakeEventLoopClient;

/**
 * C11 고친 버전. 요구사항: 운송사별로 최대 300ms 만 기다린다. 한쪽만 응답하면 그쪽 견적, 둘 다 없으면 "quote-unavailable".
 * 어떤 경우에도 결과 future 는 약 300ms 안에 완료된다.
 */
public final class C11ShippingQuote {

    private final FakeEventLoopClient carrierA;
    private final FakeEventLoopClient carrierB;

    public C11ShippingQuote(FakeEventLoopClient carrierA, FakeEventLoopClient carrierB) {
        this.carrierA = carrierA;
        this.carrierB = carrierB;
    }

    public CompletableFuture<String> cheapest(String orderId) {
        // TODO(C11): 결과에 이르는 모든 경로에 시간 상한이 있는가? (R10)
        // SOLUTION-BEGIN
        CompletableFuture<String> a = carrierA.fetch(orderId)
                .orTimeout(300, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> null);
        CompletableFuture<String> b = carrierB.fetch(orderId)
                .orTimeout(300, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> null);
        return a.thenCombine(b, C11ShippingQuote::pick);
        // SOLUTION-END
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
