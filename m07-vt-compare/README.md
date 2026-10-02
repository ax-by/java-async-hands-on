# m07-vt. 비교: CF vs 가상 스레드 vs 구조적 동시성 (Java 21+/25)

> 난이도 ★★ · 관련 규칙 **R0, R7, R10, R11, R12** + 이 모듈의 **VT1~VT5** · 예측 7개 시나리오(14개 항목) · 구현 3개 + 판단 1개
> `--release 25 --enable-preview` (JDK 25.0.3, `StructuredTaskScope`는 JEP 505 preview API)

**이 모듈의 코드는 Java 8/17 운영 환경에서 쓸 수 없다.** 목적은 두 가지다.

1. 가상 스레드(VT)가 **무엇을 바꾸고 무엇을 바꾸지 않는지** 정확히 안다. 그래서 Helidon 4(Níma), Spring의 `spring.threads.virtual.enabled`, JDK 팀이 왜 "다시 동기 코드로" 가는지 이해한다.
2. Java 8/17 환경에서는 여전히 CF가 답인 이유와, 언젠가 21+/25로 올라갈 때 **무엇을 버리고 무엇을 남길지** 판단한다.

## 이 모듈을 마치면

- 같은 A 설계(독립 blocking 호출 3개)를 CF / VT + `Future.get` / `StructuredTaskScope` 세 가지로 쓰고, 읽기 쉬움과 **실패·취소 의미론** 차이를 말할 수 있다.
- "VT를 쓰면 B가 필요 없다"가 어디까지 맞는지(스레드 비용 vs 다운스트림 한도) 설명할 수 있다.
- ThreadLocal / InheritableThreadLocal / ScopedValue가 VT와 구조적 동시성에서 어떻게 전달되는지 실험으로 안다.
- 환경 제약(Java 8 라이브러리, Java 17 서비스, Java 25 서비스)에 맞는 도구를 근거와 함께 고른다([DECISION.md](DECISION.md)).

---

## 핵심 개념

### 1. 가상 스레드가 바꾸는 것: "blocking 하는 스레드"의 가격

플랫폼 스레드는 OS 스레드 1:1이다(스레드마다 스택을 예약한다: 이 repo의 macOS aarch64 JDK 25 기본 `ThreadStackSize`는 2MB. 생성·전환 비용도 크다). 그래서 "동시에 기다리는 호출 수 = 필요한 스레드 수"(R11, Little의 법칙)가 곧 **메모리와 스케줄링 비용**이었고, 이것을 피하려고 B(완전 non-blocking)와 CF 콜백 체인을 썼다.

가상 스레드는 JVM이 관리하는 가벼운 스레드다. blocking 호출(`Thread.sleep`, 소켓 I/O, `LockSupport.park` 등)을 만나면 **캐리어(플랫폼) 스레드에서 내려오고**(unmount), 준비되면 다시 올라간다. 캐리어 수는 기본적으로 CPU 코어 수다.

```
플랫폼 풀 N개에 지연 L인 호출 M개 (M > N)  →  ⌈M/N⌉ 라운드 ≈ ⌈M/N⌉ × L   (R7, R11)
작업마다 가상 스레드 M개                    →  모두 동시에 기다림 ≈ L        (VT1)
```

### 2. 가상 스레드가 바꾸지 않는 것

- **다운스트림 한도**: VT 10,000개가 동시에 JDBC를 호출하면 커넥션 풀(예: 10개)에서 9,990개가 기다린다. 동시성 제한은 이제 풀 크기가 아니라 `Semaphore`나 커넥션 풀로 **명시**해야 한다. (VT를 풀링하지 않는다.)
- **R12**: VT도 ThreadLocal 기반 컨텍스트를 가진다. 작업마다 **새 스레드**일 뿐이라, 전파하지 않으면 MDC는 비어 있다.
- **R10의 일부**: 가상 스레드 executor에 `submit`한 작업도 `Future.cancel(true)`를 부르지 않으면 계속 돈다. 하지만 `StructuredTaskScope`는 실패·타임아웃 시 **형제를 interrupt로 취소**한다(VT2). CF와 가장 큰 차이다.
- **CPU 바운드 작업**: VT는 기다림을 싸게 만들 뿐 CPU를 늘리지 않는다.

### 3. 같은 A 설계, 세 가지 표현 (`VtScenarios.java`)

```java
// Java 17: CF + 플랫폼 풀 (콜백 조합, 스레드/실패/타임아웃을 직접 설계)
var product = supplyAsync(() -> productDb.call(k), io);
var price   = supplyAsync(() -> priceApi.call(k), io);
var review  = supplyAsync(() -> reviewApi.call(k), io);
return allOf(product, price, review).thenApply(v -> product.join() + "|" + price.join() + "|" + review.join()).join();

// Java 21+: 평범한 blocking 코드 + 작업마다 가상 스레드
try (var vt = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<String> product = vt.submit(() -> productDb.call(k));
    ...
    return product.get() + "|" + price.get() + "|" + review.get();
}   // close() 는 모든 작업이 끝날 때까지 기다린다

// Java 25 preview: 구조적 동시성 (JEP 505)
try (var scope = StructuredTaskScope.open()) {        // 기본 Joiner: 하나라도 실패하면 join 이 FailedException
    Subtask<String> product = scope.fork(() -> productDb.call(k));
    ...
    scope.join();
    return product.get() + "|" + price.get() + "|" + review.get();
}
```

| 관점 | CF + 플랫폼 풀 | VT + Future.get | StructuredTaskScope |
|---|---|---|---|
| 스레드 비용 | 풀 크기만큼 (R11 설계 필요) | 작업당 VT (쌈) | 작업당 VT (쌈) |
| 코드 모양 | 콜백 조합, 예외 래핑(R8) | 순차 코드처럼 읽힘, `ExecutionException` | 순차 코드처럼 읽힘, `FailedException` |
| 하나가 실패하면 | `allOf`는 **나머지가 모두 끝날 때까지** 기다린다. 아무것도 취소 안 함 | `get()` 순서에 따라 다름, 나머지는 계속 돈다. `close()`가 끝날 때까지 기다림 | **즉시** join이 깨어나고 형제를 **interrupt** |
| 타임아웃 | `orTimeout`: future만 완료, 작업은 계속(R10) | `get(timeout)`: 작업은 계속 | `withTimeout`: `TimeoutException` + 진행 중 작업 interrupt |
| 수명 | future가 스코프를 벗어나도 작업이 산다(누수 가능) | try-with-resources로 묶임 | 블록을 벗어나면 모든 subtask가 끝나 있음이 보장 |
| 사용 가능 | Java 8+ | Java 21+ | Java 25 (preview, `--enable-preview`) |

**그래서 Helidon 4, Spring MVC + VT가 "동기 스타일"로 돌아간 이유**: B를 하는 이유가 "기다리는 동안 스레드를 점유하지 않기"였는데, VT에서는 기다리는 스레드 자체가 싸다. 그러면 콜백 체인의 비용(읽기 어려움, 스택 트레이스 단절, 컨텍스트 유실 R12, 스레드 추론 R1)을 치를 이유가 줄어든다. 단 **CF가 사라지는 것은 아니다**: 라이브러리 공개 API(`CompletionStage` 반환), 진짜 비동기 클라이언트(Netty) 위의 조합, Java 8/17 지원은 여전히 CF의 영역이다.

### 4. 이 모듈의 규칙 (VT1~VT5)

| 규칙 | 내용 |
|---|---|
| **VT1 비용 규칙** | blocking 하는 VT는 캐리어를 놓아준다. 동시에 기다리는 작업 수만큼 VT를 만들어도 된다(총 시간 ≈ 지연시간). 단 다운스트림 한도는 그대로이므로 `Semaphore`/커넥션 풀로 동시성을 명시한다. |
| **VT2 구조적 동시성 규칙** | `StructuredTaskScope.open()`(기본 Joiner `awaitAllSuccessfulOrThrow`)은 첫 실패에서 `join()`이 `FailedException`(cause = 원래 예외)을 던지고 나머지 subtask를 **interrupt**한다. `withTimeout`도 마찬가지로 `TimeoutException` + interrupt. `close()`는 모든 subtask가 끝날 때까지 기다린다. |
| **VT3 ThreadLocal 규칙** | VT도 ThreadLocal을 갖지만 작업마다 새 스레드라 비어 있다. `InheritableThreadLocal`은 **스레드를 만드는 스레드**에서 복사된다. 작업마다 VT를 만드는 executor에서는 `submit`을 호출한 스레드의 **현재** 값이, 재사용되는 풀 스레드에서는 **그 스레드가 만들어질 때의** 값이 보인다. |
| **VT4 ScopedValue 규칙** | `ScopedValue.where(K, v).run/call(...)` 범위 안에서만 바인딩된다(불변, 범위를 벗어나면 자동 해제). 다른 스레드로는 **`StructuredTaskScope.fork`로 만든 subtask에만** 상속된다. executor `submit`, CF, 직접 만든 `Thread`에는 전달되지 않는다. |
| **VT5 고정(pinning) 규칙** | JDK 21~23(JEP 491 이전. 직접 확인한 것은 21): `synchronized` 안에서 blocking 하면 VT가 캐리어를 **고정**한다(코어 수만큼만 동시에 진행). JDK 24+(JEP 491)에서 해소됐다. 이 repo에서 JDK 21과 25로 직접 확인했다(`solutions/src/test/java`의 `VerifiedFactsTest`). Java 21 운영이면 blocking 구간의 `synchronized`를 `ReentrantLock`으로 바꾼다. |

---

## 진행 방법

1. 위 개념을 읽고 `src/main/java/async/m07vt/VtScenarios.java`를 읽는다.
2. [PREDICT.md](PREDICT.md)에 예측과 근거(R 번호 또는 VT 번호)를 적고 `Predictions.java`로 옮긴다. 채점:
   ```bash
   mvn -pl m07-vt-compare -am test -Dtest=PredictionTest -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
3. `ProductPage.java`의 X1~X3을 구현한다. 채점:
   ```bash
   mvn -pl m07-vt-compare -am test -Dtest=ProductPageTest -Dsurefire.failIfNoSpecifiedTests=false
   ```
4. [DECISION.md](DECISION.md)의 판단 실습을 채운다.
5. 모듈 전체: `mvn -q -pl m07-vt-compare test` (`-Psolutions`로 돌리면 `solutions/src/test/java`의 `VerifiedFactsTest`가 JDK 21이 있을 때 JDK 21의 pinning도 확인한다.)

**통과 기준**: 예측 적중률(첫 시도) 90% 이상, `ProductPageTest` 전부 통과, DECISION.md의 세 환경 판단에 "합격" 수준 근거([판단 가이드](../docs/decision-guide.md) 3단계).

## 참고: preview API 주의

- `StructuredTaskScope`는 JDK 21~24에서 API가 여러 번 바뀌었다(JDK 21의 `ShutdownOnFailure` 서브클래스 방식 → JDK 25의 `open()` + `Joiner`). 이 모듈은 **JDK 25.0.3의 API**로 컴파일·실행을 확인했다. 다음 버전에서 다시 바뀔 수 있다.
- 컴파일과 실행 모두 `--enable-preview`가 필요하다(이 모듈의 pom에 설정되어 있다).
