# 4장. Lettuce 7.5.2: 이벤트 루프가 완료시키는 CompletableFuture

> 예측: L1~L4, Q_LETTUCE_* · 구현: E6 · 규칙: O1, O3, O4, R1, R2, R4, R5
> 소스 루트: `lettuce-core-7.5.2.RELEASE-sources.jar`. 경로는 `io/lettuce/core/` 기준이다.

## 0. 이 장에서 답할 질문

- `RedisFuture`의 실체(구현 클래스)는 무엇이고, 누가 언제 완료시키는가?
- 응답 콜백 안에서 blocking하면 무엇이 멈추는가? 같은 연결? 다른 연결?
- 콜백 안에서 sync API를 부르면?
- `ClientOptions.publishOnScheduler`는 무엇에 영향을 주는가?

## 1. 읽기 과제

| # | 위치 | 찾을 것 |
|---|---|---|
| 1 | `RedisFuture.java:8-9, 15, 20, 31` | 무엇을 확장하는가? 리스너 실행 시점에 대해 Javadoc은 무엇이라고 하는가? |
| 2 | `protocol/AsyncCommand.java:47-48` | 어떤 JDK 클래스를 상속하고 무엇을 구현하는가? → Q_LETTUCE_ASYNC_COMMAND_SUPER |
| 3 | `protocol/AsyncCommand.java:112-128` (`complete`, `completeResult`) | 결과가 다 모이면 무엇을 호출하는가? |
| 4 | `AbstractRedisAsyncCommands.java:1334` (`get`) → `836` (`dispatch`) | 사용자에게 돌려주는 RedisFuture는 어떤 객체인가? |
| 5 | `protocol/CommandHandler.java:578-615` (`channelRead`) → `617` (`decode`) → `670, 692` → `772-774` | `command.complete()`는 어느 메서드 안에서, 어느 스레드에서 호출되는가? 콜백이 오래 걸리면 디코딩 루프의 **다음 응답**은 어떻게 되는가? → L1, L2 |
| 6 | `resource/DefaultClientResources.java:98, 128-133, 183-192` | I/O 스레드 수 기본값과 최솟값은? |
| 7 | `resource/DefaultEventLoopGroupProvider.java:213-262` | 이벤트 루프 스레드 이름 후보들과 선택 조건, 계산 풀(`eventExecutorGroup`)의 이름 → L1, L4 |
| 8 | `resource/NioProvider.java:64-67`, `resource/DefaultThreadFactoryProvider.java:18` | Netty 그룹 종류와 스레드 이름 형식 |
| 9 | `ClientOptions.java:67, 367-385, 682-684` (`publishOnScheduler`) | Javadoc은 이 옵션이 무엇을 옮긴다고 하는가? 기본값은? |
| 10 | `grep -rn isPublishOnScheduler` (소스 루트 전체) | 읽는 곳이 여러 군데 나온다. 옵션 복사(복사 생성자, `mutate`)를 빼면 **동작을 바꾸는** 곳은 어느 클래스인가? async 명령 클래스에도 그런 코드가 있는가? → L4, Q_LETTUCE_PUBLISH_ON_READER |
| 11 | `RedisPublisher.java:860-866, 939-949` | 스케줄러가 있을 때 onNext는 어떻게 전달되는가? |
| 12 | `api/StatefulRedisConnection.java:9-10`, `RedisClient.java:61-63` | 연결 공유에 대해 무엇을 전제하는가("if they avoid ...")? |
| 13 | `api/StatefulConnection.java:24-25`, `RedisChannelHandler.java:86` | 어떤 리스너에 대해 "must not block"이라고 명시하는가? async 콜백에 대한 같은 경고가 있는가? (O4) |
| 14 | `failover/MultiDbClient.java:118-121` | 7.4+ 실험 기능은 콜백을 어디서 실행하며, 무엇을 막기 위해서라고 설명하는가? → L3과 연결 |
| 15 | `RedisURI.java:224-226` | 명령 타임아웃 기본값은? → L3의 운영 영향 |
| 16 | `RedisHandshake.java:97-122, 125-170, 218-229, 306-365` | 접속 핸드셰이크에서 보내는 명령들. 프로토콜 미지정일 때와 RESP2일 때. 이 모듈의 테스트용 `FakeRedisServer`가 이것만 이해하면 되는 이유 |

## 2. 파이프라인 지도

README 양식을 채운다. "완료 지점"은 반드시 `CommandHandler`의 줄 번호로 적고, "blocking 시 멈추는 것"을 연결 단위·루프 단위로 구분한다.

<!-- ANSWER-BEGIN -->
**요약**: `RedisFuture`의 실체는 `AsyncCommand extends CompletableFuture`이고, **Netty 이벤트 루프(`lettuce-nioEventLoop-*`)가 응답을 디코딩하는 바로 그 자리에서 `complete()`한다.** 그래서 응답 전에 붙인 non-async 콜백은 이벤트 루프에서 실행되고(R1, O1), 그 안의 blocking은 **그 연결(과 같은 루프를 쓰는 다른 연결)의 모든 명령**을 멈춘다. Lettuce async API에는 이를 막는 장치가 없다(O4). `publishOnScheduler`는 **reactive API 전용**이다.

```
 앱 스레드 (요청 처리)                lettuce-nioEventLoop-4-1  (연결 C1, C3, ... 이 공유)
 ───────────────────                 ──────────────────────────────────────────────
 redis.get(k) → AsyncCommand 생성 ─► write → 소켓
 f.thenApply(cb) (응답 전 등록)
                                     channelRead → decode 루프:
                                        응답1 → AsyncCommand1.complete(v) → cb 실행 (O1)
                                                 └ cb 가 JDBC 300ms → 루프 정지 (R4)
                                        응답2 → (300ms 뒤에야) AsyncCommand2.complete   → L2
 cb 안에서 conn.sync().get(k2)  ──► 명령은 쓰였지만 응답을 디코딩할 루프가 cb 에 갇혀 있음
                                     → 명령 타임아웃(RedisCommandTimeoutException)까지 교착   → L3
 publishOnScheduler(true):
   async  cb  → 여전히 lettuce-nioEventLoop                                               → L4
   reactive onNext → lettuce-eventExecutorLoop (RedisPublisher 가 옮김)                     → L4
 해결: thenXxxAsync(cb, 앱 풀) 로 실행 스레드 고정 (R2)                                      → E6
```

**실험으로 확인한 사실 (가짜 RESP2 서버 + 진짜 Lettuce 연결)**

- L1: 응답 전에 등록한 콜백 → `lettuce-nioEventLoop-*`.
- L2: 콜백이 300ms blocking하는 동안 같은 연결의 다른 GET(서버는 즉시 응답) → ~300ms 뒤 완료.
- L3: 콜백 안에서 같은 연결의 sync GET → 명령 타임아웃(300ms) 후 `RedisCommandTimeoutException`. Netty와 달리 **즉시 예외가 아니라 타임아웃까지 멈춘다**. 기본 타임아웃(60초)이면 60초 동안 그 루프의 모든 연결이 멈춘다.
- L4: `publishOnScheduler(true)`여도 async 콜백은 `lettuce-nioEventLoop`, reactive onNext는 `lettuce-eventExecutorLoop`.
- `ioThreadPoolSize(1)`을 줘도 I/O 스레드는 2개다(최솟값 보정).
- 응답이 이미 와 있으면(R1) 같은 `thenAccept`가 **호출 스레드**에서 실행된다. 첫 프로브에서 실제로 이것 때문에 실험이 흔들렸고, 그래서 시나리오는 서버가 응답을 보류(`held:` 키)하게 만들었다.
- 핸드셰이크(프로토콜 미지정): `HELLO`(→ unknown command) → RESP2 폴백 → `PING` → `CLIENT` ×3(SETINFO lib-name, SETINFO lib-ver, MAINT_NOTIFICATIONS on; 실패는 무시).
<!-- ANSWER-END -->

## 3. "왜 이렇게 설계했나" 질문

1. 왜 `AsyncCommand`가 명령 객체이면서 동시에 `CompletableFuture`일까? 명령마다 future를 따로 만들지 않아서 얻는 것과 잃는 것은?
2. 왜 Lettuce는 기본적으로 연결 하나를 여러 스레드가 공유하게 설계했을까? 그 설계가 "콜백에서 blocking 금지"를 필수 조건으로 만드는 이유는?
3. `publishOnScheduler`는 async와 reactive API 중 어디에 영향을 주는가(L4)? 한쪽에만 영향을 준다면 왜일까? 영향을 받지 않는 쪽 사용자는 무엇으로 같은 효과를 내는가?
4. Netty는 루프 안의 `sync()`를 감지하는가(N4)? Lettuce는 콜백 안의 `connection.sync()`를 감지하는가(L3)? 감지하지 않는 쪽에 감지 PR을 낸다면 어디에서 무엇을 검사하겠는가? DataStax 드라이버의 방식(6장)을 참고하라.
5. 리뷰 코멘트 작성: 아래 코드에 docs/review-checklist.md 양식으로 코멘트를 달아라.
   ```java
   return redis.hgetall("cart:" + userId)
           .thenApply(cart -> productRepository.findAllById(cart.keySet()))   // JPA
           .toCompletableFuture();
   ```

<!-- ANSWER-BEGIN -->
**모범 답안**

1. 명령 하나당 할당을 하나로 줄인다(초당 수십만 명령에서 의미가 크다). 또 명령 스택(`CommandHandler.stack`)에 들어 있는 객체를 그대로 사용자에게 주므로 디코더가 결과를 채우는 즉시 사용자 future가 완료된다. 대가로 사용자가 `complete()`를 호출해 명령 결과를 위조할 수 있다(review-checklist #17의 안티패턴을 성능 때문에 받아들인 것).
2. Redis는 단일 스레드 서버이고 파이프라이닝으로 처리량이 나온다. 연결 하나에 여러 스레드의 명령을 파이프라이닝하는 것이 효율적이다. 그러면 응답 디코딩은 한 스레드(루프)에서 순서대로 일어나므로, 한 콜백의 blocking이 뒤에 줄 선 **다른 스레드의 응답**까지 막는다. 공유가 전제이므로 blocking 금지가 필수다.
3. Reactive는 신호를 스케줄러로 옮기는 관례(`publishOn`)가 있고, 연산자 체인 전체가 그 스레드에서 실행되므로 "한 번에 옮기는" 옵션이 의미가 있다. Async(CF)는 사용자가 `thenXxxAsync(fn, executor)`로 단계마다 직접 고를 수 있으므로 라이브러리 옵션을 두지 않았다. 그래서 async 사용자는 `*Async(fn, 앱 풀)`을 써야 한다.
4. 감지하지 않는다(L3: 타임아웃까지 멈춤). 모든 sync 대기 경로(`internal/Futures.java:228` `awaitOrCancel`, `LettuceFutures.java:58` 등)에서 현재 스레드가 Lettuce 이벤트 루프인지 검사해야 하는데, 사용자가 커스텀 `EventLoopGroup`/스레드 팩토리를 쓸 수 있어 판별이 어렵고 비용도 든다. PR을 낸다면 DataStax의 `BlockingOperation.checkNotDriverThread`처럼 Lettuce가 만든 스레드에 표식(스레드 서브클래스나 FastThreadLocal 플래그)을 달고(`DefaultThreadFactoryProvider`가 스레드를 만드는 지점), sync 대기 지점에서 표식이 있으면 `IllegalStateException`을 던지는 방식이 현실적이다.
5. `[R4][R1][O1][#3] L2 thenApply 안의 productRepository.findAllById 는 JPA(blocking)이고, 앞 단계 future 는 Lettuce 이벤트 루프가 완료시키므로(CommandHandler.decode → AsyncCommand.complete, CommandHandler.java:692) 이벤트 루프를 쿼리 시간만큼 점유한다. 같은 연결(과 같은 루프를 공유하는 연결)의 모든 Redis 응답이 그만큼 지연된다. 또 JPA 영속성 컨텍스트/트랜잭션은 이 스레드에 없다(R12). → thenApplyAsync(.., dbPool)로 옮기고, 트랜잭션이 필요하면 dbPool 작업 안에서 TransactionTemplate 으로 연다. 증상 예상: Redis p99 상승, 무관한 API 지연.`
<!-- ANSWER-END -->

## 4. 기여 시뮬레이션: E6 `ProductRedisRepository.findNameWithAudit`

버그 리포트 → 원인 분석(L1, L2) → 수정 PR. 테스트는 ① 감사 로그가 `db` 풀에서 실행되는지 ② BlockHound로 `lettuce-*` 스레드 위 blocking 0건 ③ 감사 로그가 도는 동안 같은 연결의 다른 GET이 지연되지 않는지를 검사한다. PR 설명에는 읽기 과제 5의 완료 지점을 근거로 인용한다.
