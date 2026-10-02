package async.m06;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * E1. 진짜 비동기 fan-out (B 의 기본형). 채점: Ex1FanOutTest
 *
 * <p>상황: 장바구니 화면에서 상품 N개의 상세 정보를 상품 API 에서 가져온다. 상품 API 는 HTTP 이고,
 * 우리는 JDK {@code HttpClient} 를 쓴다. 요청량이 많아서 "요청당 스레드 N개"(A)는 감당할 수 없다.
 */
public final class Ex1FanOut {

    private Ex1FanOut() {
    }

    /**
     * uris 각각에 GET 요청을 보내고, 응답 본문을 <b>uris 와 같은 순서</b>로 담은 리스트를 future 로 반환하라.
     *
     * <ul>
     *   <li>호출 스레드를 막지 말고 즉시 반환한다 (R3, R5)</li>
     *   <li>모든 요청이 동시에 진행되어 총 소요시간 ≈ 가장 느린 응답 (R7)</li>
     *   <li>I/O 를 기다리는 동안 어떤 스레드도 점유하지 않는다: blocking {@code send()} 금지, 스레드 풀 생성 금지 (R0)</li>
     *   <li>client 의 future 는 I/O 스레드가 완료시킬 수 있다. 콜백 안에서 <b>미완료</b> future 를 기다리지 마라 (R1, R5)</li>
     * </ul>
     */
    public static CompletableFuture<List<String>> fetchAll(HttpClient client, List<URI> uris) {
        // TODO(E1): sendAsync 로 모두 "먼저 시작"(리스트에 담기) → allOf → 완료가 보장된 뒤 join 으로 모으기 (R3, R5, R7, R13)
        throw new UnsupportedOperationException("TODO");
    }
}
