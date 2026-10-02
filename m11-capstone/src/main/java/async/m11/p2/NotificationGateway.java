package async.m11.p2;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/**
 * P2. 알림 발송 게이트웨이. 요구사항은 {@code p2-notification-gateway/PROBLEM.md}, 설계 근거는 {@code DECISION.md} 에 먼저 쓴다.
 *
 * <p>주어진 의존성
 * <ul>
 *   <li>{@code preferences}: 수신 동의 조회 (비동기, 이벤트 루프가 완료)</li>
 *   <li>{@code push}, {@code email}, {@code sms}: 채널 공급자 비동기 클라이언트. {@code fetch(userId + "/" + notificationId)}
 *       → "push:u1/n-1" 같은 응답 (이벤트 루프가 완료)</li>
 *   <li>{@code auditDb}: 감사 기록 JDBC (blocking). {@code call(감사 키)} → "audit-db:{감사 키}"</li>
 * </ul>
 *
 * <p>필요한 필드는 자유롭게 추가한다. 생성자·메서드 시그니처는 바꾸지 않는다.
 */
public final class NotificationGateway implements AutoCloseable {

    private final ChannelPreferences preferences;
    private final FakeEventLoopClient push;
    private final FakeEventLoopClient email;
    private final FakeEventLoopClient sms;
    private final FakeBlockingService auditDb;


    public NotificationGateway(ChannelPreferences preferences, FakeEventLoopClient push, FakeEventLoopClient email,
                               FakeEventLoopClient sms, FakeBlockingService auditDb) {
        this.preferences = preferences;
        this.push = push;
        this.email = email;
        this.sms = sms;
        this.auditDb = auditDb;
        // TODO(P2): 필요한 실행 자원을 만든다 (R11, 결정 근거는 DECISION.md)
    }

    /**
     * 알림 하나를 사용자의 수신 동의 채널로 발송하고, 감사 기록이 커밋된 뒤 결과를 돌려준다.
     * 호출 스레드는 WebFlux 이벤트 루프일 수 있다.
     *
     * <p>TODO(P2): 반환된 future 가 어느 스레드에서 완료되는지(= 호출자가 붙인 non-async 콜백이 어디서 도는지)를 여기에 문서화하라.
     */
    public CompletableFuture<DeliveryReport> send(String notificationId, String userId) {
        // TODO(P2): DECISION.md 를 먼저 채운 뒤 구현한다 (R0, R1, R4, R10, R12)
        throw new UnsupportedOperationException("TODO");
    }


    @Override
    public void close() {
        throw new UnsupportedOperationException("TODO");
    }
}
