package async.m11.p2;

import java.util.concurrent.CompletableFuture;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;
// SOLUTION-REMOVE-BEGIN (모범 답안에서만 쓰는 import: 설계 힌트가 되지 않도록 main 에서 제거)
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import async.harness.Pools;
// SOLUTION-REMOVE-END

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

    // SOLUTION-REMOVE-BEGIN
    static final long PREFS_TIMEOUT_MS = 100;
    static final long CHANNEL_TIMEOUT_MS = 300;
    /**
     * 감사 기록 전용 blocking 풀 (격리 구간). Little: 피크 1,000건/s × 30ms = 동시 30 → 32.
     * 큐 1,000(= 1초분), AbortPolicy: 거절되면 발송 결과를 실패로 돌려 호출자가 재시도하게 한다. CallerRuns 금지(제출자가 이벤트 루프).
     */
    private final ThreadPoolExecutor auditPool;
    // SOLUTION-REMOVE-END

    public NotificationGateway(ChannelPreferences preferences, FakeEventLoopClient push, FakeEventLoopClient email,
                               FakeEventLoopClient sms, FakeBlockingService auditDb) {
        this.preferences = preferences;
        this.push = push;
        this.email = email;
        this.sms = sms;
        this.auditDb = auditDb;
        // TODO(P2): 필요한 실행 자원을 만든다 (R11, 결정 근거는 DECISION.md)
        // SOLUTION-REMOVE-BEGIN
        this.auditPool = new ThreadPoolExecutor(32, 32, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1_000),
                Pools.namedFactory("audit"), new ThreadPoolExecutor.AbortPolicy());
        // SOLUTION-REMOVE-END
    }

    /**
     * 알림 하나를 사용자의 수신 동의 채널로 발송하고, 감사 기록이 커밋된 뒤 결과를 돌려준다.
     * 호출 스레드는 WebFlux 이벤트 루프일 수 있다.
     *
     * <p>TODO(P2): 반환된 future 가 어느 스레드에서 완료되는지(= 호출자가 붙인 non-async 콜백이 어디서 도는지)를 여기에 문서화하라.
     */
    public CompletableFuture<DeliveryReport> send(String notificationId, String userId) {
        // TODO(P2): DECISION.md 를 먼저 채운 뒤 구현한다 (R0, R1, R4, R10, R12)
        // SOLUTION-BEGIN
        String tenant = TenantContext.get();                                  // [caller] 조립 시점에 값으로 캡처 (R12)
        return preferences.channelsOf(userId)
                .completeOnTimeout(Set.of(Channel.PUSH), PREFS_TIMEOUT_MS, TimeUnit.MILLISECONDS) // 호출마다 새 future 라 안전
                .exceptionally(ex -> Set.of(Channel.PUSH))                    // 조회 실패 → 기본 채널
                .thenCompose(channels -> fanOut(notificationId, userId, channels)) // [event-loop 또는 timer] 요청만 보낸다
                .thenCompose(partial -> CompletableFuture
                        .supplyAsync(() -> auditDb.call(auditKey(tenant, partial)), auditPool)   // [audit] [blocking] 격리 구간
                        .thenApply(receipt -> new DeliveryReport(notificationId, partial.delivered(), partial.failed(), receipt)));
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    private CompletableFuture<DeliveryReport> fanOut(String notificationId, String userId, Set<Channel> channels) {
        Map<Channel, CompletableFuture<String>> sends = new EnumMap<>(Channel.class);
        for (Channel ch : channels) {                                         // 모두 먼저 시작 (R7)
            sends.put(ch, clientOf(ch).fetch(userId + "/" + notificationId)
                    .orTimeout(CHANNEL_TIMEOUT_MS, TimeUnit.MILLISECONDS)     // 비동기 요청이라 스레드를 잡지 않는다
                    .handle((v, ex) -> ex == null ? v : null));               // 채널 실패는 결과의 일부 (전체 실패 아님)
        }
        return CompletableFuture.allOf(sends.values().toArray(new CompletableFuture<?>[0]))
                .thenApply(v -> {                                             // 가벼운 조립
                    Map<Channel, String> delivered = new EnumMap<>(Channel.class);
                    Set<Channel> failed = EnumSet.noneOf(Channel.class);
                    sends.forEach((ch, f) -> {
                        String response = f.join();                           // 이미 완료 (R5)
                        if (response == null) {
                            failed.add(ch);
                        } else {
                            delivered.put(ch, response);
                        }
                    });
                    return new DeliveryReport(notificationId, delivered, failed, null);
                });
    }

    private FakeEventLoopClient clientOf(Channel channel) {
        switch (channel) {
            case PUSH:
                return push;
            case EMAIL:
                return email;
            default:
                return sms;
        }
    }

    private static String auditKey(String tenant, DeliveryReport partial) {
        int requested = partial.delivered().size() + partial.failed().size();
        return tenant + "|" + partial.notificationId() + "|" + partial.delivered().size() + "/" + requested;
    }
    // SOLUTION-REMOVE-END

    @Override
    public void close() {
        // SOLUTION-BEGIN
        auditPool.shutdownNow();
        // SOLUTION-END
    }
}
