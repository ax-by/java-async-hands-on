package async.m11.p2;

import java.util.Map;
import java.util.Set;

/**
 * 발송 결과. (주어진 타입)
 *
 * @param notificationId 알림 ID
 * @param delivered      성공한 채널 → 공급자 응답 (예: PUSH → "push:u1/n-1")
 * @param failed         시도했지만 실패하거나 시간 안에 응답하지 않은 채널
 * @param auditReceipt   감사 기록 결과 (auditDb 의 반환값)
 */
public record DeliveryReport(String notificationId, Map<Channel, String> delivered, Set<Channel> failed, String auditReceipt) {

    public DeliveryReport {
        delivered = Map.copyOf(delivered);
        failed = Set.copyOf(failed);
    }
}
