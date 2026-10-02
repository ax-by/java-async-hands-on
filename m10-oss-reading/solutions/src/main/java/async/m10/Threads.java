package async.m10;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import async.harness.On;
import async.harness.ThreadRecorder;

/**
 * m10 공용 도구. 라이브러리 스레드 이름을 예측 가능한 {@link On} 값으로 바꾼다.
 *
 * <p>라이브러리 스레드 이름에는 번호가 여러 개 붙는다(예: {@code shop-io-4-1}, {@code report | client-1}).
 * 번호는 실행마다 달라지므로 <b>끝에 붙은 "-숫자"를 모두 떼어 낸 이름</b>으로 예측한다.
 *
 * <pre>{@code
 * shop-io-4-1                       → On.pool("shop-io")
 * report | client-1                 → On.pool("report | client")
 * 테스트 메서드를 실행하는 스레드      → On.caller()
 * ForkJoinPool.commonPool-worker-3  → On.commonPool()
 * }</pre>
 */
public final class Threads {

    private Threads() {
    }

    /** rec 에 기록된 step 의 실행 스레드를 분류한다. */
    public static On on(ThreadRecorder rec, String step) {
        On basic = rec.on(step);
        if (basic.equals(On.caller()) || basic.equals(On.commonPool()) || basic.equals(On.timeoutScheduler())
                || basic.equals(On.freshThread()) || basic.equals(On.virtualThread())) {
            return basic;
        }
        return On.pool(prefix(rec.threadName(step)));
    }

    /** 스레드 이름 끝의 "-숫자" 묶음을 모두 뗀다. */
    public static String prefix(String threadName) {
        return threadName.replaceFirst("(-\\d+)+$", "");
    }

    /** CompletionException / ExecutionException 껍질을 벗긴다 (R8). */
    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** 결과를 "value:값" 또는 원인 예외의 단순 클래스 이름으로 요약한다. 예측 비교용. */
    public static String outcome(Object value, Throwable error) {
        return error == null ? "value:" + value : unwrap(error).getClass().getSimpleName();
    }
}
