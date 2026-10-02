# 5장. AWS SDK for Java v2 (2.55.10): I/O 스레드를 지키려고 한 번 더 넘기는 SDK

> 예측: A1~A4, Q_AWS_* · 구현: E7 · 규칙: O1, O4, R1, R3, R8, R10, R11
> 소스 루트(모두 2.55.10 sources jar): `sdk-core`, `netty-nio-client`, `aws-core`, `dynamodb`, `utils`, `retries`. 경로는 `software/amazon/awssdk/` 기준이다(아래에서는 생략).

## 0. 이 장에서 답할 질문

- 응답은 Netty 이벤트 루프가 받는다. 그런데 사용자 future도 이벤트 루프가 완료시키는가? (4장 Lettuce와 비교)
- `FUTURE_COMPLETION_EXECUTOR`는 무엇이고 기본값은? `Runnable::run`으로 바꾸면?
- 타임아웃(`apiCallTimeout`)으로 끝날 때는 누가 완료시키는가? 진행 중인 HTTP 요청은 어떻게 되는가?
- 재시도 대기 동안 스레드를 점유하는가?

## 1. 읽기 과제

| # | 위치 | 찾을 것 |
|---|---|---|
| 1 | `core/client/config/SdkAdvancedAsyncClientOption.java:37-65` | `FUTURE_COMPLETION_EXECUTOR` Javadoc: 기본값, I/O 스레드에서 blocking하면 생기는 일, `Runnable::run`에 대한 입장(53-55행), `@SdkAdvancedApi`(57-63행) |
| 2 | `core/client/builder/SdkDefaultClientBuilder.java:334-340` (`finalizeAsyncConfiguration`) | 설정이 없으면 어떻게 채우는가? |
| 3 | `core/client/builder/SdkDefaultClientBuilder.java:526-543` (`resolveAsyncFutureCompletionExecutor`) | 코어/최대 스레드, 큐 종류와 용량 → Q_AWS_QUEUE_CAPACITY, 스레드 이름 접두사, 거절 정책 |
| 4 | `utils/ThreadFactoryBuilder.java:75` + `utils/NamedThreadFactory.java:39` | 스레드 이름 형식 → A1의 이름 |
| 5 | `core/internal/http/pipeline/stages/MakeAsyncHttpRequestStage.java:84-85, 170-175` | 응답 처리 future에서 사용자 future로 넘기는 지점. 어떤 메서드와 어떤 executor? → A1, A2 |
| 6 | 같은 파일 `179-192` | 넘기기가 실패하면(예: executor 거절) 사용자 future는 어느 스레드에서 완료되는가? |
| 7 | 같은 파일 `257-265` (`setupAttemptTimer`) + `core/internal/http/pipeline/stages/AsyncApiCallTimeoutTrackingStage.java:46, 51-74` | 시도/전체 타임아웃 타이머는 무엇을 쓰는가? |
| 8 | `core/internal/http/timers/TimerUtils.java:46-62`, `core/internal/http/timers/AsyncTimeoutTask.java:47-52` | 타이머 작업이 하는 일은? 그 작업은 어느 스레드에서 실행되는가? → A3 (R1, R10) |
| 9 | `core/client/builder/SdkDefaultClientBuilder.java:545-556` (`resolveScheduledExecutorService`) | 타이머·재시도용 스케줄러의 크기와 이름 → A3 |
| 10 | `utils/CompletableFutureUtils.java:75-82` (`forwardExceptionTo`) → `MakeAsyncHttpRequestStage.java:161-166` → `netty-nio-client: http/nio/netty/internal/NettyRequestExecutor.java:117-149` | 타임아웃 예외가 진행 중인 HTTP 요청까지 전달되는가? Netty 쪽에서 무엇을 하는가? (R10과 비교) |
| 11 | `core/internal/http/pipeline/stages/AsyncRetryableStage.java:104-110, 154-191, 242-248` | 재시도 지연은 어떻게 기다리는가? |
| 12 | `dynamodb: services/dynamodb/DynamoDbRetryPolicy.java:45-60, 90-112` | DynamoDB 클라이언트의 기본 재시도(최대 시도 횟수, 기본 지연). 사용자가 재시도 전략을 주면? → Q_AWS_DYNAMODB_DEFAULT_ATTEMPTS |
| 13 | `retries: retries/DefaultRetryStrategy.java:125, 133, 137` | Standard 전략의 최대 시도 횟수와 지연 → A4 |
| 14 | `netty-nio-client: http/nio/netty/SdkEventLoopGroup.java:139-155` + `NettyNioAsyncHttpClient.java:167-173` + `internal/SharedSdkEventLoopGroup.java:55-60` | 이벤트 루프 이름(A2), 기본 스레드 수, **클라이언트 간 공유 여부** |
| 15 | `netty-nio-client: http/nio/netty/internal/ResponseHandler.java:76, 85, 101` | 응답 헤더/본문은 어느 스레드에서 처리되는가? |
| 16 | `core/client/config/ClientOverrideConfiguration.java:286-325` | `apiCallTimeout`/`apiCallAttemptTimeout` Javadoc. 중단이 얼마나 빨리 일어난다고 보장하는가(294-297행)? |

## 2. 파이프라인 지도

README 양식을 채운다. 스레드 풀이 최소 셋(이벤트 루프, completion executor, 스케줄러) 등장한다. 정상 완료·타임아웃 완료·executor 포화 시 완료를 각각 다른 화살표로 그린다.

(여기에 작성)

## 3. "왜 이렇게 설계했나" 질문

1. Lettuce는 이벤트 루프에서 바로 완료하고, AWS SDK는 executor로 넘긴다. 각각 무엇을 얻고 무엇을 잃는가? (지연시간, 처리량, 안전성, 스레드 수)
2. 기본 completion executor는 왜 **클라이언트마다** 따로 만들까? 이벤트 루프 그룹은 공유하는데.
3. 넘기기가 거절되면 왜 예외 대신 이벤트 루프에서 동기 완료할까? 이 폴백이 만들어 내는 "부하가 높을 때만 나타나는" 증상은?
4. 타임아웃으로 끝날 때도 사용자 future가 completion executor에서 완료되는가(A3)? 아니라면 그것은 의도일까? 사용자 입장에서 안전하게 만들려면 무엇을 해야 하는가?
5. Javadoc이 `Runnable::run`을 "strongly discourage"하면서도 왜 옵션 자체는 허용할까? 언제 정당화될 수 있는가?

(여기에 작성)

## 4. 기여 시뮬레이션: E7 `ProductDynamoClients.create`

리뷰 스레드(A~D)를 반영한 클라이언트 설정 PR. 테스트는 ① 응답 콜백이 앱 executor(`aws-cb`)에서 실행 ② 500 응답 시 표준 재시도 전략의 최대 시도 횟수만큼 시도 ③ 3초 걸리는 응답이 1초 안에 타임아웃되고 그 사이 재시도가 일어남(시도별 타임아웃 존재)을 검사한다. PR 설명에 `Runnable::run` 제안을 거절한 근거로 읽기 과제 1의 Javadoc을 인용하라.
