package async.m09;

import async.harness.On;

/**
 * m09 예측 시트. {@link Scenarios} 와 {@link ModernScenarios} 를 읽고 null 을 예측값으로 바꾼다.
 *
 * <p>main 은 {@code --release 8} 이고 harness({@link On})를 쓸 수 없으므로, 이 시트는 test 소스에 있다.
 *
 * <p>규칙
 * <ol>
 *   <li>테스트를 돌리기 전에 <b>전부</b> 채운다. 먼저 PREDICT.md 에 근거 규칙(R 번호, L 번호)을 적고, 그다음 여기에 옮긴다.</li>
 *   <li>첫 채점 결과는 journal/ledger.tsv 에 고정된다.</li>
 *   <li>스레드: On.caller(), On.commonPool(), On.pool("nonblocking-minikv-io"), On.pool("app"), On.pool("minikv-timer").</li>
 *   <li>문자열: 값이면 그 값(예: "val:k"), 예외면 <b>단순 클래스 이름</b>(예: "IllegalStateException"). 대소문자까지 정확히.</li>
 *   <li>시간은 ms 단위 Long (오차 ±50ms 또는 ±25%). 개수는 Integer. 참/거짓은 Boolean.</li>
 * </ol>
 */
public final class Predictions {

    private Predictions() {
    }

    // S01 정책 1(같은 스레드에서 완료): 응답 전에 붙인 사용자 콜백
    public static final On S01_USER = null;

    // S02 정책 2(completion executor = app)
    public static final On S02_USER = null;
    public static final On S02_LATE = null;

    // S03 정책 1 + 타임아웃
    public static final On S03_USER = null;
    public static final String S03_EXCEPTION = null;

    // S04 내부 future 를 그대로 반환 + 호출자 A 의 complete
    public static final String S04_CALLER_B = null;

    // S05 내부 future 를 그대로 반환 + 호출자 A 의 cancel
    public static final String S05_CALLER_B = null;

    // S06 호출마다 thenApply(x -> x) 사본
    public static final String S06_CALLER_B = null;
    public static final Boolean S06_SAME_OBJECT = null;

    // S07 사본을 캐시해서 공유
    public static final String S07_CALLER_B = null;

    // S08 사용자가 바로 붙인 exceptionally 가 받는 예외
    public static final String S08_VIA_THEN_APPLY = null;
    public static final String S08_VIA_RELAY = null;

    // S09 minimalCompletionStage 를 캐스팅해서 complete
    public static final String S09_CAST_COMPLETE = null;

    // S10 가짜 async
    public static final Long S10_RETURNED_MS = null;
    public static final On S10_USER = null;

    // S11 completion executor 스레드 위에서 sync API 호출
    public static final String S11_OUTCOME = null;

    // S12 타이머 cancel 후 스케줄러 큐에 남은 작업 수
    public static final Integer S12_QUEUED = null;

    // S13 orTimeout 을 쓰는 코드가 컴파일되는가
    public static final Boolean S13_RELEASE_8 = null;
    public static final Boolean S13_SOURCE_TARGET_8 = null;
}
