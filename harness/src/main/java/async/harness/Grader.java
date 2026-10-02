package async.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 예측 채점기.
 *
 * <p>한 테스트 안의 여러 예측을 모두 채점한 뒤 한꺼번에 결과를 보여준다.
 * 각 예측의 <b>첫 번째 채점 결과</b>는 {@code journal/ledger.tsv} 에 기록되고 이후에는 바뀌지 않는다.
 * (결과를 보고 예측을 고쳐도 적중률은 오르지 않는다. 적중률은 {@code tools/score.sh} 로 확인한다.)
 *
 * <pre>{@code
 * Grader g = new Grader();
 * g.check("m01.ex01.early", M01Predictions.EX01_EARLY, rec.on("early"), "R1");
 * g.checkMillis("m05.ex02.total", M05Predictions.EX02_TOTAL_MS, elapsed, "R7");
 * g.verify();   // 틀렸거나 아직 예측하지 않은 항목이 있으면 실패
 * }</pre>
 */
public final class Grader {

    private static final long MIN_TOLERANCE_MS = 50;

    private record Item(String id, Object predicted, Object actual, String rule, boolean hit) {
    }

    private final List<Item> items = new ArrayList<>();

    /**
     * @param id        전역 고유 ID. "모듈.실습.항목" 형식 (예: m01.ex01.early)
     * @param predicted 내 예측. null 이면 "아직 예측하지 않음"
     * @param actual    실제 관측값
     * @param rule      관련 추론 규칙 (docs/reasoning-rules.md 의 R 번호)
     */
    public Grader check(String id, Object predicted, Object actual, String rule) {
        items.add(new Item(id, predicted, actual, rule, predicted != null && Objects.equals(predicted, actual)));
        return this;
    }

    /** 소요시간 예측. 오차 범위는 max(50ms, 실제값의 25%). */
    public Grader checkMillis(String id, Long predictedMillis, long actualMillis, String rule) {
        boolean hit = predictedMillis != null
                && Math.abs(predictedMillis - actualMillis) <= Math.max(MIN_TOLERANCE_MS, actualMillis / 4);
        items.add(new Item(id, predictedMillis, actualMillis + "ms(±" + Math.max(MIN_TOLERANCE_MS, actualMillis / 4) + ")",
                rule, hit));
        return this;
    }

    /**
     * 정답을 테스트 코드에 노출하지 않는 채점 (코드 읽기 퀴즈용).
     * 예측은 {@link #normalize(String)} 후 SHA-256 으로 비교한다. 정답 해시는 {@link #sha256(String)} 으로 만든다.
     */
    public Grader checkHashed(String id, String predicted, String expectedSha256, String rule) {
        boolean hit = predicted != null && sha256(normalize(predicted)).equals(expectedSha256);
        items.add(new Item(id, predicted, hit ? predicted : "(정답 비공개: 코드를 다시 읽어라)", rule, hit));
        return this;
    }

    /** 소문자, 앞뒤 공백 제거, 연속 공백을 하나로, 따옴표 제거. */
    public static String normalize(String s) {
        return s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[\"'`]", "").replaceAll("\\s+", " ");
    }

    public static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 모든 항목을 ledger 에 기록하고, 하나라도 틀리거나 비어 있으면 AssertionError. */
    public void verify() {
        StringBuilder report = new StringBuilder();
        int hits = 0;
        int unanswered = 0;
        for (Item it : items) {
            if (it.predicted == null) {
                unanswered++;
                report.append(String.format("  [미작성] %-28s 예측을 먼저 작성하라 (규칙 %s)%n", it.id, it.rule));
                continue;
            }
            boolean firstAttempt = Ledger.recordFirstAttempt(it.id, String.valueOf(it.predicted), String.valueOf(it.actual), it.hit);
            if (it.hit) {
                hits++;
                report.append(String.format("  [적중]  %-28s %s%n", it.id, it.predicted));
            } else {
                report.append(String.format("  [오답]  %-28s 예측=%s, 실제=%s  → docs/reasoning-rules.md %s 를 다시 보라%s%n",
                        it.id, it.predicted, it.actual, it.rule, firstAttempt ? "" : " (첫 시도 기록은 이미 고정됨)"));
            }
        }
        String summary = String.format("예측 %d개 중 적중 %d, 오답 %d, 미작성 %d%n%s",
                items.size(), hits, items.size() - hits - unanswered, unanswered, report);
        System.out.println(summary);
        if (hits != items.size()) {
            throw new AssertionError(summary);
        }
    }
}
