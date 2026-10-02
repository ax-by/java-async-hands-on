# P4. 오픈소스 라이브러리의 공개 비동기 API 설계

> 코드: `src/main/java/async/m11/p4/GeoClient.java` · 결정: [DECISION.md](DECISION.md) · 채점: `P4GradingTest` (7개)

## 상황

당신은 오픈소스 IP 위치 조회 라이브러리 **geo-lookup** 의 메인테이너다. 사용자는 Spring MVC 앱, WebFlux 앱, 배치 등 다양하다.
라이브러리는 Java 17 이상을 지원한다(이 모듈은 `--release 17`).

내부 전송 계층은 Netty 기반 비동기 HTTP 클라이언트이고, `Transport` SPI 로 추상화돼 있다(`Transport.java` javadoc 을 읽어라).
`send(path)` 가 돌려준 future 는 **전송 계층의 I/O 스레드**가 완료시키며, 자체 타임아웃이 없다. `cancel(true)` 하면 요청을 중단하고 연결을 반납한다.

공개 API 의 모양은 정해져 있다(바꾸지 않는다).

```java
GeoClient client = GeoClient.builder(transport)
        .callbackExecutor(myExecutor)              // 선택
        .requestTimeout(Duration.ofMillis(300))    // 선택, 기본 1초
        .build();
CompletionStage<GeoResult> stage = client.lookup("1.2.3.4");   // transport.send("/geo/1.2.3.4") → GeoResult(ip, 응답 본문)
client.close();
```

이 API 의 **동작 계약**을 정하고, 문서화하고, 구현하는 것이 과제다.

## 계약 요구사항 (모두 채점된다)

1. **콜백 스레드**: `lookup` 의 결과는 성공·실패·타임아웃·close 모두 **콜백 executor** 에서 전달된다.
   - 사용자가 `callbackExecutor` 를 지정했으면 그 executor.
   - 지정하지 않았으면 라이브러리가 소유한 작은 풀. 그 스레드 이름은 `geo-callback-N` (문서화된 계약: 스레드 덤프에서 식별 가능해야 한다).
   - 사용자의 non-async 콜백이 **전송 I/O 스레드, commonPool, JDK 지연 스케줄러에서 실행되면 안 된다.** 사용자가 콜백에서 실수로 blocking 해도 I/O 가 멈추지 않아야 한다.
2. **commonPool 금지** (#19): 라이브러리는 어떤 작업도 commonPool 이나 JDK 공용 지연 스케줄러에 의존해 사용자에게 전달하지 않는다. 라이브러리가 만드는 스레드 이름은 모두 `geo-` 로 시작한다.
3. **실패**: 전송 계층의 실패는 unwrap 했을 때 **원래 예외**가 보여야 한다.
4. **타임아웃**: `requestTimeout` 을 넘으면 `TimeoutException` 으로 실패하고, **전송 요청을 취소**해 연결을 반납한다.
5. **취소**: 사용자가 `stage.toCompletableFuture().cancel(true)` 하면 그 조회의 전송 요청이 취소된다. 다른 조회(같은 IP 라도)에는 영향이 없다.
6. **격리** (#17): 호출마다 독립된 stage 를 반환한다. 한 사용자가 자기 future 를 `complete`/`obtrudeValue` 해도 다른 조회에 보이지 않는다.
7. **입력 오류와 종료 후 호출은 던지지 않는다**: `null`/빈 IP 는 `IllegalArgumentException` 으로 실패한 stage(전송하지 않음), `close()` 후의 `lookup` 은 `IllegalStateException` 으로 실패한 stage.
8. **close()**: 진행 중인 조회는 `IllegalStateException` 으로 실패하고, 그 전송 요청은 취소된다. 라이브러리가 소유한 스레드(`geo-*`)는 곧 모두 종료된다. 사용자가 준 executor 는 종료하지 않는다(소유하지 않은 자원).

## 할 일

1. [DECISION.md](DECISION.md) 를 먼저 채운다(공개 계약의 각 항목과 대안 비교).
2. `GeoClient` 의 클래스·`lookup`·`close` javadoc 에 계약을 쓴다. 사용자는 javadoc 만 읽는다.
3. 구현한다. 라이브러리 내부 람다마다 `[실행 스레드]/[blocking]` 을 단다.
4. 자기 리뷰: 체크리스트 #17~#19 와 R10(취소·타임아웃은 상류로 전파되지 않는다)을 확인한다.
