package async.m10.aws;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import async.harness.StubHttpServer;
import async.harness.ThreadRecorder;
import async.m10.Threads;

/**
 * 5장 AWS SDK for Java v2 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽기 가이드: {@code guides/05-aws-sdk-v2.md}
 *
 * <p>진짜 {@code DynamoDbAsyncClient}(Netty NIO HTTP 클라이언트)가 로컬 {@link StubHttpServer} 로 요청을 보낸다
 * ({@code endpointOverride}, 고정 자격증명). 스텁의 지연시간은 "원격 DynamoDB 가 느린 것"을 흉내 낸 것이다.
 */
public final class AwsScenarios {

    private AwsScenarios() {
    }

    /**
     * 여러 SDK 클라이언트가 공유하는 Netty HTTP 클라이언트(운영에서도 권장되는 형태).
     * {@code .httpClient(..)} 로 넘긴 HTTP 클라이언트는 SDK 클라이언트를 close 해도 닫히지 않는다.
     * (클라이언트마다 {@code httpClientBuilder} 로 만들면 close 할 때마다 이벤트 루프 그룹 종료를 ~2초 기다린다.)
     */
    private static final SdkAsyncHttpClient SHARED_HTTP = NettyNioAsyncHttpClient.builder().build();

    /** 공통 빌더: 로컬 엔드포인트 + 고정 자격증명 + 공유 Netty NIO 클라이언트. */
    public static DynamoDbAsyncClientBuilder localClient(URI endpoint) {
        return DynamoDbAsyncClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClient(SHARED_HTTP);
    }

    static CompletableFuture<GetItemResponse> getProduct(DynamoDbAsyncClient client, String id) {
        return client.getItem(b -> b.tableName("products").key(Map.of("id", AttributeValue.fromS(id))));
    }

    /**
     * A1. 기본 설정 클라이언트. 응답(200ms 뒤)이 오기 전에 붙인 {@code whenComplete}("callback")는 어느 스레드에서 실행되는가?
     */
    public static void a1(ThreadRecorder rec, URI endpoint) throws Exception {
        try (DynamoDbAsyncClient client = localClient(endpoint).build()) {
            getProduct(client, "1").whenComplete((r, ex) -> rec.mark("callback")).get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A2. "스레드 전환 비용을 줄이자"며 {@code FUTURE_COMPLETION_EXECUTOR} 를 {@code Runnable::run} 으로 바꿨다.
     * 같은 콜백("callback")은 이제 어느 스레드에서 실행되는가?
     */
    public static void a2(ThreadRecorder rec, URI endpoint) throws Exception {
        try (DynamoDbAsyncClient client = localClient(endpoint)
                .asyncConfiguration(b -> b.advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, Runnable::run))
                .build()) {
            getProduct(client, "1").whenComplete((r, ex) -> rec.mark("callback")).get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A3. 서버는 1초 뒤에 응답하고, 클라이언트는 {@code apiCallTimeout(200ms)} 이다.
     * 실패 원인 예외의 단순 클래스 이름은(반환값)? 그 실패를 받는 {@code whenComplete}("callback")는 어느 스레드에서 실행되는가?
     */
    public static String a3(ThreadRecorder rec, URI endpoint) throws Exception {
        try (DynamoDbAsyncClient client = localClient(endpoint)
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofMillis(200)).build())
                .build()) {
            return getProduct(client, "1")
                    .whenComplete((r, ex) -> rec.mark("callback"))
                    .handle(Threads::outcome)
                    .get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A4. 서버가 항상 500(InternalServerError)을 돌려준다. 재시도 전략은 {@code AwsRetryStrategy.standardRetryStrategy()} 이다.
     * 서버가 받은 요청은 모두 몇 번인가?
     */
    public static int a4(StubHttpServer server) throws Exception {
        int before = server.requestCount();
        try (DynamoDbAsyncClient client = localClient(server.uri(""))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(AwsRetryStrategy.standardRetryStrategy())
                        .build())
                .build()) {
            getProduct(client, "1").handle((r, ex) -> null).get(10, TimeUnit.SECONDS);
        }
        return server.requestCount() - before;
    }
}
