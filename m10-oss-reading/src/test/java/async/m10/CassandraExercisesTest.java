package async.m10;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.datastax.oss.driver.api.core.DriverTimeoutException;

import async.harness.BlockHoundSupport;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Timed;
import async.m10.cassandra.AsyncPaging;
import async.m10.cassandra.FakeAsyncPage;

/** E8 채점. */
@Timeout(30)
class CassandraExercisesTest {

    /** 256KB 스택 스레드에서 실행한다 (D1 과 같은 조건). */
    private static <T> T onSmallStack(java.util.function.Supplier<T> work) throws Exception {
        CompletableFuture<T> out = new CompletableFuture<>();
        Thread t = new Thread(null, () -> {
            try {
                out.complete(work.get());
            } catch (Throwable e) {
                out.completeExceptionally(e);
            }
        }, "request-handler", 256 * 1024);
        t.start();
        return out.get(20, TimeUnit.SECONDS);
    }

    @Test
    void e8_twentyThousandAlreadyCompletedPages() throws Exception {
        List<String> rows = onSmallStack(() -> {
            CompletionStage<List<String>> all = AsyncPaging.fetchAll(FakeAsyncPage.query(FakeAsyncPage.rows(20_000, 1), null));
            return all.handle((r, ex) -> {
                if (ex != null) {
                    throw new AssertionError("이미 완료된 페이지 20,000개에서 실패했다: " + Threads.unwrap(ex)
                            + " (이미 완료된 future 에 붙인 함수는 어느 스레드의 어느 스택에서 실행되는가? R1)", ex);
                }
                return r;
            }).toCompletableFuture().join();
        });
        assertThat(rows).hasSize(20_000);
        assertThat(rows.get(0)).isEqualTo("row-0");
        assertThat(rows.get(19_999)).isEqualTo("row-19999");
    }

    @Test
    void e8_asyncPages_noBlockingOnDriverThreads() throws Exception {
        BlockHoundSupport.reset();
        try (NamedPool io = Pools.single("s0-io")) {
            CompletableFuture<FakeAsyncPage<String>> firstPage = new CompletableFuture<>();
            Timed<CompletionStage<List<String>>> call = Timed.of(() -> AsyncPaging.fetchAll(firstPage));
            assertThat(call.millis()).as("fetchAll 은 즉시 반환해야 한다 (R3)").isLessThan(50);
            assertThat(call.value().toCompletableFuture()).as("첫 페이지가 오기 전에 완료될 수 없다").isNotDone();

            FakeAsyncPage.query(FakeAsyncPage.rows(2_000, 3), io).thenAccept(firstPage::complete);
            List<String> rows = call.value().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(rows).hasSize(6_000);
            assertThat(rows.get(5_999)).isEqualTo("row-5999");
        }
        assertThat(BlockHoundSupport.violations())
                .as("드라이버 I/O 스레드(s0-io)에서 blocking 이 감지됐다. 다음 페이지를 join/get 으로 기다리면 안 된다 (O4, R5)")
                .noneMatch(v -> v.threadName().startsWith("s0-io-"));
    }

    @Test
    void e8_failureOfAnyPage_failsWithOriginalCause() throws Exception {
        try (NamedPool io = Pools.single("s0-io")) {
            Throwable asyncError = AsyncPaging.fetchAll(FakeAsyncPage.query(FakeAsyncPage.rows(10, 2), io, 3))
                    .handle((r, ex) -> ex).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(Threads.unwrap(asyncError)).as("3번째 페이지 요청의 DriverTimeoutException 이 결과로 전달돼야 한다").isInstanceOf(DriverTimeoutException.class);
        }
        Throwable immediateError = AsyncPaging.fetchAll(FakeAsyncPage.query(FakeAsyncPage.rows(10, 2), null, 7))
                .handle((r, ex) -> ex).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(Threads.unwrap(immediateError)).isInstanceOf(DriverTimeoutException.class);
    }
}
