package async.m07.web;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import async.m07.web.ProductExercise.ProductClient;
import async.m07.web.ProductExercise.ProductNotFoundException;
import async.m07.web.ProductExercise.ReviewClient;

/**
 * E5. 상품 상세 API. 상품과 리뷰 요약은 서로 독립인 blocking 호출이다(A 설계).
 *
 * <p>요구사항
 * <ol>
 *   <li>{@code GET /e5/products/{id}} → {@code "product:{id}|review:{id}"}</li>
 *   <li>두 호출을 orderExecutor 에서 병렬로 실행하고, <b>요청 스레드(Tomcat worker)를 기다리게 하지 않는다</b>.
 *       Tomcat worker 가 2개뿐이어도 동시 요청 6개가 ≈ 한 요청 시간에 끝나야 한다.</li>
 *   <li>클라이언트 호출 안에서 요청의 traceId 가 보여야 한다(로그 상관관계).</li>
 *   <li>전체 400ms 를 넘기면 504 Gateway Timeout, 상품이 없으면 404 Not Found.</li>
 * </ol>
 */
@RestController
public class ProductController {

    private final ProductClient products;
    private final ReviewClient reviews;
    private final ThreadPoolTaskExecutor orderExecutor;

    public ProductController(ProductClient products, ReviewClient reviews, ThreadPoolTaskExecutor orderExecutor) {
        this.products = products;
        this.reviews = reviews;
        this.orderExecutor = orderExecutor;
    }

    @GetMapping("/e5/products/{id}")
    public CompletableFuture<String> product(@PathVariable String id) {
        // TODO(E5): 시작을 먼저(둘 다), 합치기, 전체 타임아웃. join 하지 말고 future 를 반환 (R3, R7, R10)
        throw new UnsupportedOperationException("TODO");
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<String> notFound(ProductNotFoundException e) {
        // TODO(E5): 404. 반환한 future 가 CompletionException 으로 감싼 예외로 실패해도 이 핸들러가 불리는가? (R8, S20)
        throw new UnsupportedOperationException("TODO");
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<String> timeout(TimeoutException e) {
        // TODO(E5): 504
        throw new UnsupportedOperationException("TODO");
    }
}
