package async.m08;

/**
 * m08 예측 시트. {@code bugs} 패키지의 각 사례를 <b>읽기만 하고</b> 두 가지를 적는다.
 *
 * <ol>
 *   <li>{@code Cxx_SYMPTOM}: 관측 조건에서 측정될 증상 ({@link Symptom}). 버그가 없다고 판단하면 {@link Symptom#NO_BUG}.</li>
 *   <li>{@code Cxx_CHECKLIST}: 원인에 해당하는 docs/review-checklist.md 의 안티패턴 번호 중 <b>가장 직접적인 것 하나</b>를
 *       문자열로 적는다 (예: "3", "16b"). 버그가 없으면 "0".</li>
 * </ol>
 *
 * <p>규칙
 * <ul>
 *   <li>먼저 PREDICT.md 에 람다마다 [실행 스레드]/[blocking 여부]를 적고 근거 규칙(R 번호)을 쓴 다음 여기에 옮긴다.</li>
 *   <li><b>전부</b> 채운 뒤 채점한다. 첫 채점 결과는 journal/ledger.tsv 에 고정된다.</li>
 *   <li>증상의 정의와 "여러 개가 측정되면 위의 것" 규칙은 {@link Symptom} javadoc 에 있다.</li>
 *   <li>체크리스트 번호의 정답은 해시로만 들어 있다. 테스트 코드를 읽어도 답이 보이지 않는다.</li>
 * </ul>
 */
public final class Predictions {

    private Predictions() {
    }

    // C01 주문 이력
    public static final Symptom C01_SYMPTOM = /*ANSWER*/ Symptom.SEQUENTIAL_NOT_PARALLEL /*END*/;
    public static final String C01_CHECKLIST = /*ANSWER*/ "7" /*END*/;

    // C02 상품 상세
    public static final Symptom C02_SYMPTOM = /*ANSWER*/ Symptom.CALLER_THREAD_BLOCKED /*END*/;
    public static final String C02_CHECKLIST = /*ANSWER*/ "1" /*END*/;

    // C03 상품 페이지
    public static final Symptom C03_SYMPTOM = /*ANSWER*/ Symptom.NO_BUG /*END*/;
    public static final String C03_CHECKLIST = /*ANSWER*/ "0" /*END*/;

    // C04 주문 접수 + 포인트 적립
    public static final Symptom C04_SYMPTOM = /*ANSWER*/ Symptom.EXCEPTION_LOST /*END*/;
    public static final String C04_CHECKLIST = /*ANSWER*/ "9" /*END*/;

    // C05 주문 상세
    public static final Symptom C05_SYMPTOM = /*ANSWER*/ Symptom.EVENT_LOOP_STALL /*END*/;
    public static final String C05_CHECKLIST = /*ANSWER*/ "3" /*END*/;

    // C06 추천
    public static final Symptom C06_SYMPTOM = /*ANSWER*/ Symptom.FALLBACK_NOT_APPLIED /*END*/;
    public static final String C06_CHECKLIST = /*ANSWER*/ "13" /*END*/;

    // C07 재고 배지
    public static final Symptom C07_SYMPTOM = /*ANSWER*/ Symptom.NO_BUG /*END*/;
    public static final String C07_CHECKLIST = /*ANSWER*/ "0" /*END*/;

    // C08 썸네일
    public static final Symptom C08_SYMPTOM = /*ANSWER*/ Symptom.COMMON_POOL_STARVATION /*END*/;
    public static final String C08_CHECKLIST = /*ANSWER*/ "4" /*END*/;

    // C09 결제 승인
    public static final Symptom C09_SYMPTOM = /*ANSWER*/ Symptom.FALLBACK_NOT_APPLIED /*END*/;
    public static final String C09_CHECKLIST = /*ANSWER*/ "10" /*END*/;

    // C10 장바구니 금액
    public static final Symptom C10_SYMPTOM = /*ANSWER*/ Symptom.EVENT_LOOP_STALL /*END*/;
    public static final String C10_CHECKLIST = /*ANSWER*/ "2" /*END*/;

    // C11 배송비 견적
    public static final Symptom C11_SYMPTOM = /*ANSWER*/ Symptom.NEVER_COMPLETES /*END*/;
    public static final String C11_CHECKLIST = /*ANSWER*/ "11" /*END*/;

    // C12 주문 접수 + 분석 이벤트
    public static final Symptom C12_SYMPTOM = /*ANSWER*/ Symptom.NO_BUG /*END*/;
    public static final String C12_CHECKLIST = /*ANSWER*/ "0" /*END*/;

    // C13 출고 준비
    public static final Symptom C13_SYMPTOM = /*ANSWER*/ Symptom.COMPLETES_TOO_EARLY /*END*/;
    public static final String C13_CHECKLIST = /*ANSWER*/ "14" /*END*/;

    // C14 매출 리포트
    public static final Symptom C14_SYMPTOM = /*ANSWER*/ Symptom.TIMEOUT_DOES_NOT_FREE_RESOURCES /*END*/;
    public static final String C14_CHECKLIST = /*ANSWER*/ "12" /*END*/;

    // C15 상품 검색
    public static final Symptom C15_SYMPTOM = /*ANSWER*/ Symptom.HOP_QUEUEING_DELAY /*END*/;
    public static final String C15_CHECKLIST = /*ANSWER*/ "8" /*END*/;

    // C16 배송 상태
    public static final Symptom C16_SYMPTOM = /*ANSWER*/ Symptom.CONTEXT_LOST /*END*/;
    public static final String C16_CHECKLIST = /*ANSWER*/ "15" /*END*/;

    // C17 주문 상세 (레거시 정산)
    public static final Symptom C17_SYMPTOM = /*ANSWER*/ Symptom.NO_BUG /*END*/;
    public static final String C17_CHECKLIST = /*ANSWER*/ "0" /*END*/;

    // C18 결제 확정
    public static final Symptom C18_SYMPTOM = /*ANSWER*/ Symptom.STARVATION_DEADLOCK /*END*/;
    public static final String C18_CHECKLIST = /*ANSWER*/ "5" /*END*/;

    // C19 상품 카드
    public static final Symptom C19_SYMPTOM = /*ANSWER*/ Symptom.EVENT_LOOP_STALL /*END*/;
    public static final String C19_CHECKLIST = /*ANSWER*/ "6" /*END*/;

    // C20 주문 내보내기
    public static final Symptom C20_SYMPTOM = /*ANSWER*/ Symptom.CONTEXT_LEAK /*END*/;
    public static final String C20_CHECKLIST = /*ANSWER*/ "16" /*END*/;

    // C21 가격 single-flight 캐시
    public static final Symptom C21_SYMPTOM = /*ANSWER*/ Symptom.SHARED_FUTURE_CORRUPTED /*END*/;
    public static final String C21_CHECKLIST = /*ANSWER*/ "17" /*END*/;

    // C22 결제 취소
    public static final Symptom C22_SYMPTOM = /*ANSWER*/ Symptom.CONTEXT_LOST /*END*/;
    public static final String C22_CHECKLIST = /*ANSWER*/ "16b" /*END*/;
}
