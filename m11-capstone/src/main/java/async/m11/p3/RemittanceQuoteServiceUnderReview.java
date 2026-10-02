package async.m11.p3;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/**
 * P3 리뷰 대상 PR #1482 "해외 송금 견적 API 비동기화". <b>이 파일은 수정하지 않는다.</b> 리뷰하고, 고친 버전은
 * {@link RemittanceQuoteService} 에 작성한다. 요구사항은 {@code p3-code-review/PROBLEM.md}.
 *
 * <p>PR 설명: "기존 동기 구현(평균 260ms)을 비동기로 바꿨습니다. KYC·환율·스크리닝을 동시에 진행해 평균 110ms 입니다.
 * 환율 API 장애 시 캐시 환율 폴백, KYC 실패 시 KycRejectedException 매핑까지 반영했습니다. 단위 테스트 통과."
 */
public final class RemittanceQuoteServiceUnderReview {

    private static final Map<String, String> CACHED_RATES = Map.of("USD-KRW", "fx-cache:USD-KRW", "JPY-KRW", "fx-cache:JPY-KRW");

    private final FakeBlockingService kycDb;        // JDBC
    private final FakeEventLoopClient fxClient;     // 환율 API (Netty 기반 비동기 클라이언트)
    private final FakeBlockingService feeEngine;    // 사내 수수료 엔진 (동기 HTTP 클라이언트)
    private final FakeEventLoopClient sanctions;    // 제재 대상 스크리닝 API (비동기 클라이언트)
    private final FakeBlockingService quoteDb;      // JDBC
    private final Executor ioPool;                  // blocking 작업 전용 풀

    public RemittanceQuoteServiceUnderReview(FakeBlockingService kycDb, FakeEventLoopClient fxClient, FakeBlockingService feeEngine,
                                             FakeEventLoopClient sanctions, FakeBlockingService quoteDb, Executor ioPool) {
        this.kycDb = kycDb;
        this.fxClient = fxClient;
        this.feeEngine = feeEngine;
        this.sanctions = sanctions;
        this.quoteDb = quoteDb;
        this.ioPool = ioPool;
    }

    public CompletableFuture<Quote> quote(String customerId, String pair, long amount) {
        // 1) KYC 확인. 승인되지 않은 고객이면 KycRejectedException
        CompletableFuture<String> kyc = CompletableFuture.supplyAsync(() -> kycDb.call(customerId))
                .handle((row, ex) -> {
                    if (ex != null) {
                        throw new CompletionException(new KycRejectedException(customerId, unwrap(ex)));
                    }
                    return row;
                });

        // 2) 환율. 환율 API 가 RateUnavailableException 을 주면 캐시 환율로 폴백
        CompletableFuture<String> rate = fxClient.fetch(pair)
                .thenApply(String::trim)
                .exceptionally(ex -> {
                    if (ex instanceof RateUnavailableException) {
                        return CACHED_RATES.get(pair);
                    }
                    throw new CompletionException(ex);
                });

        // 3) 수수료. 환율이 나오면 바로 계산
        CompletableFuture<String> fee = rate.thenApply(r -> feeEngine.call(amount + "@" + r));

        // 4) 제재 대상 스크리닝 (규정상 필수)
        sanctions.fetch(customerId)
                .thenAccept(result -> QuoteLog.info("screening passed: " + result));

        // 5) 모두 모이면 저장하고 견적 반환
        return CompletableFuture.allOf(kyc, rate, fee)
                .thenApply(v -> new Quote(customerId, rate.join(), fee.join(), null))
                .thenApply(draft -> save(draft))
                .thenApply(CompletableFuture::join);
    }

    private CompletableFuture<Quote> save(Quote draft) {
        return CompletableFuture.supplyAsync(() -> {
            String channel = RequestContext.channel();
            String quoteId = quoteDb.call(draft.customerId() + "/" + draft.rate() + "/" + channel);
            return new Quote(draft.customerId(), draft.rate(), draft.fee(), quoteId);
        }, ioPool);
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
