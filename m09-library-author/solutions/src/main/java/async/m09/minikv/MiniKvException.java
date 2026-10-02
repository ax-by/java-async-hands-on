package async.m09.minikv;

/**
 * minikv 의 모든 실패는 이 타입(또는 하위 타입)이다. 사용자는 이 계층 하나만 잡으면 된다.
 *
 * <p>unchecked 인 이유: CompletionStage 의 콜백(Function, BiFunction ...)은 checked 예외를 던질 수 없으므로,
 * 비동기 API 의 도메인 예외는 RuntimeException 계열로 두는 것이 관례다(Lettuce RedisException, AWS SdkException 등).
 */
public class MiniKvException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MiniKvException(String message) {
        super(message);
    }

    public MiniKvException(String message, Throwable cause) {
        super(message, cause);
    }
}
