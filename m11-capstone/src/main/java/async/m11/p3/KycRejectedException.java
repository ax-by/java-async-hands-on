package async.m11.p3;

/** 고객 확인(KYC)이 승인되지 않았다. (주어진 타입) */
public class KycRejectedException extends RuntimeException {

    public KycRejectedException(String customerId, Throwable cause) {
        super("KYC 미승인: " + customerId, cause);
    }
}
