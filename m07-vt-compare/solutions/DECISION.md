# m07-vt 판단 실습: 환경 제약에 맞는 도구 고르기

[판단 가이드](../docs/decision-guide.md)의 질문 흐름(R0)을 먼저 적용하고, 그다음 **사용할 수 있는 도구**를 환경으로 거른다.
같은 기능을 세 환경에서 만든다고 하자.

> **기능**: 주문 상세 조회. 주문(JDBC, 80ms), 배송 상태(사내 HTTP API, 120ms), 쿠폰 적용 내역(JDBC, 60ms)은 서로 독립이다.
> 응답 SLO p99 300ms. 피크 400 RPS. DB 커넥션 풀 30개.

## 환경 1. Java 8을 지원해야 하는 사내 공통 라이브러리 (`order-client` SDK)

이 SDK의 공개 메서드 `OrderDetail getDetail(long id)`를 다른 팀이 호출한다. 호출하는 쪽은 Java 8 Spring MVC부터 Java 25 서비스까지 다양하다.

```markdown
## 결정
- 도구: CF / VT / StructuredTaskScope / 동기 순차
- 공개 API 모양:
- 한 줄 근거:

## 근거
- 사용할 수 없는 도구와 이유:
- executor 를 누가 소유하는가:
- 실패·타임아웃 정책:
```

## 환경 2. Java 17 Spring Boot 3 서비스 (Tomcat, 운영 중)

```markdown
## 결정
- 도구:
- 한 줄 근거:

## 실행 모델
- executor: 이름, 크기, 큐, 거절 정책, 크기 근거 (R11, Little 의 법칙)
- 컨트롤러: join / CF 반환 (근거: m07 S17, R0)
- 컨텍스트 전파 (R12):

## 실패 모델
- 전체 타임아웃:            부분 실패 정책:
- 타임아웃 후 남은 작업 (R10):
```

## 환경 3. Java 25 신규 서비스 (Spring Boot, VT 활성화 가능)

```markdown
## 결정
- 도구:
- 한 줄 근거:

## 실행 모델
- 요청 스레드:
- fan-out:
- 동시성 상한 (VT1):
- 컨텍스트 (VT3, VT4):

## 이 결정을 다시 볼 조건
- 예) preview API 변경, 라이브러리의 pinning (VT5), ...
```

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

**공통 계산**: 순차 80+120+60 = 260ms(SLO 300ms에 여유 없음), 병렬 ≈ max = 120ms. 세 호출은 독립 → A가 의미 있다(R0 Q1~Q3). JDBC 2개 × 400 RPS × (0.08, 0.06s) → DB 동시 사용 ≈ 400 × (0.08 + 0.06) = 56 > 커넥션 30. 병렬화 여부와 무관하게 **DB 용량이 먼저 병목**이므로 어떤 도구를 쓰든 DB 동시성 상한(커넥션 풀 대기 타임아웃 또는 세마포어)과 그 초과 시 정책(빠른 실패)을 정해야 한다.

**환경 1 (Java 8 라이브러리)**
- 도구: **CF** (Java 8에서 쓸 수 있는 유일한 조합 도구). 공개 API는 동기 `getDetail`과 함께 `CompletionStage<OrderDetail> getDetailAsync(long id)`를 제공한다.
- VT, STS, ScopedValue, `orTimeout`(9+), `exceptionallyCompose`(12+)는 사용할 수 없다 → 타임아웃은 `ScheduledExecutorService`로 직접 구현(java-version-matrix).
- executor: 라이브러리가 commonPool을 쓰지 않는다(체크리스트 #19). 생성자/빌더로 호출자가 executor를 **주입**하게 하고, 기본값은 라이브러리 전용의 이름 있는 고정 풀 + 유한 큐 + `close()`.
- 반환은 `CompletionStage`(또는 `minimalCompletionStage` 대체: `thenApply(identity())` 복사본)로 내부 future 오염을 막는다(체크리스트 #17). 콜백이 어느 스레드에서 실행되는지 Javadoc에 적는다(#18).
- 실패: 배송 상태 실패는 "알 수 없음"으로 폴백(부분 결과), 주문 실패는 전체 실패. 예외는 unwrap해서 라이브러리 예외 타입으로 감싼다.
- Java 21+ 호출자는 동기 `getDetail`을 VT에서 그냥 호출하면 된다. 그래서 동기 API도 반드시 제공한다.

**환경 2 (Java 17 Spring 서비스)**
- 도구: **A = CF + 전용 플랫폼 풀**. 순차 260ms → 병렬 ≈120ms로 SLO 여유 확보.
- executor: `orderDetailIo`, 동시 필요 스레드 ≈ 400 RPS × (0.08+0.12+0.06)s ≈ 104 → 고정 110~120, 큐 100, AbortPolicy → 503(빠른 실패). DB 호출은 커넥션 30이 상한이므로 Hikari `connection-timeout`을 짧게(예: 100ms) 둬서 대기열이 SLO를 넘기기 전에 실패시킨다.
- 컨트롤러: Tomcat worker 200개가 병목이 아니면 **컨트롤러는 동기**(내부에서 병렬 + `get(250ms)`), 병목이면 CF 반환 + `orTimeout(250ms)` → 504 매핑(m07 E5). 어느 쪽이든 작업은 io 스레드를 점유한다(A).
- 컨텍스트: `ThreadPoolTaskExecutor` + `ContextPropagatingTaskDecorator`(MDC+Security 등록 팩토리, m07 E2). `@Transactional` 안에서 fan-out하지 않는다(m07 E4).
- 실패: 전체 타임아웃 250ms, 배송 상태는 폴백, 나머지는 실패. 타임아웃 후 남은 JDBC 호출은 계속 돈다(R10) → JDBC `queryTimeout`과 HTTP 클라이언트 read timeout을 호출 단위로 건다.

**환경 3 (Java 25 신규 서비스)**
- 도구: **VT 요청 스레드 + StructuredTaskScope fan-out**(preview 사용이 허용되는 조직이면), 아니면 VT + `newVirtualThreadPerTaskExecutor` + `Future.get(timeout)`. CF는 쓰지 않는다(동기 코드로 같은 효과, R0 "쓰지 않음"에 가까움).
- 근거: 기다리는 스레드가 싸다(VT1). STS는 실패/타임아웃 시 형제를 취소하므로(VT2) 장애 시 쓸모없는 DB/HTTP 호출이 남지 않는다. `withTimeout(250ms)`.
- 동시성 상한: 스레드 풀이 사라졌으므로 DB 동시성은 커넥션 풀(30) + 짧은 대기 타임아웃, 또는 `Semaphore(30)`로 명시한다. "VT라서 무한 동시성"은 DB를 죽인다.
- 컨텍스트: traceId는 ScopedValue로 바인딩하고 fork로 상속(VT4). 기존 MDC 기반 로깅은 fork 안에서 MDC 어댑터로 연결.
- 다시 볼 조건: STS API 변경(preview), 라이브러리의 native/JNI 구간 blocking(VT5 이후에도 남는 pinning 후보, 실측으로 확인), CPU 바운드 작업이 섞이면 별도 플랫폼 풀.
<!-- ANSWER-END -->
