package async.m07;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import async.harness.Grader;
import async.harness.On;
import async.harness.Timed;
import async.m07.web.WebScenario;

/** m07 예측 채점 (6. 서블릿 경계). 실제 Tomcat(worker 2개)에 JDK HttpClient 로 요청한다. */
@Timeout(30)
@SpringBootTest(classes = WebScenario.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebPredictionTest {

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach
    void warmUp() throws Exception {
        WebScenario.THREADS.clear();
        get("/s/me-cf", null); // DispatcherServlet 초기화 등 첫 요청 비용을 미리 치른다
    }

    @Test
    void s17_joinVsReturnFuture() {
        long blocking = Timed.of(() -> fire(10, "/s/blocking")).millis();
        long async = Timed.of(() -> fire(10, "/s/async")).millis();
        new Grader()
                .checkMillis("m07.s17.blockingTotalMs", Predictions.S17_BLOCKING_TOTAL_MS, blocking, "R5,R7")
                .checkMillis("m07.s17.asyncTotalMs", Predictions.S17_ASYNC_TOTAL_MS, async, "R3,R7")
                .verify();
    }

    @Test
    void s18_whoWritesTheResponse() throws Exception {
        HttpResponse<String> r = get("/s/async", null);
        if (r.statusCode() != 200) {
            throw new AssertionError("시나리오 오류: " + r.statusCode());
        }
        new Grader()
                .check("m07.s18.work", Predictions.S18_WORK, classify("work"), "R2")
                .check("m07.s18.write", Predictions.S18_WRITE, classify("write"), "R1")
                .verify();
    }

    @Test
    void s19_asyncRequestTimeout() throws Exception {
        HttpResponse<String> r = get("/s/never", null);
        Thread.sleep(100);
        new Grader()
                .check("m07.s19.status", Predictions.S19_STATUS, r.statusCode(), "R10")
                .check("m07.s19.futureCancelled", Predictions.S19_FUTURE_CANCELLED,
                        WebScenario.ScenarioController.lastNever.isCancelled(), "R10")
                .verify();
    }

    @Test
    void s20_exceptionMapping() throws Exception {
        new Grader()
                .check("m07.s20.returnCfStatus", Predictions.S20_RETURN_CF_STATUS, get("/s/fail-cf", null).statusCode(), "R8")
                .check("m07.s20.joinStatus", Predictions.S20_JOIN_STATUS, get("/s/fail-join", null).statusCode(), "R8")
                .check("m07.s20.getStatus", Predictions.S20_GET_STATUS, get("/s/fail-get", null).statusCode(), "R8")
                .verify();
    }

    @Test
    void s21_securityContextAcrossAsyncReturnTypes() throws Exception {
        new Grader()
                .check("m07.s21.callableUser", Predictions.S21_CALLABLE_USER, get("/s/me-callable", "alice").body(), "R12")
                .check("m07.s21.cfUser", Predictions.S21_CF_USER, get("/s/me-cf", "alice").body(), "R12")
                .verify();
    }

    /** 스레드 이름 → On. Tomcat worker(http-nio-...)는 On.pool("tomcat"). */
    static On classify(String step) {
        String name = WebScenario.THREADS.get(step);
        if (name == null) {
            throw new AssertionError("단계 '" + step + "' 가 기록되지 않았다: " + WebScenario.THREADS);
        }
        if (name.startsWith("http-nio-")) {
            return On.pool("tomcat");
        }
        return On.pool(name.replaceFirst("-\\d+$", ""));
    }

    private List<Integer> fire(int n, String path) {
        List<CompletableFuture<HttpResponse<String>>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(http.sendAsync(request(path, null), HttpResponse.BodyHandlers.ofString()));
        }
        List<Integer> statuses = all.stream().map(f -> f.join().statusCode()).toList();
        if (statuses.stream().anyMatch(s -> s != 200)) {
            throw new AssertionError("시나리오 오류: " + statuses);
        }
        return statuses;
    }

    private HttpResponse<String> get(String path, String user) throws Exception {
        return http.send(request(path, user), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, String user) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        if (user != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":pw").getBytes(StandardCharsets.UTF_8)));
        }
        return b.build();
    }
}
