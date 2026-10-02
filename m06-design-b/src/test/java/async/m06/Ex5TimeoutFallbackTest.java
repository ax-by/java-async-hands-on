package async.m06;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import async.harness.BlockHoundSupport;
import async.harness.Sleep;
import async.harness.StubHttpServer;
import async.harness.Timed;
import async.m06.support.IoThreadHttpClient;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class Ex5TimeoutFallbackTest {

    static final String FALLBACK = "list-price";

    StubHttpServer server;
    IoThreadHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = StubHttpServer.start();
        client = IoThreadHttpClient.create();
        server.route("/warmup", 0, 200, "w");
        client.sendAsync(HttpRequest.newBuilder(server.uri("/warmup")).build(), BodyHandlers.ofString()).get(5, TimeUnit.SECONDS);
        BlockHoundSupport.reset();
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    private CompletableFuture<String> price(URI uri) {
        return Ex5TimeoutFallback.priceOrFallback(client, uri, Duration.ofMillis(200), FALLBACK);
    }

    @Test
    void fastSuccess_returnsBody() throws Exception {
        server.route("/price", 50, 200, "990");
        Timed<CompletableFuture<String>> call = TestSupport.invoke(() -> price(server.uri("/price")));
        assertThat(call.millis()).as("즉시 반환해야 한다 (R3, R5)").isLessThan(100);
        assertThat(call.value().get(2, TimeUnit.SECONDS)).isEqualTo("990");
        assertThat(client.sendCalls()).as("blocking send() 금지 (R0)").isZero();
        assertThat(BlockHoundSupport.violations()).as("I/O 스레드 위 blocking (R4)").isEmpty();
    }

    @Test
    void slowRemote_fallsBackOnTime_andAbortsTheExchange() throws Exception {
        server.route("/price", 1000, 200, "990");
        int handlerBefore = client.bodyHandlerCalls();
        long start = System.nanoTime();
        Timed<CompletableFuture<String>> call = TestSupport.invoke(() -> price(server.uri("/price")));
        assertThat(call.millis()).as("즉시 반환해야 한다. 응답이나 타임아웃을 호출 스레드에서 기다렸다 (R3, R5)").isLessThan(100);
        String v = TestSupport.await(call.value(), 2);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertThat(v).as("타임아웃이면 fallback (예외로 실패하면 안 된다)").isEqualTo(FALLBACK);
        assertThat(elapsed).as("200ms 타임아웃이므로 약 200ms 에 fallback 이 나와야 한다").isBetween(150L, 600L);

        Sleep.ms(Math.max(0, 1300 - elapsed)); // 서버가 응답했을 시점(1000ms) 이후까지 기다린다
        assertThat(client.bodyHandlerCalls() - handlerBefore)
                .as("타임아웃 후에도 HTTP 교환이 끝까지 진행됐다(응답 헤더를 받았다). orTimeout/completeOnTimeout 은 "
                        + "future 의 상태만 바꾼다 (R10). HttpClient 가 교환을 실제로 끊는 수단이 무엇인지, "
                        + "그 수단이 언제 무력해지는지 S12, S13 채점 결과와 README 5절에서 다시 확인하라")
                .isZero();
        assertThat(client.sendCalls()).as("blocking send() 금지 (R0)").isZero();
        assertThat(BlockHoundSupport.violations()).as("I/O 스레드 위 blocking (R4)").isEmpty();
    }

    @Test
    void non200_fallsBack() throws Exception {
        server.route("/price", 10, 503, "unavailable");
        assertThat(price(server.uri("/price")).get(2, TimeUnit.SECONDS))
                .as("HttpClient 는 503 에도 정상 완료한다. 상태 코드를 확인해 fallback 하라")
                .isEqualTo(FALLBACK);
    }

    @Test
    void connectionRefused_fallsBack() throws Exception {
        URI dead;
        try (StubHttpServer gone = StubHttpServer.start()) {
            dead = gone.uri("/price");
        }
        assertThat(price(dead).get(3, TimeUnit.SECONDS))
                .as("연결 실패(ConnectException)도 fallback 이어야 한다 (R9)")
                .isEqualTo(FALLBACK);
    }
}
