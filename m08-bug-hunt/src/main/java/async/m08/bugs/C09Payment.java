package async.m08.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import async.harness.FakeEventLoopClient;
import async.m08.domain.CardDeclinedException;

/**
 * C09. 결제 승인 (PG 사 비동기 클라이언트).
 *
 * <p>요구사항: 카드 거절({@link CardDeclinedException})은 장애가 아니므로 {@code "declined(주문번호)"} 응답으로 안내한다.
 * 그 밖의 실패는 그대로 전파해 5xx 로 처리한다.
 *
 * <p>작성자 메모: "PG 클라이언트는 거절이면 CardDeclinedException 으로 future 를 실패시킨다(문서 확인함).
 * 처음엔 exceptionally 를 fetch 바로 뒤에 붙여서 테스트했고, 응답 포맷팅은 나중에 추가했다."
 *
 * <p><b>관측 조건</b>: paymentGateway 가 40ms 후 {@code CardDeclinedException("한도 초과")} 로 future 를 실패시킨다
 * ({@code completeExceptionally} 로 직접 실패).
 *
 * <p>이 파일은 수정하지 않는다. 읽고 진단만 한다.
 */
public final class C09Payment {

    private final FakeEventLoopClient paymentGateway;

    public C09Payment(FakeEventLoopClient paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public CompletableFuture<String> pay(String orderId) {
        return paymentGateway.fetch(orderId)
                .thenApply(approval -> "paid(" + approval + ")")
                .exceptionally(ex -> {
                    if (ex instanceof CardDeclinedException) {
                        return "declined(" + orderId + ")";
                    }
                    throw ex instanceof CompletionException ? (CompletionException) ex : new CompletionException(ex);
                });
    }
}
