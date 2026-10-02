package async.m10.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import com.datastax.oss.driver.api.core.AsyncPagingIterable;

/**
 * E8. 비동기 페이징 유틸 (기여 시뮬레이션: 드라이버 위에 얹는 사내 DAO 라이브러리).
 *
 * <p>{@code AsyncResultSet}(= {@code AsyncPagingIterable<Row, AsyncResultSet>})에도 그대로 쓸 수 있는 시그니처다.
 * <pre>{@code
 * CompletionStage<List<Row>> all = AsyncPaging.fetchAll(session.executeAsync("SELECT * FROM orders WHERE ..."));
 * }</pre>
 */
public final class AsyncPaging {

    private AsyncPaging() {
    }

    /**
     * 모든 페이지의 원소를 순서대로 모은다.
     * <ol>
     *   <li><b>어떤 스레드도 막지 않는다</b>: {@code get/join} 으로 다음 페이지를 기다리지 않는다(드라이버 I/O 스레드에서 실행될 수 있다, O4).
     *       단, 이미 완료가 보장된 future 의 값을 꺼내는 것은 괜찮다(R5).</li>
     *   <li>페이지 future 가 미완료로 돌아오든 이미 완료된 상태로 돌아오든, 20,000 페이지를 처리할 수 있어야 한다.</li>
     *   <li>어느 페이지든 실패하면 결과는 <b>그 원인 예외</b>로 실패한다.</li>
     *   <li>즉시 future 를 반환한다. 새 스레드나 executor 를 만들지 않는다.</li>
     * </ol>
     */
    public static <T, S extends AsyncPagingIterable<T, S>> CompletionStage<List<T>> fetchAll(CompletionStage<S> firstPage) {
        // TODO(E8): 이미 완료된 페이지와 아직 안 온 페이지를 같은 방식으로 이어도 되는가? D1 의 결과를 근거로 두 경우의 처리 방식을 정한다. R1, R5, O4
        // SOLUTION-BEGIN
        CompletableFuture<List<T>> result = new CompletableFuture<>();
        drain(firstPage, new ArrayList<>(), result);
        return result;
        // SOLUTION-END
    }

    // SOLUTION-REMOVE-BEGIN
    private static <T, S extends AsyncPagingIterable<T, S>> void drain(CompletionStage<S> stage, List<T> rows, CompletableFuture<List<T>> result) {
        CompletableFuture<S> next = stage.toCompletableFuture();
        while (true) {
            if (!next.isDone()) {
                CompletableFuture<S> pending = next;
                // 나중에 io 스레드가 완료시키면 그 스레드의 새 스택에서 이어서 처리한다
                pending.whenComplete((page, error) -> drain(pending, rows, result));
                return;
            }
            S page;
            try {
                page = next.join(); // 이미 완료됨: 막지 않는다 (R5)
            } catch (CompletionException e) {
                result.completeExceptionally(e.getCause() != null ? e.getCause() : e);
                return;
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
                return;
            }
            page.currentPage().forEach(rows::add);
            if (!page.hasMorePages()) {
                result.complete(rows);
                return;
            }
            next = page.fetchNextPage().toCompletableFuture();
        }
    }
    // SOLUTION-REMOVE-END
}
