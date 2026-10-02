package async.m09.minikv;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import async.m09.util.Stages;

/**
 * minikv 의 예외 정책(L6). 반환하는 stage 를 실패시킬 때와 sync API 가 예외를 던질 때 모두 이 변환을 거친다.
 *
 * <pre>
 * 공개 계약 (README 와 MiniKvClient Javadoc 에 문서화)
 *  - 반환된 stage 의 실패 원인은 항상 MiniKvException 계열 또는 CancellationException(사용자가 취소한 경우)이다.
 *  - 그 예외는 <b>감싸지 않은 채</b> 전달된다: stage 에 바로 붙인 exceptionally/handle/whenComplete 는 MiniKvException 을 받는다.
 *  - join() 은 CompletionException(원인), get() 은 ExecutionException(원인) 으로 감싼다. 이것은 JDK 의 계약이라 피할 수 없다.
 *  - sync API(get) 는 MiniKvException 을 <b>그대로</b> 던진다.
 * </pre>
 */
public final class Errors {

    private Errors() {
    }

    /**
     * E3. 내부에서 생긴 실패를 공개 예외로 바꾼다.
     *
     * <ol>
     *   <li>먼저 CompletionException/ExecutionException 껍질을 벗긴다({@link Stages#unwrap}).</li>
     *   <li>{@link MiniKvException}(하위 타입 포함)과 {@link CancellationException} 은 <b>같은 객체</b>를 그대로 반환한다.</li>
     *   <li>{@link TimeoutException} 은 {@link MiniKvTimeoutException}(cause = 원래 TimeoutException)으로 바꾼다.</li>
     *   <li>그 밖의 모든 것(IOException, NPE ...)은 {@link MiniKvException}(cause = 원래 예외)으로 바꾼다.</li>
     * </ol>
     */
    public static RuntimeException toPublic(Throwable failure) {
        // TODO(E3): unwrap 후 instanceof 로 분기한다. 이미 공개 예외면 다시 감싸지 않는다 (L6, R8)
        throw new UnsupportedOperationException("TODO");
    }
}
