package async.m06;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * E5. B 에서의 타임아웃 + 폴백. 아무 스레드도 막지 않고, 포기한 원격 호출은 실제로 끊는다. 채점: Ex5TimeoutFallbackTest
 *
 * <p>상황: 상품 상세에 "실시간 가격"을 붙인다. 가격 API 가 느리거나 실패하면 기본 가격(fallback)을 보여 준다.
 * 가격 API 가 장애로 수 초씩 걸리는 날에도 연결과 소켓이 쌓이면 안 된다.
 */
public final class Ex5TimeoutFallback {

    private Ex5TimeoutFallback() {
    }

    /**
     * <ul>
     *   <li>200 이면 응답 본문, 200 이 아니면 fallback</li>
     *   <li>timeout 안에 응답이 오지 않거나, 연결 실패 등 어떤 예외가 나도 fallback (예외로 실패하지 않는다)</li>
     *   <li>타임아웃이 나면 <b>HTTP 교환을 실제로 중단</b>해야 한다. 상태만 바꾸고 원격 호출을 내버려 두면 오답 (R10)</li>
     *   <li>즉시 반환하고, 어떤 스레드도 기다리며 막지 않는다 (R0, R5)</li>
     * </ul>
     */
    public static CompletableFuture<String> priceOrFallback(HttpClient client, URI priceUri, Duration timeout, String fallback) {
        // TODO(E5): orTimeout 은 상태만 바꾼다(R10). HttpClient 에는 교환 자체를 끊는 수단이 있다 (S12, S13, README 5절)
        // SOLUTION-BEGIN
        HttpRequest request = HttpRequest.newBuilder(priceUri).timeout(timeout).build();
        return client.sendAsync(request, BodyHandlers.ofString())
                .thenApply(res -> res.statusCode() == 200 ? res.body() : fallback)
                .exceptionally(ex -> fallback);
        // SOLUTION-END
    }
}
