package async.m05;

import java.util.List;

/**
 * 상품 상세 API 의 응답.
 *
 * @param product 상품 기본 정보 (필수)
 * @param price   가격 (필수)
 * @param stock   재고 (선택: 실패하면 {@link #UNKNOWN_STOCK})
 * @param reviews 리뷰 (선택: 실패하면 빈 목록)
 */
public record ProductDetail(String product, String price, String stock, List<String> reviews) {

    public static final String UNKNOWN_STOCK = "UNKNOWN";
}
