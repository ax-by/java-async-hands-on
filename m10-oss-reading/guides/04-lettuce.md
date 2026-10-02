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

(여기에 작성)

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

(여기에 작성)

## 4. 기여 시뮬레이션: E6 `ProductRedisRepository.findNameWithAudit`

버그 리포트 → 원인 분석(L1, L2) → 수정 PR. 테스트는 ① 감사 로그가 `db` 풀에서 실행되는지 ② BlockHound로 `lettuce-*` 스레드 위 blocking 0건 ③ 감사 로그가 도는 동안 같은 연결의 다른 GET이 지연되지 않는지를 검사한다. PR 설명에는 읽기 과제 5의 완료 지점을 근거로 인용한다.
