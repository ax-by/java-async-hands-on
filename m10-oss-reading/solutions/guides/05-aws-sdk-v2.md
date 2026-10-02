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

<!-- ANSWER-BEGIN -->
**요약**: Lettuce와 반대 선택이다. 응답은 Netty 이벤트 루프(`aws-java-sdk-NettyEventLoop-*`)가 받지만, SDK는 사용자 future를 **별도 executor(`FUTURE_COMPLETION_EXECUTOR`, 기본 `sdk-async-response-*`)로 넘겨서 완료**한다. 사용자 콜백이 blocking해도 이벤트 루프는 안전하다(O4). 단 **타임아웃으로 끝나는 경우는 `sdk-ScheduledExecutor-*`가 완료**시키고(A3), executor가 거절하면 **이벤트 루프가 직접 완료**한다(폴백).

```
 앱 스레드                        aws-java-sdk-NettyEventLoop-0-N      sdk-async-response-1-M     sdk-ScheduledExecutor-K
 ─────────                        (모든 클라이언트가 공유)              (클라이언트별, 큐 1,000)      (타이머·재시도, 5 스레드)
 client.getItem(req)
  ├ 인터셉터 before*·마샬링·서명 (앱 스레드)
  └ 반환 future F ──────────────►  connect/write ... channelRead0
                                   → 언마샬링 + 응답 인터셉터(afterUnmarshalling) → responseHandlerFuture 완료
                                   → handleAsync(.., completionExecutor) ──► completeResponseFuture → F.complete(r)
                                                                              └ F 의 non-async 콜백 (A1)
                                   (executor 거절 시) ──────────────── F.complete 를 이 이벤트 루프에서 (폴백)
                                   (Runnable::run 설정 시) ──────────── F.complete 와 콜백을 이 이벤트 루프에서 (A2)
 apiCallTimeout 초과 ───────────────────────────────────────────────────────────────────────►  F.completeExceptionally(ApiCallTimeoutException)
                                                                                                └ F 의 콜백 (A3)
                                   ◄── forwardExceptionTo: 진행 중 요청 중단 (채널 close / FutureCancelledException)
 재시도: 실패 → schedule(backoff) ──────────────────────────────────────────────────────────► 다음 시도 시작 (sleep 없음)
```

**실험으로 확인한 사실 (진짜 DynamoDbAsyncClient → 로컬 StubHttpServer)**

- A1: 기본 설정 콜백 → `sdk-async-response-*`.
- A2: `Runnable::run` → `aws-java-sdk-NettyEventLoop-*`. 이 콜백에서 blocking하면 그 이벤트 루프를 공유하는 **모든 SDK 클라이언트**의 I/O가 멈춘다(그룹 공유, 읽기 과제 14).
- A3: `apiCallTimeout(200ms)` + 1초 서버 → ~200ms에 `ApiCallTimeoutException`(원인), 콜백은 `sdk-ScheduledExecutor-*`. **completion executor가 "모든" 완료를 맡는다고 믿으면 틀린다.** 타이머 스레드(5개)에서 blocking하면 다른 요청의 타임아웃·재시도까지 밀린다.
- A4: 항상 500 + `standardRetryStrategy()` → 서버가 받은 요청 3회.
- 재시도 설정이 없는 DynamoDB 클라이언트 → 9회, 약 2.3초(레거시 백오프). `apiCallAttemptTimeout(100ms)` + standard + 300ms 서버 → 3회 시도 후 `ApiCallAttemptTimeoutException`(~500ms).
- `ExecutionInterceptor`로 단계별 스레드를 기록하면: `beforeExecution`·`afterMarshalling`·`beforeTransmission`은 **호출 스레드**, `afterTransmission`·`afterUnmarshalling`은 **`aws-java-sdk-NettyEventLoop-*`**. 즉 completion executor가 지켜 주는 것은 "사용자 future의 콜백"뿐이고, **응답 인터셉터(로깅·메트릭 인터셉터)는 이벤트 루프에서 실행된다.** 인터셉터 안의 blocking도 금지다.
- SDK 클라이언트마다 `httpClientBuilder(...)`로 HTTP 클라이언트를 만들면 `close()`할 때마다 이벤트 루프 그룹 종료를 ~2초 기다렸다(테스트가 느려진 원인). 하나의 `SdkAsyncHttpClient`를 `httpClient(...)`로 공유하면 사라진다.
<!-- ANSWER-END -->

## 3. "왜 이렇게 설계했나" 질문

1. Lettuce는 이벤트 루프에서 바로 완료하고, AWS SDK는 executor로 넘긴다. 각각 무엇을 얻고 무엇을 잃는가? (지연시간, 처리량, 안전성, 스레드 수)
2. 기본 completion executor는 왜 **클라이언트마다** 따로 만들까? 이벤트 루프 그룹은 공유하는데.
3. 넘기기가 거절되면 왜 예외 대신 이벤트 루프에서 동기 완료할까? 이 폴백이 만들어 내는 "부하가 높을 때만 나타나는" 증상은?
4. 타임아웃으로 끝날 때도 사용자 future가 completion executor에서 완료되는가(A3)? 아니라면 그것은 의도일까? 사용자 입장에서 안전하게 만들려면 무엇을 해야 하는가?
5. Javadoc이 `Runnable::run`을 "strongly discourage"하면서도 왜 옵션 자체는 허용할까? 언제 정당화될 수 있는가?

<!-- ANSWER-BEGIN -->
**모범 답안**

1. Lettuce(직접 완료): 스레드 전환이 없어 지연시간이 가장 짧고 스레드가 적다. 대신 사용자 콜백의 blocking이 I/O를 멈춘다(안전성을 사용자에게 맡김). AWS(넘김): 스레드 전환 비용과 추가 스레드(최대 64+)가 들지만, SDK 사용자 대부분이 blocking 코드를 섞어 쓰는 현실에서 공유 이벤트 루프를 지킨다. "실수해도 전체가 멈추지 않게"를 고른 것이다.
2. 한 클라이언트의 느린 콜백이 다른 클라이언트의 완료를 굶기지 않게 격리하기 위해서다(R11의 풀 격리). 이벤트 루프는 non-blocking 작업만 하므로 공유해도 안전하고 스레드를 아낄 수 있다.
3. 예외를 던지면 응답은 왔는데 사용자 future가 영원히 완료되지 않는(또는 엉뚱한 예외로 실패하는) 더 나쁜 결과가 된다. 그래서 "느리더라도 완료"를 고르고 debug 로그를 남긴다. 증상: 평소에는 콜백이 `sdk-async-response`에서 돌다가, 트래픽이 몰려 큐(1,000)가 차고 스레드가 최대치일 때만 콜백이 이벤트 루프에서 실행되어 blocking 콜백이 I/O를 멈춘다. 평시 테스트로는 재현되지 않는다(review-checklist #6 CallerRuns와 같은 구조).
4. 타이머 작업이 하는 일은 `completeExceptionally` 한 줄이라 넘길 이유가 적다고 본 구현 선택으로 보인다(문서화되어 있지 않다). 사용자는 SDK future 뒤에 붙이는 **모든 무거운 단계**를 `thenXxxAsync(fn, 앱 풀)`로 옮겨야 한다. 그러면 정상 완료든 타임아웃 완료든 실행 위치가 고정된다(R2).
5. 콜백이 정말 가벼운(blocking 없는, 짧은) 고성능 경로에서 스레드 전환 비용을 없애고 싶을 때다. 그래서 금지 대신 `@SdkAdvancedApi(cautionWhen = CONFIGURED)`로 "고급 사용자 책임"을 표시했다(SdkAdvancedAsyncClientOption.java:57-63). 단 Javadoc이 경고하듯 콜백 안에서 **다시 SDK를 호출**하면 SDK 내부의 blocking 경로 때문에 교착할 수 있으므로, 일반 서비스 코드에서는 정당화되기 어렵다.
<!-- ANSWER-END -->

## 4. 기여 시뮬레이션: E7 `ProductDynamoClients.create`

리뷰 스레드(A~D)를 반영한 클라이언트 설정 PR. 테스트는 ① 응답 콜백이 앱 executor(`aws-cb`)에서 실행 ② 500 응답 시 표준 재시도 전략의 최대 시도 횟수만큼 시도 ③ 3초 걸리는 응답이 1초 안에 타임아웃되고 그 사이 재시도가 일어남(시도별 타임아웃 존재)을 검사한다. PR 설명에 `Runnable::run` 제안을 거절한 근거로 읽기 과제 1의 Javadoc을 인용하라.
