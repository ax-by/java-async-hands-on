package async.m08.fixed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import async.harness.FakeEventLoopClient;
import async.m08.domain.CardDeclinedException;

/**
 * C09 고친 버전. 요구사항: 승인이면 "paid(응답)", 카드 거절이면 "declined(주문번호)", 그 밖의 실패는 원래 예외가
 * cause 로 보이도록 전파한다.
 */
public final class C09Payment {

    private final FakeEventLoopClient paymentGateway;

    public C09Payment(FakeEventLoopClient paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public CompletableFuture<String> pay(String orderId) {
        // TODO(C09): exceptionally 가 받는 ex 의 실제 타입은? (R8)
        // SOLUTION-BEGIN
        return paymentGateway.fetch(orderId)
                .thenApply(approval -> "paid(" + approval + ")")
                .exceptionally(ex -> {
                    Throwable cause = unwrap(ex);
                    if (cause instanceof CardDeclinedException) {
                        return "declined(" + orderId + ")";
                    }
                    throw ex instanceof CompletionException ? (CompletionException) ex : new CompletionException(cause);
                });
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
    // SOLUTION-REMOVE-END
}
