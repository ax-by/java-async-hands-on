package async.m06;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import async.harness.FakeBlockingService;
import async.harness.FakeEventLoopClient;

/**
 * E4. B 파이프라인 안에 JDBC 한 구간 격리하기 ("B, 단 X 구간은 A식 격리"). 채점: Ex4IsolatedJdbcTest
 *
 * <p><b>먼저 DECISION.md 를 채운다.</b>
 *
 * <p>상황: 주문 화면 BFF. 요청마다
 * <ol>
 *   <li>세션 저장소(Redis, Lettuce: 이벤트 루프)에서 {@code "session:" + token} 을 읽어 사용자 ID 를 얻는다 (30ms)</li>
 *   <li>사용자 정보를 회원 DB 에서 읽는다 (JDBC: blocking 100ms). 비동기 드라이버가 없다.</li>
 *   <li>추천 API(이벤트 루프 기반 비동기 클라이언트)에서 {@code "reco:" + userId} 를 읽는다 (100ms)</li>
 * </ol>
 * 2와 3은 사용자 ID 만 있으면 서로 독립이다. 결과는 {@code "{user}|{reco}"}.
 */
public final class Ex4IsolatedJdbc {

    private Ex4IsolatedJdbc() {
    }

    /** 세션 값("{저장소이름}:session:{userId}")에서 사용자 ID 를 꺼낸다. 수정하지 않는다. */
    public static String userIdOf(String session) {
        return session.substring(session.lastIndexOf(':') + 1);
    }

    /**
     * <ul>
     *   <li>userDb.call(userId) 는 <b>jdbc 풀에서만</b> 실행한다. 이벤트 루프나 commonPool 에서 실행하면 오답 (R4, R11)</li>
     *   <li>회원 DB 조회와 추천 API 호출은 동시에 진행한다: 총 시간 ≈ 30 + max(100, 100) (R7)</li>
     *   <li>이벤트 루프 위 blocking 0건, 즉시 반환 (R0, R3)</li>
     *   <li>회원 DB 가 실패하면 결과는 그 예외로 실패한다 (R8)</li>
     * </ul>
     */
    public static CompletableFuture<String> orderView(FakeEventLoopClient sessionStore, FakeEventLoopClient recoApi,
                                                      FakeBlockingService userDb, String token, Executor jdbc) {
        // TODO(E4): 세션 → (supplyAsync(DB, jdbc) 와 추천 호출을 둘 다 먼저 시작) → thenCombine. 바깥은 thenCompose (R3, R7, R13)
        throw new UnsupportedOperationException("TODO");
    }
}
