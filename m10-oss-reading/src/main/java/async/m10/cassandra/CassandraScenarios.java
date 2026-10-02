package async.m10.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import com.datastax.oss.driver.api.core.AsyncPagingIterable;

import async.m10.Threads;

/**
 * 6장 DataStax Java Driver 4 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽기 가이드: {@code guides/06-datastax-driver.md}
 */
public final class CassandraScenarios {

    private CassandraScenarios() {
    }

    /**
     * 흔히 보는 재귀 페이징. 매뉴얼 예제를 그대로 "모두 모으기"로 바꾼 모양이다.
     */
    static <T, S extends AsyncPagingIterable<T, S>> CompletionStage<List<T>> naiveFetchAll(CompletionStage<S> stage, List<T> acc) {
        return stage.thenCompose(page -> {
            page.currentPage().forEach(acc::add);
            return page.hasMorePages()
                    ? naiveFetchAll(page.fetchNextPage(), acc)
                    : CompletableFuture.completedFuture(acc);
        });
    }

    /**
     * D1. 위 {@code naiveFetchAll} 로 5,000 페이지 × 2 행을 모은다. 실행 스레드의 스택은 256KB 다.
     * <ul>
     *   <li>(a) {@code io == null}: 모든 페이지 future 가 <b>이미 완료된 상태</b>로 돌아온다.</li>
     *   <li>(b) {@code io} = 단일 스레드 {@code s0-io}: 페이지 future 를 io 스레드가 <b>나중에</b> 완료한다.</li>
     * </ul>
     * 각 경우의 결과는? ({@code "rows=<개수>"} 또는 원인 예외의 단순 클래스 이름)
     */
    public static String d1(Executor ioOrNull) throws Exception {
        CompletableFuture<String> outcome = new CompletableFuture<>();
        Thread caller = new Thread(null, () -> {
            CompletionStage<FakeAsyncPage<String>> first = FakeAsyncPage.query(FakeAsyncPage.rows(5_000, 2), ioOrNull);
            naiveFetchAll(first, new ArrayList<String>())
                    .whenComplete((rows, ex) -> outcome.complete(ex == null ? "rows=" + rows.size() : Threads.outcome(null, ex)));
        }, "request-handler", 256 * 1024);
        caller.start();
        return outcome.get(10, TimeUnit.SECONDS);
    }
}
