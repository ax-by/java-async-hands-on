package async.m10.aws;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executor;

import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * E7. DynamoDB 비동기 클라이언트 팩토리 (기여 시뮬레이션: 팀 공용 AWS 설정 모듈 리뷰 반영 PR).
 *
 * <p>리뷰 스레드 요약
 * <ul>
 *   <li>A: "SDK 기본 completion executor 는 우리 메트릭/MDC 데코레이터가 없는 풀이다. 앱이 관리하는 executor 를 쓰자."</li>
 *   <li>B: "스레드 전환이 아까우니 {@code Runnable::run} 으로 하자." → 이 제안은 <b>거절</b>한다(A2 와 가이드 5장의 Javadoc 인용 참고).</li>
 *   <li>C: "DynamoDB 기본 재시도는 환경(AWS_RETRY_MODE, ~/.aws/config)과 서비스 기본값에 따라 달라진다. 명시하자."</li>
 *   <li>D: "느린 응답 하나가 요청 스레드 예산을 다 쓰지 않도록 전체/시도 타임아웃을 건다."</li>
 * </ul>
 */
public final class ProductDynamoClients {

    private ProductDynamoClients() {
    }

    /**
     * 요구사항 (엔드포인트·리전·자격증명은 {@link AwsScenarios#localClient(URI)} 를 그대로 쓴다)
     * <ol>
     *   <li>사용자 future 는 {@code completionExecutor} 에서 완료된다(응답 콜백이 그 스레드에서 실행된다).</li>
     *   <li>재시도: {@code AwsRetryStrategy.standardRetryStrategy()} (최대 3회 시도).</li>
     *   <li>시도별 타임아웃 300ms, 전체 호출 타임아웃 1초.</li>
     * </ol>
     */
    public static DynamoDbAsyncClient create(URI endpoint, Executor completionExecutor) {
        // TODO(E7): asyncConfiguration 의 advancedOption, overrideConfiguration 의 retryStrategy / apiCallTimeout / apiCallAttemptTimeout (O1, O4)
        // SOLUTION-BEGIN
        return AwsScenarios.localClient(endpoint)
                .asyncConfiguration(b -> b.advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, completionExecutor))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(AwsRetryStrategy.standardRetryStrategy())
                        .apiCallAttemptTimeout(Duration.ofMillis(300))
                        .apiCallTimeout(Duration.ofSeconds(1))
                        .build())
                .build();
        // SOLUTION-END
    }
}
