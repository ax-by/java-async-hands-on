package async.m07;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import async.harness.Timed;
import async.m07.web.ProductExercise;

/** m07 구현 채점 E5 (E2 가 먼저 구현되어 있어야 한다). 실제 Tomcat(worker 2개). */
@Timeout(30)
@SpringBootTest(classes = ProductExercise.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebExerciseTest {

    @LocalServerPort int port;
    @Autowired ProductExercise.ProductClient products;
    @Autowired ProductExercise.ReviewClient reviews;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach
    void warmUp() throws Exception {
        get("/e5/products/warmup", "t-warmup");
        products.seen.clear();
        reviews.seen.clear();
    }

    HttpRequest request(String path, String traceId) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Trace-Id", traceId).timeout(Duration.ofSeconds(10)).build();
    }

    HttpResponse<String> get(String path, String traceId) throws Exception {
        return http.send(request(path, traceId), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void e5_ok_andTraceIdReachesClients() throws Exception {
        HttpResponse<String> r = get("/e5/products/p1", "t-e5");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("product:p1|review:p1");
        assertThat(products.seen).as("상품 API 는 orderExecutor(order-*)에서, 요청의 traceId 를 보며 호출돼야 한다 (R12)")
                .allMatch(s -> s.startsWith("order-") && s.endsWith(" t-e5")).isNotEmpty();
        assertThat(reviews.seen).as("리뷰 API 도 마찬가지").allMatch(s -> s.startsWith("order-") && s.endsWith(" t-e5")).isNotEmpty();
    }

    @Test
    void e5_requestThreadsAreReleased() {
        Timed<List<Integer>> t = Timed.of(() -> {
            List<CompletableFuture<HttpResponse<String>>> all = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                all.add(http.sendAsync(request("/e5/products/p" + i, "t-" + i), HttpResponse.BodyHandlers.ofString()));
            }
            return all.stream().map(f -> f.join().statusCode()).toList();
        });
        assertThat(t.value()).allMatch(s -> s == 200);
        assertThat(t.millis())
                .as("Tomcat worker 2개로 동시 요청 6개: 컨트롤러가 join 하면 ≈600ms(3라운드 × 200ms), future 를 반환하면 ≈200ms (R7)")
                .isLessThan(450);
    }

    @Test
    void e5_notFoundIs404() throws Exception {
        assertThat(get("/e5/products/missing", "t-404").statusCode())
                .as("ProductNotFoundException → 404 (Spring 은 반환된 CF 의 CompletionException 을 벗겨서 핸들러를 찾는다, R8)")
                .isEqualTo(404);
    }

    @Test
    void e5_timeoutIs504() throws Exception {
        Timed<Integer> t = Timed.of(() -> {
            try {
                return get("/e5/products/slow", "t-504").statusCode();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(t.value()).as("전체 400ms 타임아웃 → 504. (orTimeout 없이 MVC 타임아웃에 맡기면 503) (R10)").isEqualTo(504);
        assertThat(t.millis()).as("400ms 근처에서 응답해야 한다").isLessThan(1000);
    }
}
