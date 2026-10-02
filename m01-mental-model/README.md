# m01. 멘탈 모델: 콜백은 누가, 언제 실행하는가

> 난이도 ★ · 관련 규칙 **R1, R2, R3, R5** · 예측 21개 · 구현 3개

## 이 모듈을 마치면

- 어떤 CF 코드든 각 콜백이 **어느 스레드에서 실행되는지** 근거 규칙과 함께 말할 수 있다.
- "같은 코드가 타이밍에 따라 다른 스레드에서 실행된다"를 직접 설명할 수 있다.
- `supplyAsync`는 막지 않고 `join`은 막는다는 것을 시간으로 증명할 수 있다.

---

## 핵심 개념

### 1. CompletableFuture의 실체: 상태 하나 + 기다리는 콜백 목록

```
CompletableFuture
 ├─ result     : null(미완료) | 값 | 예외      ← 딱 한 번만 바뀐다
 └─ stack      : [콜백3] → [콜백2] → [콜백1]   ← 아직 실행되지 않은 의존 단계들
```

- `thenApply(fn)`을 호출하면 두 가지 중 하나가 일어난다.
  - `result`가 **이미 있으면**: `fn`을 **지금 이 스레드에서 바로** 실행한다.
  - `result`가 **없으면**: `fn`을 `stack`에 쌓아 두고 즉시 반환한다.
- 누군가 `complete(v)`를 호출하면, **그 스레드가** `result`를 채운 뒤 `stack`에 쌓인 콜백을 **차례로 실행**한다.

이것이 **R1(완료 스레드 규칙)**의 전부다. CompletableFuture에는 "콜백을 실행해 주는 전용 스레드"가 없다. **완료시키는 스레드가 일을 떠맡거나, 이미 완료됐다면 등록하는 스레드가 떠맡는다.**

```
 io-1 스레드                             caller(테스트) 스레드
 ───────────                             ──────────────────────
                                         f = supplyAsync(work, io)   // 제출만 하고 즉시 반환 (R3)
                                         g = f.thenApply(fn)         // f 미완료 → fn 을 stack 에 쌓음
 work 실행 ...
 f.complete(v)  ──┐
                  └─ stack 의 fn 실행 ← fn 은 io-1 에서 실행된다
                                         h = f.thenApply(fn2)        // f 완료 → fn2 를 caller 에서 즉시 실행
```

### 2. Async 접미사: "실행할 곳을 내가 정한다"

| 호출 | 실행 위치 |
|---|---|
| `thenApply(fn)` | R1을 따른다. 비결정적 |
| `thenApplyAsync(fn)` | `ForkJoinPool.commonPool()`. 단, JDK 8~24에서 병렬도 ≤ 1이면 작업마다 새 스레드 ([버전표](../docs/java-version-matrix.md)) |
| `thenApplyAsync(fn, ex)` | 항상 `ex` |

Async는 이미 완료된 future에서도 **항상 executor로 넘긴다**. 실행 스레드를 고정하는 도구다.

### 3. 시작과 대기

- `supplyAsync(work, ex)`는 `ex`에 **제출만 하고** 즉시 반환한다(R3). 작업은 이 순간 **이미 시작**된다. 콜백을 붙여야 시작되는 게 아니다.
- `join()`/`get()`은 완료될 때까지 **호출 스레드를 막는다**(R5).
- 예외: executor가 받은 작업을 그 자리에서 실행하면(`Runnable::run`) `supplyAsync`도 호출 스레드에서 **동기 실행**된다.

### 4. 조합 단계의 완료 주체

| 단계 | 그 단계를 완료시키는 스레드 → 뒤에 붙은 non-async 콜백이 실행되는 곳 |
|---|---|
| `a.thenCombine(b, fn)` | a와 b 중 **나중에** 완료된 쪽 |
| `anyOf(a, b)` | **먼저** 완료된 쪽 |
| `a.thenCompose(x -> inner)` | `inner`를 완료시킨 스레드(아직 미완료였다면) |
| `orTimeout(...)`으로 실패 | JDK 타임아웃 스케줄러 스레드 |

### 5. 왜 이것이 실무에서 치명적인가 (미리 보기)

비동기 Redis 클라이언트가 준 future에 `thenApply(x -> jdbc.query(x))`를 붙였다고 하자. 응답이 오기 전에 붙였다면 R1에 따라 이 JDBC 쿼리는 **Redis 클라이언트의 이벤트 루프 스레드**에서 실행된다. 그 스레드가 쿼리 시간 동안 멈추면, 그 스레드가 담당하는 **다른 모든 Redis 응답이 함께 멈춘다**. 이 문제는 m06, m08, m10에서 직접 재현한다.

---

## 진행 방법

1. 위 개념을 읽는다. 필요하면 [추론 규칙 카드](../docs/reasoning-rules.md)의 R1, R2, R3, R5를 함께 본다.
2. `src/main/java/async/m01/Scenarios.java`를 읽고, [PREDICT.md](PREDICT.md)에 시나리오별 **예측과 근거 규칙**을 적는다.
3. 예측을 `Predictions.java`로 옮긴다(`null` → 값).
4. 채점한다. **첫 채점 결과는 고정된다.** 반드시 전부 채운 뒤 실행하라.
   ```bash
   mvn -pl m01-mental-model -am test -Dtest=PredictionTest -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
5. 틀린 항목은 [journal](../journal/README.md)에 "어떤 규칙을 어떻게 잘못 적용했는지" 적는다.
6. `Exercises.java`의 TODO를 구현하고 채점한다.
   ```bash
   mvn -pl m01-mental-model -am test -Dtest=ExercisesTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

**통과 기준**: 예측 적중률(첫 시도) 90% 이상, `ExercisesTest` 전부 통과.

## 더 깊이: JDK 소스로 확인하기 (선택, 권장)

IDE에서 `java.util.concurrent.CompletableFuture`를 열고 아래 순서로 따라가 보라. R1이 코드로 보인다.

1. `thenApply` → `uniApplyStage`: `result`가 이미 있으면 `uniApplyNow`(즉시 실행 또는 executor로 넘김), 없으면 `UniApply` 객체를 만들어 `unipush`로 stack에 쌓는다.
2. `UniApply.tryFire`: stack에 쌓였던 콜백이 **나중에** 실행되는 지점이다.
3. `complete` → `postComplete`: **완료시킨 스레드가** stack을 비우며 각 콜백의 `tryFire`를 호출한다.
4. `thenApplyAsync(fn, e)` → `uniApplyStage(screenExecutor(e), fn)`: executor가 있으면 `tryFire`가 `e.execute(...)`로 넘긴다.
5. `thenCompose` → `uniComposeStage`: 안쪽 future `g`가 미완료면 `g.unipush(new UniRelay(...))`로 **안쪽 future에** 릴레이를 건다. 그래서 바깥 결과를 완료시키는 스레드는 "안쪽 future를 완료시킨 스레드"가 된다.
