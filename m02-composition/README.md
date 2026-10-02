# m02. 조합: 어떤 연산자를 고르고, 언제 시작하고, 무엇을 기다리는가

> 난이도 ★ · 관련 규칙 **R6, R7, R13** (복습: R1, R5) · 예측 23개 · 구현 6개

## 이 모듈을 마치면

- 함수 모양(값을 반환하는가, future를 반환하는가)과 의존 관계(독립인가, 앞 결과가 필요한가)만 보고 **조합 연산자를 바로 고른다**.
- 파이프라인 코드를 보고 총 소요시간을 **합/최댓값/ceil(N/T)×지연**으로 계산한다.
- "병렬로 하려고 CF를 썼는데 실제로는 순차"인 코드(루프 안 join, 늦은 시작)를 읽기만 해도 찾아낸다.
- 반환값을 버린 분기에서 결과와 예외가 **사라지는** 코드를 찾아낸다.

---

## 핵심 개념

### 1. 왜: CF로 얻으려는 것은 "기다림의 겹치기"다

A 목적(R0)에서 CF가 응답시간을 줄이는 원리는 하나뿐이다. **독립적인 기다림을 동시에 진행해서 겹치게 만든다.** 조합 연산자는 이 겹침을 표현하는 문법이다. 그래서 연산자를 잘못 고르거나 시작 시점을 잘못 잡으면 코드는 CF처럼 보여도 **겹침이 사라진다**. 컴파일도 되고 결과값도 맞으니 테스트로는 잡히지 않고, 운영에서 응답시간으로만 드러난다.

### 2. 무엇: 연산자 고르는 표 (R13, R7)

먼저 두 가지만 묻는다. **(1) 다음 함수가 값을 반환하는가, future를 반환하는가? (2) 다음 작업은 앞 결과가 필요한가?**

| 상황 | 연산자 | 결과 타입 |
|---|---|---|
| 앞 결과 → **값** | `thenApply(fn)` | `CF<R>` |
| 앞 결과 → **소비만** (반환 없음) | `thenAccept(consumer)` | `CF<Void>` |
| 앞 결과가 필요 없음, 끝나기만 기다림 | `thenRun(runnable)` | `CF<Void>` |
| 앞 결과 → **future** (다음 I/O를 시작) | `thenCompose(fn)` | `CF<R>` (평탄화) |
| **독립** 둘 → 값 하나 | `a.thenCombine(b, (x, y) -> r)` | `CF<R>` |
| 독립 둘 → 소비 / 끝나기만 | `thenAcceptBoth` / `runAfterBoth` | `CF<Void>` |
| 독립 **N개** → 모두 | `allOf(fs...)` + 콜백 안 `join` (R5) | `CF<Void>` → 직접 모은다 |
| 같은 것을 둘에게 요청, **먼저 온 것** | `a.applyToEither(b, fn)` / `acceptEither` | `CF<R>` / `CF<Void>` |
| N개 중 **먼저 끝난 것** | `anyOf(fs...)` | **`CF<Object>`** (캐스팅 필요) |

```java
// R13: 함수가 future 를 반환하면 thenCompose. thenApply 를 쓰면 CF<CF<T>> 가 된다.
userF.thenApply(user -> orderClient.fetch(user));    // CF<CF<String>>  ← 바깥은 "future를 만들었다"에서 끝난다
userF.thenCompose(user -> orderClient.fetch(user));  // CF<String>      ← 안쪽 결과까지 기다린다
```

`thenApply`로 만든 `CF<CF<T>>`의 바깥 future는 **안쪽 future를 만든 순간** 완료된다. 그 시점에는 주문 조회가 아직 진행 중이다. 이것을 꺼내려고 `.join().join()`을 쓰는 순간 R4/R5 위반이 시작된다.

### 3. 시간 계산 (R7)

| 모양 | 총 시간 |
|---|---|
| `a.thenCompose(x -> b(x))` (의존) | a + b (**합**) |
| 둘 다 먼저 시작 → `thenCombine` / `allOf` (독립) | max(a, b) |
| 독립 N개를 스레드 T개 풀에서 | ceil(N / T) × 지연 |
| 의존 + 병렬 혼합: `a → (b ∥ c)` | a + max(b, c) |
| 루프 안에서 `supplyAsync(..).join()` | **합** |
| 먼저 전부 시작해 담고, 그다음 루프에서 `join` | max. 이미 시작했으니 join 순서는 상관없다 |

**시작 시점이 전부다.** `supplyAsync`를 호출한 순간 작업이 시작된다(R3). 그러니 질문은 "언제 `supplyAsync`가 호출되는가?"다. 앞 작업의 `join()` 뒤나 앞 작업의 콜백 안에서 호출되면, 그 작업은 앞 작업이 끝난 뒤에야 시작된다.

```java
// ❌ 순차: 다음 작업은 앞 join 이 끝나야 시작된다
for (String sku : skus) prices.add(supplyAsync(() -> api.call(sku), io).join());

// ✅ 병렬: 먼저 전부 시작하고, 기다림은 마지막에 한 번 (E2)
List<CompletableFuture<String>> started = skus.stream().map(s -> supplyAsync(() -> api.call(s), io)).toList();
CompletableFuture<List<String>> all = allOf(started.toArray(CompletableFuture[]::new))
        .thenApply(v -> started.stream().map(CompletableFuture::join).toList());  // 이 join 은 막히지 않는다 (R5)
```

### 4. 체인과 분기 (R6)

`thenXxx`는 **새 future를 반환**하고 원래 future는 바꾸지 않는다. 그래서 같은 변수에 콜백을 여러 개 붙이면 **체인이 아니라 분기**다.

```java
CompletableFuture<String> base = supplyAsync(() -> "item", io);
base.thenApply(s -> s + "+gift");        // 분기 1: 결과를 아무도 받지 않는다 → 버려진다
return base.thenApply(s -> s + "+wrap"); // 분기 2: base 에서 출발한다. 분기 1 의 결과는 섞이지 않는다
```

- 버린 분기의 **예외도 함께 사라진다**. 로그조차 남지 않는다. `thenAccept(o -> validate(o))`의 반환값을 버리면 검증 실패를 아무도 모른다.
- 분기 사이의 실행 순서는 보장되지 않는다. "검증 → 보강" 순서가 필요하면 하나의 체인으로 이어야 한다.
- future는 **결과값**이지 "실행 방법(레시피)"이 아니다. 콜백을 몇 개 붙이든 작업은 한 번만 실행된다. 반대로 future를 만드는 **메서드**를 두 번 호출하면 작업도 두 번 실행된다.

### 5. 모으기와 경주: `allOf`, `anyOf`, `either`

- **`allOf`는 fail-fast가 아니다.** 입력 하나가 먼저 실패해도 **나머지가 모두 끝날 때까지** 완료되지 않는다. `thenCombine`도 같다: 두 입력이 모두 끝나야 완료된다. 실패 즉시 응답하려면 직접 만든다(m05).
- `allOf`의 결과는 `CF<Void>`다. 값은 원래 future 목록에서 꺼낸다. 콜백 안의 `join`은 모두 이미 완료된 future에 대한 것이므로 **막히지 않는다**(R5).
- `allOf`/`thenCombine` 뒤의 non-async 콜백은 **마지막으로 끝난 입력을 완료시킨 스레드**에서 실행된다(R1). 이미 모두 완료됐다면 등록한 스레드에서 즉시 실행된다.
- **`anyOf`는 "먼저 성공한 것"이 아니라 "먼저 끝난 것"이다.** 먼저 끝난 것이 실패면 결과도 실패다. `applyToEither`/`acceptEither`도 같다. "실패는 무시하고 첫 성공"이 필요하면 직접 만든다(E5).
- `anyOf`/`either`가 끝나도 **나머지 작업은 계속 실행된다**. 스레드와 커넥션을 계속 잡고 있다(R10).
- `anyOf`의 타입은 `CompletableFuture<Object>`다. 입력 타입 정보를 잃는다. 같은 타입끼리라면 `applyToEither`나 E5처럼 직접 만든 헬퍼가 타입을 지킨다.

### 6. 실무에서의 증상

| 코드 모양 | 증상 |
|---|---|
| 루프 안 `supplyAsync(..).join()` | "CF로 병렬화했는데 응답시간이 그대로다". 스레드 덤프에는 요청 스레드가 `join`에서 기다리는 모습만 보인다 |
| `thenApply`가 future 반환 + `.join().join()` | 콜백 스레드(I/O 스레드일 수 있다)가 막힌다. 부하가 오르면 풀 고갈 |
| 반환값을 버린 `thenAccept` | 감사 로그·이벤트 발행이 가끔 누락되는데 에러 로그가 없다 |
| `anyOf`로 "빠른 쪽 사용" | 캐시 장애 때 DB가 정상인데도 요청이 실패한다 |
| `allOf`로 모은 뒤 실패 처리 | 하나가 즉시 실패해도 가장 느린 호출만큼 기다린 뒤에야 에러 응답 |
| 풀 크기보다 많은 fan-out | 지연시간이 계단식으로 늘어난다(ceil(N/T)). 피크 때 p99만 튄다 |

---

## 진행 방법

1. 위 개념을 읽는다. [추론 규칙 카드](../docs/reasoning-rules.md)의 R6, R7, R13(그리고 R1, R5)을 함께 본다.
2. `src/main/java/async/m02/Scenarios.java`를 읽고 [PREDICT.md](PREDICT.md)에 **예측과 근거 규칙**을 적는다. 시간 예측은 "어느 작업이 언제 시작되는가"를 타임라인으로 그려서 계산한다.
3. 예측을 `Predictions.java`로 옮긴다(`null` → 값).
4. 채점한다. **첫 채점 결과는 고정된다.** 반드시 전부 채운 뒤 실행하라.
   ```bash
   mvn -pl m02-composition -am test -Dtest=PredictionTest -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
5. 틀린 항목은 [journal](../journal/README.md)에 "어떤 규칙을 어떻게 잘못 적용했는지" 적는다.
6. `Exercises.java`의 TODO를 E1부터 순서대로 구현하고 채점한다. 테스트는 결과값뿐 아니라 **즉시 반환 여부, 소요시간, 실행 스레드, 동시 호출 수**를 본다.
   ```bash
   mvn -pl m02-composition -am test -Dtest=ExercisesTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

| 실습 | 연습하는 판단 | 규칙 |
|---|---|---|
| E1 `sequence` | allOf + join 안전 패턴, 입력 순서 유지 | R5, R13 |
| E2 `fetchPrices` | 루프 안 join 고치기, 풀 상한 | R7 |
| E3 `ordersOfUser` | 의존 체인, future 반환 함수, I/O 기다리는 동안 스레드 점유 금지 | R13, R4, R0 |
| E4 `productPage` | 독립 3개 합치기 | R7 |
| E5 `firstSuccessful` | anyOf와 다른 "첫 성공" 헬퍼 | R13, R6 |
| E6 `placeOrder` | 버려진 분기를 체인으로 모으기, 의존 + 병렬 혼합 | R6, R7 |

**통과 기준**: 예측 적중률(첫 시도) 90% 이상, `ExercisesTest` 전부 통과.

## 더 깊이: JDK 소스로 확인하기 (선택)

1. `allOf` → `andTree`: 입력을 이진 트리로 묶어 `BiRelay`로 연결한다. `BiRelay`는 **두 입력이 모두 완료되어야** 발화한다. 그래서 한쪽이 먼저 실패해도 기다린다.
2. `thenCombine` → `biApplyStage` → `BiApply.tryFire` → `biApply(a.result, b.result, ...)`: 두 결과가 모두 `null`이 아니어야 진행한다.
3. `anyOf` → `AnyOf.tryFire`: 입력 중 **처음으로 결과가 생긴 것**을 그대로 옮긴다. 결과가 예외여도 같다.
