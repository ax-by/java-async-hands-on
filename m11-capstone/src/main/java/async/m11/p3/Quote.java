package async.m11.p3;

/** 송금 견적. quoteId 는 저장 후에만 있다. (주어진 타입) */
public record Quote(String customerId, String rate, String fee, String quoteId) {
}
