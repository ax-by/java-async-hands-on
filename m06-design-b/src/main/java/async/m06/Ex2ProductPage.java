package async.m06;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.CompletableFuture;

/**
 * E2. 3개 HTTP 엔드포인트를 합치는 non-blocking 집계기. 채점: Ex2ProductPageTest
 *
 * <p>상황: 상품 상세 BFF. 상품 정보(product), 가격(price), 재고(stock)는 서로 독립적인 HTTP API 다.
 * 이 BFF 는 초당 수천 건을 처리해야 하고, 호출당 수백 ms 가 걸리므로 B 로 설계한다.
 *
 * <p>주의: JDK {@code HttpClient} 는 4xx/5xx 응답에도 future 를 <b>정상 완료</b>시킨다(예외가 아니다).
 * 상태 코드는 직접 확인해야 한다(README 2절).
 */
public final class Ex2ProductPage {

    private Ex2ProductPage() {
    }

    /**
     * 세 API 를 <b>동시에</b> 호출해 {@code "{product 본문}|{price 본문}|{stock 본문}"} 을 만든다.
     *
     * <ul>
     *   <li>즉시 반환한다. 총 소요시간 ≈ max(세 지연시간) (R3, R7)</li>
     *   <li>어느 하나라도 상태 코드가 200 이 아니면 결과 future 는
     *       {@code IllegalStateException("HTTP {상태코드} {경로}")} 로 실패한다 (예: "HTTP 503 /price")</li>
     *   <li>client 는 future 를 단일 I/O 스레드에서 완료시킨다. 그 스레드 위의 콜백에서 미완료 future 를
     *       join/get 하면 BlockHound 위반이고, 최악에는 영원히 멈춘다 (R1, R4, R5)</li>
     * </ul>
     */
    public static CompletableFuture<String> productPage(HttpClient client, URI product, URI price, URI stock) {
        // TODO(E2): 세 호출을 먼저 시작하고 thenCombine 으로 합친다. 상태 코드 검사는 각 호출의 thenApply 에서 (R7, R13)
        throw new UnsupportedOperationException("TODO");
    }

}
