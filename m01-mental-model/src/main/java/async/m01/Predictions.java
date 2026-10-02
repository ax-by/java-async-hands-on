package async.m01;

import async.harness.On;

/**
 * m01 예측 시트. {@link Scenarios} 를 읽고 null 을 예측값으로 바꾼다.
 *
 * <p>규칙
 * <ol>
 *   <li>테스트를 돌리기 전에 <b>전부</b> 채운다. 먼저 PREDICT.md 에 근거 규칙(R 번호)을 적고, 그다음 여기에 옮긴다.</li>
 *   <li>첫 채점 결과는 journal/ledger.tsv 에 고정된다. 틀린 뒤 고쳐도 적중률은 오르지 않는다.</li>
 *   <li>사용할 수 있는 값: On.caller(), On.commonPool(), On.pool("io"|"cpu"|"completer"|"a"|"b"),
 *       On.timeoutScheduler(), On.freshThread(). 시간은 ms 단위 Long (예: 0L, 200L. 오차 ±50ms 또는 ±25%)</li>
 * </ol>
 */
public final class Predictions {

    private Predictions() {
    }

    // S01 미완료 future + non-async 콜백
    public static final On S01_SUPPLY = null;
    public static final On S01_MAP = null;

    // S02 완료된 future + non-async 콜백
    public static final On S02_MAP = null;

    // S03 executor 없는 Async
    public static final On S03_MAP = null;

    // S04 executor 지정 Async (완료 후 등록)
    public static final On S04_MAP = null;

    // S05 completer 가 완료시키는 직접 만든 future
    public static final On S05_MAP = null;

    // S06 caller 가 완료시키는 직접 만든 future
    public static final On S06_MAP = null;

    // S07 thenCombine (a 먼저, b 나중)
    public static final On S07_COMBINE = null;

    // S08 anyOf (a 먼저)
    public static final On S08_FIRST = null;

    // S09 io → Async(cpu) → non-async
    public static final On S09_SUPPLY = null;
    public static final On S09_PARSE = null;
    public static final On S09_AFTER = null;

    // S10 orTimeout 후속 콜백
    public static final On S10_AFTER_TIMEOUT = null;

    // S11 Runnable::run executor
    public static final On S11_TASK = null;

    // S12 supplyAsync 반환 시점과 join 반환 시점 (시작 후 ms)
    public static final Long S12_RETURNED_MS = null;
    public static final Long S12_JOINED_MS = null;

    // S13 completedFuture + non-async 콜백
    public static final On S13_MAP = null;

    // S14 thenCompose
    public static final On S14_COMPOSE_FN = null;
    public static final On S14_INNER = null;
    public static final On S14_AFTER = null;

    // S15 blocking 콜백을 "등록"하는 데 걸린 시간 (ms)
    public static final Long S15_REGISTER_ON_INCOMPLETE_MS = null;
    public static final Long S15_REGISTER_ON_COMPLETED_MS = null;
}
