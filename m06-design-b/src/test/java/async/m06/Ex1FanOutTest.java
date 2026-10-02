package async.m06;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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
import async.m06.support.IoThreadHttpClient;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class Ex1FanOutTest {

    StubHttpServer server;
    IoThreadHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = StubHttpServer.start();
        client = IoThreadHttpClient.create();
        // 예열: 첫 요청의 클래스 로딩/셀렉터 시작 비용이 측정에 섞이지 않게 한다
        server.route("/warmup", 0, 200, "w");
        client.sendAsync(HttpRequest.newBuilder(server.uri("/warmup")).build(), BodyHandlers.ofString()).get(5, TimeUnit.SECONDS);
        BlockHoundSupport.reset();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    @Test
    void hundredCalls_concurrently_withoutHoldingThreads() throws Exception {
        int n = 100;
        List<URI> uris = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            server.route("/items/" + i, 200, 200, "item-" + i);
            uris.add(server.uri("/items/" + i));
            expected.add("item-" + i);
        }
        int asyncBefore = client.sendAsyncCalls();

        Timed<CompletableFuture<List<String>>> call = TestSupport.invoke(() -> Ex1FanOut.fetchAll(client, uris));
        assertThat(call.millis())
                .as("fetchAll 은 즉시 반환해야 한다. 호출 스레드에서 응답을 기다렸다 (R3, R5)")
                .isLessThan(150);
        Timed<List<String>> total = Timed.run(() -> TestSupport.await(call.value(), 5));

        assertThat(total.value()).as("응답 본문을 uris 순서대로 담아야 한다").isEqualTo(expected);
        assertThat(client.sendCalls())
                .as("blocking send() 를 썼다. I/O 동안 스레드를 점유하므로 B 가 아니다 (R0)")
                .isZero();
        assertThat(client.sendAsyncCalls() - asyncBefore).as("요청마다 sendAsync 를 한 번씩 호출해야 한다").isEqualTo(n);
        assertThat(server.maxConcurrentRequests())
                .as("서버가 동시에 받은 요청이 너무 적다. 모두 먼저 시작해야 한다 (R7)")
                .isGreaterThanOrEqualTo(90);
        assertThat(call.millis() + total.millis())
                .as("100개 × 200ms 가 동시에 진행되면 총 시간 ≈ 200ms 여야 한다 (R7)")
                .isLessThan(700);
        assertThat(BlockHoundSupport.violations())
                .as("I/O 스레드(nonblocking-io) 위에서 blocking 이 일어났다. 콜백 안에서 미완료 future 를 기다렸는가? (R1, R4, R5)")
                .isEmpty();
    }

    @Test
    void preservesOrder_evenWhenLaterCallsFinishFirst() throws Exception {
        server.route("/slow", 300, 200, "slow");
        server.route("/mid", 150, 200, "mid");
        server.route("/fast", 0, 200, "fast");
        List<String> result = TestSupport.await(TestSupport.invoke(() -> Ex1FanOut.fetchAll(client,
                List.of(server.uri("/slow"), server.uri("/mid"), server.uri("/fast")))).value(), 5);
        assertThat(result).as("완료 순서가 아니라 uris 순서여야 한다 (R13: allOf + join)").containsExactly("slow", "mid", "fast");
        assertThat(BlockHoundSupport.violations()).as("I/O 스레드 위 blocking (R4, R5)").isEmpty();
    }
}
