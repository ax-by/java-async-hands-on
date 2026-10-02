package async.m11.p3;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
// SOLUTION-REMOVE-BEGIN (모범 답안에서만 쓰는 import: 설계 힌트가 되지 않도록 main 에서 제거)
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
// SOLUTION-REMOVE-END

/**
 * P3 고친 버전. {@link RemittanceQuoteServiceUnderReview} 를 리뷰한 뒤(REVIEW.md), 요구사항(PROBLEM.md)을 모두 만족하도록 고쳐 쓴다.
 * 생성자·메서드 시그니처는 바꾸지 않는다. 헬퍼 메서드는 자유롭게 추가한다.
 */
public final class RemittanceQuoteService {

    static final Map<String, String> CACHED_RATES = Map.of("USD-KRW", "fx-cache:USD-KRW", "JPY-KRW", "fx-cache:JPY-KRW");

    private final FakeBlockingService kycDb;
    private final FakeEventLoopClient fxClient;
    private final FakeBlockingService feeEngine;
    private final FakeEventLoopClient sanctions;
    private final FakeBlockingService quoteDb;
    private final Executor ioPool;

    public RemittanceQuoteService(FakeBlockingService kycDb, FakeEventLoopClient fxClient, FakeBlockingService feeEngine,
                                  FakeEventLoopClient sanctions, FakeBlockingService quoteDb, Executor ioPool) {
        this.kycDb = kycDb;
        this.fxClient = fxClient;
        this.feeEngine = feeEngine;
        this.sanctions = sanctions;
        this.quoteDb = quoteDb;
        this.ioPool = ioPool;
    }

    public CompletableFuture<Quote> quote(String customerId, String pair, long amount) {
        // TODO(P3): REVIEW.md 에 쓴 결함을 모두 고친다. 원래 의도(병렬 진행, 폴백, KYC 매핑)는 유지한다.
        // SOLUTION-BEGIN
        String channel = RequestContext.channel();                                  // [caller] 값으로 캡처 (R12, #15)

        CompletableFuture<String> kyc = CompletableFuture.supplyAsync(() -> kycDb.call(customerId), ioPool) // [io] (R2, #4)
                .handle((row, ex) -> {
                    if (ex != null) {
                        throw new CompletionException(new KycRejectedException(customerId, unwrap(ex)));
                    }
                    return row;
                });

        CompletableFuture<String> rate = fxClient.fetch(pair)
                .thenApply(String::trim)                                           // [event-loop] 가벼움
                .orTimeout(FX_TIMEOUT_MS, TimeUnit.MILLISECONDS)                    // (R10, #11)
                .exceptionally(ex -> {
                    Throwable cause = unwrap(ex);                                   // (R8, #10)
                    if (cause instanceof RateUnavailableException || cause instanceof TimeoutException) {
                        return CACHED_RATES.get(pair);
                    }
                    throw new CompletionException(cause);
                });

        CompletableFuture<String> fee = rate.thenApplyAsync(r -> feeEngine.call(amount + "@" + r), ioPool); // [io] (R4, #3)

        CompletableFuture<String> screening = sanctions.fetch(customerId)          // 결과를 버리지 않고 합류시킨다 (R6, #9)
                .orTimeout(SCREENING_TIMEOUT_MS, TimeUnit.MILLISECONDS);             // 응답 없으면 거절 (fail-closed)

        return CompletableFuture.allOf(kyc, rate, fee, screening)
                .thenApply(v -> new Quote(customerId, rate.join(), fee.join(), null)) // 모두 완료 → 막지 않음 (R5)
                .thenCompose(draft -> save(draft, channel))                         // (R13, #14)
                .orTimeout(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);                 // 전체 SLO
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    static final long FX_TIMEOUT_MS = 200;
    static final long SCREENING_TIMEOUT_MS = 300;
    static final long TOTAL_TIMEOUT_MS = 600;

    private CompletableFuture<Quote> save(Quote draft, String channel) {
        return CompletableFuture.supplyAsync(() -> {
            String quoteId = quoteDb.call(draft.customerId() + "/" + draft.rate() + "/" + channel);   // [io] [blocking]
            return new Quote(draft.customerId(), draft.rate(), draft.fee(), quoteId);
        }, ioPool);
    }
    // SOLUTION-REMOVE-END

    static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
