package async.m06;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.harness.StubHttpServer;
import async.harness.Timed;
import async.m06.support.Cpu;
import async.m06.support.IoThreadHttpClient;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class Ex2ProductPageTest {

    StubHttpServer server;
    IoThreadHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = StubHttpServer.start();
        client = IoThreadHttpClient.create();
        server.route("/warmup", 0, 200, "w");
        client.sendAsync(HttpRequest.newBuilder(server.uri("/warmup")).build(), BodyHandlers.ofString()).get(5, TimeUnit.SECONDS);
        server.route("/product", 100, 200, "P");
        server.route("/price", 200, 200, "1000");
        server.route("/stock", 300, 200, "S3");
        BlockHoundSupport.reset();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    private CompletableFuture<String> page() {
        return Ex2ProductPage.productPage(client, server.uri("/product"), server.uri("/price"), server.uri("/stock"));
    }

    private static String await(CompletableFuture<String> f) throws Exception {
        return TestSupport.await(f, 2);
    }

    @Test
    void combinesThreeCalls_inParallel_withoutBlocking() throws Exception {
        Timed<CompletableFuture<String>> call = TestSupport.invoke(this::page);
        assertThat(call.millis()).as("즉시 반환해야 한다 (R3, R5)").isLessThan(100);
        String result = await(call.value());
        assertThat(result).isEqualTo("P|1000|S3");
        assertThat(client.sendCalls()).as("blocking send() 금지 (R0)").isZero();
        assertThat(server.maxConcurrentRequests()).as("세 호출이 동시에 진행돼야 한다 (R7)").isEqualTo(3);
        assertThat(BlockHoundSupport.violations())
                .as("I/O 스레드(nonblocking-io) 위에서 blocking 이 일어났다. 콜백 안의 join/get 을 찾아라 (R1, R4, R5)")
                .isEmpty();
    }

    @Test
    void totalTime_isMax_notSum() throws Exception {
        Timed<CompletableFuture<String>> call = TestSupport.invoke(this::page);
        Timed<String> rest = Timed.run(() -> await(call.value()));
        assertThat(rest.value()).isEqualTo("P|1000|S3");
        assertThat(call.millis() + rest.millis())
                .as("총 시간은 max(100, 200, 300) ≈ 300ms 여야 한다. 600ms 근처라면 순차 실행이다 (R7)")
                .isBetween(280L, 480L);
    }

    @Test
    void manyPagesAtOnce_stayFast_andNonBlocking() throws Exception {
        List<CompletableFuture<String>> pages = new ArrayList<>();
        Timed<Integer> all = Timed.run(() -> {
            for (int i = 0; i < 30; i++) {
                pages.add(page());
            }
            for (CompletableFuture<String> p : pages) {
                assertThat(await(p)).isEqualTo("P|1000|S3");
            }
            return pages.size();
        });
        assertThat(all.millis())
                .as("30페이지 × 3호출 = 90개 요청이 모두 동시에 진행되면 ≈ 300ms (스레드 수와 무관해야 B 다)")
                .isLessThan(900);
        assertThat(client.sendCalls()).as("blocking send() 금지 (R0)").isZero();
        assertThat(BlockHoundSupport.violations()).as("I/O 스레드 위 blocking (R4, R5)").isEmpty();
    }

    @Test
    void non200_failsWithStatusAndPath() {
        server.route("/price", 50, 503, "unavailable");
        Throwable thrown = catchThrowable(() -> page().get(2, TimeUnit.SECONDS));
        assertThat(thrown).as("price 가 503 이면 결과 future 가 실패해야 한다. HttpClient 는 5xx 에도 정상 완료한다").isNotNull();
        Throwable cause = Cpu.unwrap(thrown);
        assertThat(cause)
                .as("원인은 IllegalStateException(\"HTTP 503 /price\") 이어야 한다 (R8)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503")
                .hasMessageContaining("/price");
    }
}
