package async.m08.probe;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import async.harness.BlockHoundSupport;
import async.m08.Symptom;

/**
 * 측정값을 {@link Symptom} 으로 분류한다. 증상 테스트와 수정 테스트가 <b>같은 측정·같은 기준</b>을 쓴다.
 *
 * <p>각 판정 메서드는 측정값과 기준을 증거 문자열로 남긴다. 기준을 넘은 항목만 증상으로 표시된다.
 * 여러 증상이 표시되면 {@link Symptom} 선언 순서상 앞의 것이 최종 증상이다(EnumMap 순서).
 */
public final class Diagnosis {

    /** 이벤트 루프 lag 기준 (ms). */
    public static final long LAG_MS = 100;
    /** future 를 반환하는 메서드가 호출 스레드를 막았다고 볼 기준 (ms). */
    public static final long BLOCKED_MS = 100;
    /** 무관한 commonPool 작업의 시작 지연 기준 (ms). */
    public static final long COMMON_POOL_DELAY_MS = 150;
    /** blocking 시간을 넘는 추가 지연 기준 (ms). */
    public static final long HOP_DELAY_MS = 100;

    private final String caseId;
    private final Map<Symptom, String> flagged = new EnumMap<>(Symptom.class);
    private final List<String> evidence = new ArrayList<>();

    public Diagnosis(String caseId) {
        this.caseId = caseId;
    }

    /** 판정에 쓰지 않는 참고 측정값. */
    public Diagnosis measured(String format, Object... args) {
        evidence.add("       " + String.format(format, args));
        return this;
    }

    /** condition 이 참이면 symptom 으로 표시한다. */
    public Diagnosis flagIf(boolean condition, Symptom symptom, String format, Object... args) {
        String line = String.format(format, args);
        evidence.add((condition ? "[증상] " : "[정상] ") + line);
        if (condition) {
            flagged.putIfAbsent(symptom, line);
        }
        return this;
    }

    /** 이벤트 루프 스레드 위의 BlockHound 위반과 lag 로 EVENT_LOOP_STALL 을 판정한다. */
    public Diagnosis eventLoop(List<BlockHoundSupport.Violation> violations, long maxLagMillis) {
        List<BlockHoundSupport.Violation> onLoop = violations.stream()
                .filter(v -> v.threadName().startsWith("event-loop-"))
                .collect(Collectors.toList());
        String sample = onLoop.stream().limit(2).map(Object::toString).collect(Collectors.joining("; "));
        return flagIf(!onLoop.isEmpty() || maxLagMillis >= LAG_MS, Symptom.EVENT_LOOP_STALL,
                "이벤트 루프 위 blocking %d건%s, 최대 lag %dms (기준: 1건 이상 또는 lag ≥ %dms)",
                onLoop.size(), sample.isEmpty() ? "" : " [" + sample + "]", maxLagMillis, LAG_MS);
    }

    /** future 를 반환하는 메서드가 반환되기까지 걸린 시간으로 CALLER_THREAD_BLOCKED 를 판정한다. */
    public Diagnosis callerBlocked(long returnMillis) {
        return flagIf(returnMillis >= BLOCKED_MS, Symptom.CALLER_THREAD_BLOCKED,
                "메서드가 future 를 반환하기까지 %dms (기준: ≥ %dms 면 호출 스레드를 막은 것)", returnMillis, BLOCKED_MS);
    }

    /** 결과가 제한 시간 안에 완료됐는지로 NEVER_COMPLETES 를 판정한다. */
    public Diagnosis completed(int completedCount, int total, long boundMillis) {
        return flagIf(completedCount < total, Symptom.NEVER_COMPLETES,
                "제한 시간 %dms 안에 완료된 결과 %d/%d", boundMillis, completedCount, total);
    }

    public Symptom symptom() {
        return flagged.isEmpty() ? Symptom.NO_BUG : flagged.keySet().iterator().next();
    }

    public String report() {
        StringBuilder sb = new StringBuilder("── " + caseId + " 측정 결과 → " + symptom() + "\n");
        for (String line : evidence) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return report();
    }
}
