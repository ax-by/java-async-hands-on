package async.m11.p3;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

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
        throw new UnsupportedOperationException("TODO");
    }


    static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
