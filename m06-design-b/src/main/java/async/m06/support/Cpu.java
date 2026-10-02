package async.m06.support;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** 실습용 작은 도구. <b>학습자가 수정하지 않는다.</b> */
public final class Cpu {

    private Cpu() {
    }

    /**
     * CPU 를 millis 동안 바쁘게 돌린다(sleep 이 아니다). JSON 직렬화, 템플릿 렌더링, 암호화 같은 "무거운 계산"의 대역.
     * BlockHound 는 이것을 blocking 으로 감지하지 <b>못한다</b>. 하지만 이벤트 루프를 점유하는 것은 똑같다.
     */
    public static long burn(long millis) {
        long end = System.nanoTime() + millis * 1_000_000;
        long x = 0;
        while (System.nanoTime() < end) {
            x += System.nanoTime() % 7;
        }
        return x;
    }

    /** R8: CompletionException/ExecutionException 을 벗겨 원인을 꺼낸다. */
    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
