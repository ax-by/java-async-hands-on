package async.m05;

/**
 * 상품 상세 집계의 도메인 예외. 컨트롤러는 이 예외 하나만 보고 응답 코드를 정한다
 * (예: {@code @ExceptionHandler} 에서 PRODUCT_UNAVAILABLE → 404/503, TIMEOUT → 504).
 *
 * <p>{@link #getCause()} 는 <b>원래 예외</b>여야 한다. {@code CompletionException} 껍데기가 아니라
 * 다운스트림이 실제로 던진 예외나 {@code TimeoutException} 이다 (R8).
 */
public final class ProductDetailException extends RuntimeException {

    public enum Reason {
        /** 필수 소스 product 실패 */
        PRODUCT_UNAVAILABLE,
        /** 필수 소스 price 실패 */
        PRICE_UNAVAILABLE,
        /** 전체 타임아웃(300ms) 초과 */
        TIMEOUT
    }

    private final Reason reason;

    public ProductDetailException(Reason reason, Throwable cause) {
        super(reason + ": " + cause, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
