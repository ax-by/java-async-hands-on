package async.m09.minikv;

/** 요청이 설정한 시간 안에 끝나지 않았다. cause 는 {@link java.util.concurrent.TimeoutException} 이다. */
public class MiniKvTimeoutException extends MiniKvException {

    private static final long serialVersionUID = 1L;

    public MiniKvTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
