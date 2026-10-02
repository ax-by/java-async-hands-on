package async.m10;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import async.harness.On;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.StubHttpServer;
import async.harness.ThreadRecorder;
import async.harness.Timed;
import async.m10.aws.ProductDynamoClients;

/** E7 채점. 진짜 DynamoDbAsyncClient + 로컬 스텁 서버. */
@Timeout(30)
class AwsExercisesTest {

    StubHttpServer server;
    NamedPool completion;

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        completion = Pools.fixed("aws-cb", 2);
    }

    @AfterEach
    void tearDown() {
        server.close();
        completion.close();
    }

    private static java.util.concurrent.CompletableFuture<GetItemResponse> get(DynamoDbAsyncClient client) {
        return client.getItem(b -> b.tableName("products").key(Map.of("id", AttributeValue.fromS("1"))));
    }

    @Test
    void e7_responsesCompleteOnTheAppExecutor() throws Exception {
        server.route("/", 150, 200, "{\"Item\":{\"id\":{\"S\":\"1\"}}}");
        ThreadRecorder rec = ThreadRecorder.start();
        try (DynamoDbAsyncClient client = ProductDynamoClients.create(server.uri(""), completion)) {
            GetItemResponse r = get(client).whenComplete((v, ex) -> rec.mark("callback")).get(5, TimeUnit.SECONDS);
            assertThat(r.item()).containsKey("id");
        }
        assertThat(Threads.on(rec, "callback"))
                .as("응답 콜백은 앱이 준 completionExecutor(aws-cb)에서 실행돼야 한다 (O1, O4)")
                .isEqualTo(On.pool("aws-cb"));
    }

    @Test
    void e7_retriesAreExplicit() throws Exception {
        server.route("/", 10, 500, "{\"__type\":\"com.amazonaws.dynamodb.v20120810#InternalServerError\",\"message\":\"boom\"}");
        try (DynamoDbAsyncClient client = ProductDynamoClients.create(server.uri(""), completion)) {
            get(client).handle((v, ex) -> null).get(10, TimeUnit.SECONDS);
        }
        assertThat(server.requestCount())
                .as("재시도 전략을 standardRetryStrategy 로 명시해야 한다 (그 전략의 최대 시도 횟수만큼 요청이 도착한다)")
                .isEqualTo(AwsRetryStrategy.standardRetryStrategy().maxAttempts());
    }

    @Test
    void e7_attemptAndCallTimeouts() throws Exception {
        server.route("/", 3000, 200, "{}");
        try (DynamoDbAsyncClient client = ProductDynamoClients.create(server.uri(""), completion)) {
            Timed<Throwable> failed = Timed.of(() -> get(client).handle((v, ex) -> ex).join());
            assertThat(failed.value()).as("3초 걸리는 응답은 타임아웃으로 실패해야 한다").isNotNull();
            assertThat(Threads.unwrap(failed.value()))
                    .isInstanceOfAny(ApiCallTimeoutException.class, ApiCallAttemptTimeoutException.class);
            assertThat(failed.millis()).as("전체 호출 타임아웃 1초를 지켜야 한다 (실제 %dms)", failed.millis()).isBetween(250L, 1400L);
        }
        assertThat(server.requestCount())
                .as("시도별 타임아웃(300ms)이 있으면 1초 안에 재시도가 일어나 요청이 2번 이상 도착한다")
                .isGreaterThanOrEqualTo(2);
    }
}
