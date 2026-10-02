package async.m10.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import com.datastax.oss.driver.api.core.AsyncPagingIterable;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;

/**
 * Cassandra 없이 DataStax 드라이버의 비동기 페이징을 흉내 내는 가짜 결과 페이지.
 *
 * <p>{@code AsyncResultSet} 은 {@code AsyncPagingIterable<Row, AsyncResultSet>} 이다. 여기서는 Row 대신 임의의 원소 타입 T 를 쓴다
 * (드라이버의 object mapper 가 쓰는 {@code MappedAsyncPagingIterable} 과 같은 모양). 계약은 드라이버와 같다.
 * <ul>
 *   <li>{@code currentPage()}: 이 페이지의 원소들. {@code hasMorePages()}: 다음 페이지가 있는가.</li>
 *   <li>{@code fetchNextPage()}: 다음 페이지를 <b>비동기로</b> 요청한다. 다음 페이지가 없으면 {@code IllegalStateException}.</li>
 * </ul>
 *
 * <p>완료 방식
 * <ul>
 *   <li>{@code io == null}: 다음 페이지 future 가 <b>이미 완료된 상태</b>로 반환된다(드라이버가 미리 받아 둔 페이지, 캐시 등).</li>
 *   <li>{@code io != null}: 다음 페이지 future 를 {@code io} 스레드가 나중에 완료한다(드라이버의 {@code s0-io-N} 스레드 흉내).</li>
 * </ul>
 */
public final class FakeAsyncPage<T> implements AsyncPagingIterable<T, FakeAsyncPage<T>> {

    private final List<List<T>> pages;
    private final int index;
    private final Executor io;
    private final int failAtPage;

    private FakeAsyncPage(List<List<T>> pages, int index, Executor io, int failAtPage) {
        this.pages = pages;
        this.index = index;
        this.io = io;
        this.failAtPage = failAtPage;
    }

    /** 첫 페이지 요청({@code session.executeAsync(...)} 흉내). failAtPage 번째(0부터) 페이지 요청은 DriverTimeoutException 으로 실패한다. -1 이면 실패 없음. */
    public static <T> CompletionStage<FakeAsyncPage<T>> query(List<List<T>> pages, Executor io, int failAtPage) {
        return load(pages, 0, io, failAtPage);
    }

    public static <T> CompletionStage<FakeAsyncPage<T>> query(List<List<T>> pages, Executor io) {
        return query(pages, io, -1);
    }

    /** "row-0", "row-1", ... 을 pageSize 개씩 나눈 pageCount 개 페이지. */
    public static List<List<String>> rows(int pageCount, int pageSize) {
        List<List<String>> pages = new ArrayList<>(pageCount);
        int n = 0;
        for (int p = 0; p < pageCount; p++) {
            List<String> page = new ArrayList<>(pageSize);
            for (int i = 0; i < pageSize; i++) {
                page.add("row-" + n++);
            }
            pages.add(page);
        }
        return pages;
    }

    private static <T> CompletionStage<FakeAsyncPage<T>> load(List<List<T>> pages, int index, Executor io, int failAtPage) {
        if (index == failAtPage) {
            CompletableFuture<FakeAsyncPage<T>> failed = new CompletableFuture<>();
            DriverTimeoutException timeout = new DriverTimeoutException("Query timed out after PT2S (page " + index + ")");
            if (io == null) {
                failed.completeExceptionally(timeout);
            } else {
                io.execute(() -> failed.completeExceptionally(timeout));
            }
            return failed;
        }
        FakeAsyncPage<T> page = new FakeAsyncPage<>(pages, index, io, failAtPage);
        if (io == null) {
            return CompletableFuture.completedFuture(page);
        }
        CompletableFuture<FakeAsyncPage<T>> later = new CompletableFuture<>();
        io.execute(() -> later.complete(page));
        return later;
    }

    @Override
    public Iterable<T> currentPage() {
        return pages.get(index);
    }

    @Override
    public int remaining() {
        return pages.get(index).size();
    }

    @Override
    public boolean hasMorePages() {
        return index + 1 < pages.size();
    }

    @Override
    public CompletionStage<FakeAsyncPage<T>> fetchNextPage() throws IllegalStateException {
        if (!hasMorePages()) {
            throw new IllegalStateException("No next page. Use #hasMorePages before calling this method to avoid this error.");
        }
        return load(pages, index + 1, io, failAtPage);
    }

    @Override
    public boolean wasApplied() {
        return true;
    }

    @Override
    public ColumnDefinitions getColumnDefinitions() {
        throw new UnsupportedOperationException("가짜 페이지에는 컬럼 정의가 없다");
    }

    @Override
    public ExecutionInfo getExecutionInfo() {
        throw new UnsupportedOperationException("가짜 페이지에는 실행 정보가 없다");
    }
}
