package async.m06;

import async.harness.On;

/**
 * m06 예측 시트. {@link Scenarios} 를 읽고 null 을 예측값으로 바꾼다.
 *
 * <p>규칙
 * <ol>
 *   <li>테스트를 돌리기 전에 <b>전부</b> 채운다. 먼저 PREDICT.md 에 근거 규칙(R 번호)을 적고, 그다음 여기에 옮긴다.</li>
 *   <li>첫 채점 결과는 journal/ledger.tsv 에 고정된다. 틀린 뒤 고쳐도 적중률은 오르지 않는다.</li>
 *   <li>사용할 수 있는 값
 *     <ul>
 *       <li>스레드: On.caller(), On.commonPool(), On.eventLoop(), On.timeoutScheduler(),
 *           On.pool("http-worker"|"worker"|"jdbc"|"io")</li>
 *       <li>시간: ms 단위 Long (예: 0L, 200L. 오차 ±50ms 또는 ±25%)</li>
 *       <li>참/거짓: Boolean.TRUE / Boolean.FALSE (true / false 로 써도 된다)</li>
 *       <li>결과: 성공이면 "OK", 실패면 unwrap(R8)한 원인 예외의 <b>단순 클래스 이름</b> (예: "IllegalStateException")</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public final class Predictions {

    private Predictions() {
    }

    // S01 기본 HttpClient.sendAsync + non-async 콜백
    public static final On S01_MAP = null;

    // S02 executor(http-worker) 를 준 HttpClient: BodySubscriber 와 non-async 콜백
    public static final On S02_BODY = null;
    public static final On S02_MAP = null;

    // S03 Netty 계열 클라이언트 future + non-async 콜백
    public static final On S03_MAP = null;

    // S04 이벤트 루프 위에서 300ms blocking → 관계없는 ping(150ms 예정)은 언제 처리되나 / BlockHound 감지
    public static final Long S04_PING_DONE_MS = null;
    public static final Boolean S04_VIOLATION = null;

    // S05 blocking 을 jdbc 풀로 넘긴 경우
    public static final Long S05_PING_DONE_MS = null;
    public static final Boolean S05_VIOLATION = null;

    // S06 이벤트 루프 위에서 "이미 완료된" future 에 non-async 콜백 등록
    public static final On S06_APPLY = null;

    // S07 thenApplyAsync(.., worker) 뒤의 exceptionally: 앞 단계가 실패하면 어디서 실행되나
    public static final On S07_FALLBACK = null;

    // S08 completedFuture(blockingCall()) : 반환까지 걸린 시간 / 이벤트 루프에서 호출 시 감지
    public static final Long S08_RETURNED_MS = null;
    public static final Boolean S08_VIOLATION = null;

    // S09 이벤트 루프 위에서 같은 클라이언트의 응답을 get(300ms) 으로 기다리면
    public static final String S09_OUTCOME = null;

    // S10 이벤트 루프 위의 잠금 경합: synchronized / ReentrantLock 을 BlockHound 가 감지하는가
    public static final Boolean S10_SYNCHRONIZED_VIOLATION = null;
    public static final Boolean S10_LOCK_VIOLATION = null;

    // S11 orTimeout 으로 폴백한 뒤에도 원격 응답은 처리되는가
    public static final Boolean S11_REMOTE_COMPLETED = null;

    // S12 HttpClient 교환 중단 시도: f.isCancelled() / BodyHandler 호출 여부(= 교환이 계속됐는가)
    public static final Boolean S12_CANCEL_TRUE_IS_CANCELLED = null;
    public static final Boolean S12_CANCEL_TRUE_HANDLER = null;
    public static final Boolean S12_CANCEL_FALSE_HANDLER = null;
    public static final Boolean S12_DERIVED_CANCEL_HANDLER = null;
    public static final Boolean S12_OR_TIMEOUT_HANDLER = null;

    // S13 HttpRequest.timeout(100ms) 의 결과
    public static final String S13_OUTCOME = null;

    // S14 100개 × 200ms: A(blocking send, 스레드 10개) vs B(sendAsync)
    public static final Long S14_A_TOTAL_MS = null;
    public static final Integer S14_A_SERVER_MAX = null;
    public static final Integer S14_B_SERVER_MAX = null;
    public static final Boolean S14_B_UNDER_400MS = null;
}
