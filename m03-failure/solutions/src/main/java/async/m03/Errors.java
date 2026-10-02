package async.m03;

/** m03 에서 쓰는 도메인 예외들. 실무의 "비즈니스 예외"(폴백 대상)와 "인프라 예외"를 흉내 낸다. */
public final class Errors {

    private Errors() {
    }

    /** 재고 없음. 비즈니스적으로 예상된 실패라서 "품절" 같은 대체 응답으로 바꿀 수 있다. */
    public static final class OutOfStockException extends RuntimeException {
        public OutOfStockException(String sku) {
            super("재고 없음: " + sku);
        }
    }

    /** 결제 거절. */
    public static final class PaymentDeclinedException extends RuntimeException {
        public PaymentDeclinedException(String reason) {
            super("결제 거절: " + reason);
        }
    }

    /** 상품 없음. 필수 정보라서 폴백하면 안 된다. */
    public static final class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(String id) {
            super("상품 없음: " + id);
        }
    }

    /** 외부 API 장애(5xx, 연결 실패). */
    public static final class ExternalApiException extends RuntimeException {
        public ExternalApiException(String message) {
            super(message);
        }
    }
}
