package async.m11.p3;

/** 환율 API 가 해당 통화쌍 환율을 줄 수 없다. (주어진 타입) */
public class RateUnavailableException extends RuntimeException {

    public RateUnavailableException(String message) {
        super(message);
    }
}
