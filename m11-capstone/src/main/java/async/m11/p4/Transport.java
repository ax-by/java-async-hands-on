package async.m11.p4;

import java.util.concurrent.CompletableFuture;

/**
 * 라이브러리가 쓰는 비동기 전송 계층 (Netty 기반 HTTP 클라이언트를 추상화한 SPI). (주어진 타입: 수정하지 않는다)
 *
 * <ul>
 *   <li>{@link #send(String)} 는 즉시 반환한다. 반환된 future 는 전송 계층의 <b>I/O 스레드</b>가 응답 본문으로 완료시킨다.</li>
 *   <li>반환된 future 를 {@code cancel(true)} 하면 진행 중인 요청을 중단하고 연결 자원을 돌려준다.</li>
 *   <li>요청이 끝나지 않으면 future 는 영원히 미완료일 수 있다(자체 타임아웃 없음).</li>
 * </ul>
 */
public interface Transport {

    CompletableFuture<String> send(String path);
}
