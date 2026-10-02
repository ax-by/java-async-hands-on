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
    public static final Symptom C01_SYMPTOM = null;
    public static final String C01_CHECKLIST = null;

    // C02 상품 상세
    public static final Symptom C02_SYMPTOM = null;
    public static final String C02_CHECKLIST = null;

    // C03 상품 페이지
    public static final Symptom C03_SYMPTOM = null;
    public static final String C03_CHECKLIST = null;

    // C04 주문 접수 + 포인트 적립
    public static final Symptom C04_SYMPTOM = null;
    public static final String C04_CHECKLIST = null;

    // C05 주문 상세
    public static final Symptom C05_SYMPTOM = null;
    public static final String C05_CHECKLIST = null;

    // C06 추천
    public static final Symptom C06_SYMPTOM = null;
    public static final String C06_CHECKLIST = null;

    // C07 재고 배지
    public static final Symptom C07_SYMPTOM = null;
    public static final String C07_CHECKLIST = null;

    // C08 썸네일
    public static final Symptom C08_SYMPTOM = null;
    public static final String C08_CHECKLIST = null;

    // C09 결제 승인
    public static final Symptom C09_SYMPTOM = null;
    public static final String C09_CHECKLIST = null;

    // C10 장바구니 금액
    public static final Symptom C10_SYMPTOM = null;
    public static final String C10_CHECKLIST = null;

    // C11 배송비 견적
    public static final Symptom C11_SYMPTOM = null;
    public static final String C11_CHECKLIST = null;

    // C12 주문 접수 + 분석 이벤트
    public static final Symptom C12_SYMPTOM = null;
    public static final String C12_CHECKLIST = null;

    // C13 출고 준비
    public static final Symptom C13_SYMPTOM = null;
    public static final String C13_CHECKLIST = null;

    // C14 매출 리포트
    public static final Symptom C14_SYMPTOM = null;
    public static final String C14_CHECKLIST = null;

    // C15 상품 검색
    public static final Symptom C15_SYMPTOM = null;
    public static final String C15_CHECKLIST = null;

    // C16 배송 상태
    public static final Symptom C16_SYMPTOM = null;
    public static final String C16_CHECKLIST = null;

    // C17 주문 상세 (레거시 정산)
    public static final Symptom C17_SYMPTOM = null;
    public static final String C17_CHECKLIST = null;

    // C18 결제 확정
    public static final Symptom C18_SYMPTOM = null;
    public static final String C18_CHECKLIST = null;

    // C19 상품 카드
    public static final Symptom C19_SYMPTOM = null;
    public static final String C19_CHECKLIST = null;

    // C20 주문 내보내기
    public static final Symptom C20_SYMPTOM = null;
    public static final String C20_CHECKLIST = null;

    // C21 가격 single-flight 캐시
    public static final Symptom C21_SYMPTOM = null;
    public static final String C21_CHECKLIST = null;

    // C22 결제 취소
    public static final Symptom C22_SYMPTOM = null;
    public static final String C22_CHECKLIST = null;
}
