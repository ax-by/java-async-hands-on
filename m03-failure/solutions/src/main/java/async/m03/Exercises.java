package async.m03;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import async.harness.FakeBlockingService;

/**
 * m03 구현 실습. 각 메서드의 TODO 를 구현한다. 채점: ExercisesTest
 * (E4 는 별도 파일 {@link Java8Timeouts} 에 있다)
 *
 * <p>공통 요구사항: 모든 메서드는 호출 즉시 future 를 반환한다. 메서드 본문에서 join/get 으로 기다리지 않는다.
 */
public final class Exercises {

    private Exercises() {
    }

    /**
     * E1. CompletionException 과 ExecutionException 껍질을 모두 벗겨 근본 원인을 돌려준다.
     * <ul>
     *   <li>껍질이 여러 겹이어도 모두 벗긴다(이미 껍질인 예외를 {@code throw new CompletionException(ex)} 로 다시 던지면 두 겹이 된다).</li>
     *   <li>cause 가 없는 껍질은 그대로 돌려준다. null 이면 null.</li>
     *   <li>다른 예외(예: IllegalStateException)의 cause 는 벗기지 않는다. 그것은 도메인 예외의 일부다.</li>
     * </ul>
     */
    public static Throwable unwrap(Throwable t) {
        // TODO(E1): while 루프. 어떤 타입만 벗겨야 하는가? (R8)
        // SOLUTION-BEGIN
        Throwable cur = t;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException) && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
        // SOLUTION-END
    }

    /**
     * E2. 근본 원인이 {@code type} 인 실패만 {@code fallback} 값으로 복구한다.
     * <ul>
     *   <li>supplyAsync 에서 던진 예외(감싸짐)와 completeExceptionally 로 전달된 예외(안 감싸짐) 모두에서 동작해야 한다.</li>
     *   <li>다른 실패는 그대로 전파한다. 이때 join() 이 던지는 CompletionException 의 getCause() 가 <b>원래 예외</b>여야 한다
     *       (두 겹으로 감싸면 안 된다. 호출자가 getCause() 한 번으로 판별하는 코드를 깨뜨린다).</li>
     *   <li>성공은 그대로 통과시킨다.</li>
     * </ul>
     */
    public static <T> CompletableFuture<T> fallbackOn(CompletableFuture<T> source, Class<? extends Throwable> type, T fallback) {
        // TODO(E2): exceptionally + unwrap. 다시 던질 때 이미 CompletionException 이면 그대로 던진다 (R8, R9)
        // SOLUTION-BEGIN
        return source.exceptionally(ex -> {
            if (type.isInstance(unwrap(ex))) {
                return fallback;
            }
            throw ex instanceof CompletionException ? (CompletionException) ex : new CompletionException(ex);
        });
        // SOLUTION-END
    }

    /**
     * E3. source 가 timeoutMillis 안에 끝나지 않으면 fallback 으로 완료되는 <b>새</b> future 를 반환한다.
     * <ul>
     *   <li><b>source 자체를 완료시키면 안 된다.</b> source 는 다른 곳(캐시 갱신 등)에서도 쓰고 있다.
     *       S11 에서 completeOnTimeout 이 무엇을 바꿨는지 떠올려라.</li>
     *   <li>시간 안에 끝나면 source 의 결과(성공 또는 실패)를 그대로 따른다.</li>
     *   <li>타임아웃 뒤에 source 가 <b>성공</b>하면, 그 값은 버려지지만 onLateResult 로 한 번 알린다(메트릭/로그용).
     *       시간 안에 끝난 경우에는 onLateResult 를 호출하지 않는다.</li>
     * </ul>
     */
    public static <T> CompletableFuture<T> withTimeoutFallback(CompletableFuture<T> source, long timeoutMillis, T fallback,
                                                               Consumer<T> onLateResult) {
        // TODO(E3): 결과용 future 를 따로 만든다. complete 는 처음 한 번만 이기고, 진 쪽은 false 를 돌려준다 (R10, R6)
        // SOLUTION-BEGIN
        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete((v, ex) -> {
            if (ex != null) {
                result.completeExceptionally(ex);
            } else if (!result.complete(v)) {
                onLateResult.accept(v);
            }
        });
        result.completeOnTimeout(fallback, timeoutMillis, TimeUnit.MILLISECONDS);
        return result;
        // SOLUTION-END
    }

    /**
     * E5. blocking 호출을 pool 에서 실행하고, 반환한 future 가 <b>취소되거나 실패로 끝나면(타임아웃 포함)</b>
     * 실행 중인 호출을 실제로 interrupt 해서 스레드를 돌려받는다.
     * <ul>
     *   <li>호출자가 반환값에 {@code cancel(true)} 를 하든 {@code orTimeout(...)} 을 걸든 동작해야 한다.</li>
     *   <li>정상 완료되면 interrupt 하지 않는다.</li>
     * </ul>
     * supplyAsync 가 돌려준 future 를 cancel 하는 것으로 충분한가? (R10)
     */
    public static CompletableFuture<String> interruptibleCall(FakeBlockingService service, String key, ExecutorService pool) {
        // TODO(E5): pool.submit 이 돌려주는 Future 를 쥐고 있다가, 결과 future 가 실패로 끝나면 cancel(true) (R10)
        // SOLUTION-BEGIN
        CompletableFuture<String> result = new CompletableFuture<>();
        Future<?> task = pool.submit(() -> {
            try {
                result.complete(service.call(key));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        result.whenComplete((v, ex) -> {
            if (ex != null) {
                task.cancel(true);
            }
        });
        return result;
        // SOLUTION-END
    }

    /**
     * E6. source 의 결과를 로그로 남기되 <b>결과는 바꾸지 않는</b> future 를 반환한다.
     * <ul>
     *   <li>성공이면 {@code "OK <값>"}, 실패면 {@code "FAIL <근본 원인의 단순 클래스 이름>"} 을 logger 에 한 번 넘긴다.</li>
     *   <li>반환 future 의 결과는 source 와 같아야 한다. 성공은 같은 값, 실패는 같은 근본 원인.</li>
     *   <li><b>logger 자체가 예외를 던져도</b> 결과가 바뀌면 안 된다(R9). 로그 장애가 결제 실패가 되면 안 된다.</li>
     * </ul>
     */
    public static <T> CompletableFuture<T> logged(CompletableFuture<T> source, Consumer<String> logger) {
        // TODO(E6): 관찰용 API 는 무엇인가? 그 액션이 던지면 어떻게 되는가? (R9)
        // SOLUTION-BEGIN
        return source.whenComplete((v, ex) -> {
            try {
                logger.accept(ex == null ? "OK " + v : "FAIL " + unwrap(ex).getClass().getSimpleName());
            } catch (RuntimeException logFailure) {
                // 로그 실패는 결과에 영향을 주지 않는다 (실무에서는 여기서 최소한 stderr 로라도 남긴다)
            }
        });
        // SOLUTION-END
    }

    /**
     * E7. 상품 상세 화면. 상품 정보(products)는 <b>필수</b>, 리뷰(reviews)는 <b>선택</b>이다.
     * <ul>
     *   <li>둘은 독립이므로 io 에서 동시에 조회한다. 결과는 {@code "<상품>|<리뷰>"} (예: "product:p1|reviews:p1").</li>
     *   <li>리뷰가 실패하거나 reviewBudgetMillis 안에 오지 않으면 리뷰 자리에 {@code "no-reviews"} 를 쓴다.</li>
     *   <li>상품 조회가 실패하면 <b>전체가 실패</b>해야 한다(근본 원인은 상품 쪽 예외). 리뷰 폴백이 상품 실패를 삼키면 안 된다.</li>
     * </ul>
     */
    public static CompletableFuture<String> productDetail(FakeBlockingService products, FakeBlockingService reviews,
                                                          String id, Executor io, long reviewBudgetMillis) {
        // TODO(E7): 복구 단계를 "선택 분기에만" 붙인다. 위치가 의미를 바꾼다 (R9, R10, R7)
        // SOLUTION-BEGIN
        CompletableFuture<String> product = CompletableFuture.supplyAsync(() -> products.call(id), io);
        CompletableFuture<String> review = CompletableFuture.supplyAsync(() -> reviews.call(id), io)
                .completeOnTimeout("no-reviews", reviewBudgetMillis, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> "no-reviews");
        return product.thenCombine(review, (p, r) -> p + "|" + r);
        // SOLUTION-END
    }
}
