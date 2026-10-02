# m04. Executor: 누가, 몇 개를, 어디서 기다리게 하는가

> 난이도 ★★ · 관련 규칙 **R2, R3, R11** (R7 보조) · 예측 23개 · 구현 5개

## 이 모듈을 마치면

- `supplyAsync`에 executor를 왜 **항상** 넘겨야 하는지 commonPool 고갈과 Java 버전 차이로 설명할 수 있다.
- 풀 크기를 "감"이 아니라 **Little의 법칙과 다운스트림 한도**로 정하고, 큐 용량을 **허용 대기시간**으로 정할 수 있다.
- 거절 정책 4종이 `CompletableFuture`에 어떤 일을 하는지 코드만 보고 말하고, CF와 함께 쓰면 안 되는 정책을 골라낼 수 있다.
- "부하가 높을 때만 멈추는" thread starvation deadlock을 코드에서 찾아내고 고칠 수 있다.
- bulkhead로 느린 의존 서비스 하나가 다른 기능을 끌고 들어가지 못하게 설계할 수 있다.

---

## 핵심 개념

### 1. executor가 정하는 네 가지

`supplyAsync(work, executor)`에서 executor는 "어디서 실행할지"만 정하는 게 아니다.

| 질문 | 정하는 것 | 잘못 정하면 |
|---|---|---|
| 어느 스레드에서? | 스레드 이름, 컨텍스트(R12), 다른 작업과의 공유 여부 | 남의 풀(commonPool)을 막는다 |
| 동시에 몇 개까지? | 스레드 수 | 너무 적으면 대기 폭증, 너무 많으면 다운스트림(DB)이 먼저 무너진다 |
| 넘친 작업은 어디서 기다리나? | 큐 종류와 용량 | 무제한 큐는 과부하를 **지연시간 폭증**으로 숨긴다 |
| 큐마저 차면? | 거절 정책 | 동기 예외, caller 실행, **조용히 버림** 중 하나가 일어난다 |

이 네 가지를 정하지 않은 코드(= executor 생략)는 JDK가 대신 정한 답을 쓴다. 그 답이 commonPool이다.

### 2. commonPool: JVM 전체가 나눠 쓰는 CPU용 풀

- `ForkJoinPool.commonPool()`은 **JVM에 하나**다. 병렬도는 기본 `CPU 개수 - 1`(최소 1)이다. 이 repo에서 확인한 값: CPU 3개 → 2, CPU 2개 → 1, CPU 1개 → 1. `-Djava.util.concurrent.ForkJoinPool.common.parallelism=N`으로 바꿀 수 있다.
- 같은 풀을 쓰는 것: executor를 생략한 `xxxAsync`, **parallel stream**, 그리고 여러분이 모르는 라이브러리들.
- `Thread.sleep`, JDBC, `RestTemplate` 같은 평범한 blocking 호출이 워커를 잡고 있어도 ForkJoinPool은 **스레드를 보충하지 않는다**(보충은 `ForkJoinPool.managedBlock`을 거치는 대기에서만 일어난다). 워커가 모두 blocking 중이면 commonPool에 들어온 다른 작업은 워커가 풀려날 때까지 기다린다.
- 증상은 "관계없는 기능이 느려진다"로 나타나므로 원인을 찾기 어렵다. **I/O blocking 작업은 전용 풀에 넣는다(R11).**
- 생각해 볼 것(C01): commonPool이 막혔을 때 `supplyAsync`로 낸 작업과 parallel stream은 똑같이 막힐까? parallel stream의 `forEach`를 호출한 스레드는 그동안 무엇을 하고 있을까?

### 3. [버전] Java 8~24와 25의 기본 executor 차이

- **JDK 8~24**: commonPool 병렬도가 1 이하이면(CPU 1~2개 컨테이너, `-XX:ActiveProcessorCount=1/2`) CF는 commonPool 대신 `ThreadPerTaskExecutor`를 쓴다. 작업마다 **새 플랫폼 스레드**(`Thread-N`)를 만든다.
- **JDK 25**: 항상 commonPool을 쓴다. 병렬도가 1이면 워커도 1개다.
- 같은 코드가 로컬(JDK 25, CPU 많음), 운영(Java 17, CPU 2개 컨테이너), JDK 25로 올린 운영에서 **서로 다른 동시성**으로 돈다. 요청이 몰릴 때 각각 어떤 일이 생길지 V01에서 예측해 보라. **executor를 명시하면 세 환경의 동작이 같아진다.**
- 이 모듈의 V01은 자식 JVM으로 두 버전을 실제로 실행해 채점한다([java-version-matrix.md](../docs/java-version-matrix.md)).

### 4. ThreadPoolExecutor는 "큐 먼저, max는 나중"

작업이 들어올 때 `ThreadPoolExecutor.execute`는 다음 순서로 판단한다.

```
1) 스레드 수 < core          → 새 스레드를 만들어 그 작업을 실행
2) 그 외, 큐에 자리가 있으면  → 큐에 넣는다             ← max 보다 먼저!
3) 큐가 찼고 스레드 수 < max  → 새 스레드를 만들어 "방금 들어온 작업"을 실행 (큐의 작업이 아니다)
4) 그것도 안 되면            → 거절 정책
```

- 큐 종류가 3)이 일어날 수 있는지를 정한다. 큐가 절대 차지 않는다면?
- Spring에서 실제로 확인한 기본값(Spring Framework 6.2, Boot 3.5). S04를 예측한 뒤 이 표를 다시 보라.

  | | core | max | queueCapacity |
  |---|---|---|---|
  | `new ThreadPoolTaskExecutor()` 기본값 | 1 | `Integer.MAX_VALUE` | `Integer.MAX_VALUE` (무제한 `LinkedBlockingQueue`) |
  | Spring Boot `applicationTaskExecutor` (`spring.task.execution.pool.*`) | 8 | 무제한 | 무제한 |

### 5. 크기 정하기: Little의 법칙 + 다운스트림 한도 + 허용 대기시간

```
필요 스레드 ≈ 초당 요청 수 × 요청당 호출 수 × 호출당 blocking 시간(초) × 여유율
예) 200 RPS × 3회 × 0.05s = 30 → 여유 1.5배 → 45
```

- **다운스트림 한도가 상한이다.** DB 커넥션 풀이 20개인데 스레드를 45개 두면, 25개는 커넥션을 기다리며 막혀 있을 뿐이다. 스레드는 한도에 맞추고, 남는 부하는 큐/거절/타임아웃으로 다룬다(E1).
- **큐 용량 = 허용 대기시간 동안 처리할 수 있는 작업 수** = 스레드 수 × 허용 대기시간 / 호출시간. 이보다 큰 큐는 "어차피 타임아웃 날 작업"을 쌓아 둘 뿐이다.
- 무제한 큐는 아무것도 거절하지 않는다. 과부하는 에러가 아니라 **대기시간**으로 나타난다. 모니터링에는 에러 0, p99만 오른다. 제한 큐는 넘치는 순간 거절해서 과부하를 **보이게** 만든다(S05에서 숫자로 확인).
- 타임아웃이 난 작업도 스레드를 계속 점유한다(R10). 그래서 풀에는 여유분이 필요하다(m05에서 자세히).

### 6. 거절 정책: `supplyAsync` 안에서 무슨 일이 일어나는가

`supplyAsync(work, pool)`은 내부에서 future `d`를 만들고, `d`를 완료시키는 `Runnable`(`AsyncSupply`)을 `pool.execute(...)`에 넘긴 뒤 `d`를 반환한다. 거절 정책은 그 `execute` 안에서 동작한다.

| 정책 | `execute` 안에서 하는 일 |
|---|---|
| `AbortPolicy` (기본값) | `RejectedExecutionException`을 던진다 (R3 예외 2) |
| `CallerRunsPolicy` | 넘겨받은 `Runnable`을 **제출한 스레드에서** 바로 `run()`한다 (R3 예외 1). 풀이 종료된 뒤에는 조용히 버린다 |
| `DiscardPolicy` | 넘겨받은 `Runnable`을 아무 말 없이 버린다 |
| `DiscardOldestPolicy` | 큐 맨 앞(가장 오래 기다린)의 `Runnable`을 버리고, 새 `Runnable`을 다시 제출한다 |

- 각 정책에서 `d`는 어떻게 되는가? "`d`를 완료시킬 수 있는 코드는 어디에 있었나"를 따라가면 S02의 답이 나온다.
- `CallerRunsPolicy`는 제출자를 느리게 만들어 자연스러운 backpressure가 되기도 하지만, 제출자가 요청 스레드나 이벤트 루프라면 그 스레드가 blocking 작업을 떠안는다.

### 7. thread starvation deadlock: 부하가 높을 때만 멈춘다

```java
CompletableFuture.supplyAsync(() -> {
    CompletableFuture<String> item = CompletableFuture.supplyAsync(() -> itemDb.call(id), io);  // 같은 풀에 제출
    return item.join();                                                                        // 그리고 기다린다
}, io);
```

- 바깥 작업은 안쪽 작업이 끝날 때까지 스레드를 잡고 있다. 안쪽 작업은 **같은 풀의 빈 스레드**가 있어야 실행된다. 모든 스레드가 바깥 작업으로 차 있으면 서로 기다리며 **영원히** 멈춘다(R11). 스레드 T개, 동시 요청 N개일 때 언제 그렇게 되는지는 S06에서 예측한다.
- 이런 버그는 테스트 환경(동시 요청 1~2개)에서는 재현되지 않고, 트래픽이 몰리는 순간 서비스 전체를 멈춘다. 재시작하면 "해결"된 것처럼 보인다.
- 해결: (1) **기다리지 말고 이어 붙인다** (`thenCompose` + `allOf`, E4). 풀 스레드는 blocking 호출 중일 때만 점유된다. (2) 바깥과 안쪽을 **다른 풀**로 분리한다. 단, 바깥 풀은 기다리는 동안 낭비된다.
- 참고(실측): 같은 패턴을 **ForkJoinPool**에서 돌리면 멈추지 않았다. `CompletableFuture.join()`이 ForkJoinPool 워커에서 호출되면 그냥 잠들지 않기 때문이다. 전용 `new ForkJoinPool(2)`에 요청 8개를 넣어 보니 JDK 25는 기다리는 작업을 **그 워커가 직접 실행**했고(스레드 2개 유지), JDK 21은 **보충 스레드**를 만들었다(2 → 4개). 그렇다고 commonPool에서 `join`해도 된다는 뜻은 아니다. 2절의 blocking 문제는 그대로다.

### 8. bulkhead(격벽): 의존 서비스마다 풀을 나눈다

- 느려진 의존 서비스 하나가 공유 풀의 스레드를 모두 잡으면, 같은 풀을 쓰는 **빠른** 의존 서비스 호출도 큐에서 기다린다. 풀을 나누면 한 서비스의 장애가 그 서비스의 풀 안에 갇힌다(E5).
- 풀 크기 = 그 다운스트림에 허용할 최대 동시 호출 수. 느려진 다운스트림에 스레드를 무제한으로 더 보내는 것은 장애를 키우는 일이다.
- Resilience4j의 `ThreadPoolBulkhead`, Hystrix의 thread pool isolation이 같은 아이디어다.

### 9. 실무에서의 증상 → 원인

| 증상 | 의심할 원인 | 규칙 |
|---|---|---|
| 관계없는 parallel stream / CF 작업이 함께 느려진다 | commonPool에 blocking 작업 | R2, R11 |
| Java 17 소형 컨테이너에서만 스레드 수가 비정상적으로 많다 | executor 생략 + 병렬도 ≤ 1 (`Thread-N`) | R2 |
| 에러는 0인데 p99만 계속 오른다 | 무제한 큐에 작업이 쌓임 | R11 |
| `maxPoolSize`를 올려도 스레드 수가 그대로다 | 4절의 판단 순서와 큐 종류 | R11 |
| 트래픽 피크에 서비스 전체가 멈추고, 재시작하면 괜찮다 | 같은 풀 안에서 join (starvation deadlock) | R11 |
| 큐가 찰 때만 요청 스레드/이벤트 루프가 느려진다 | `CallerRunsPolicy` | R3 |

---

## 진행 방법

1. 위 개념을 읽는다. [추론 규칙 카드](../docs/reasoning-rules.md)의 R2, R3, R7, R11을 함께 본다.
2. `src/main/java/async/m04/Scenarios.java`와 `probe/` 아래 두 파일을 읽고 [PREDICT.md](PREDICT.md)에 **예측과 근거 규칙**을 적는다.
3. 예측을 `Predictions.java`로 옮긴다(`null` → 값).
4. 채점한다. **첫 채점 결과는 고정된다.** 반드시 전부 채운 뒤 실행하라.
   ```bash
   mvn -pl m04-executors -am test -Dtest=PredictionTest -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
   - C01, V01은 **자식 JVM**을 띄워 실행한다(commonPool 병렬도와 CPU 개수는 JVM 시작 시에 정해지므로). 각 1~2초 걸린다.
   - V01의 JDK 21 항목은 `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`의 JDK 21을 쓴다. 다른 곳에 있으면 `JAVA21_HOME` 환경변수로 지정한다. 없으면 그 테스트는 건너뛴다(skipped).
5. 틀린 항목은 [journal](../journal/README.md)에 "어떤 규칙을 어떻게 잘못 적용했는지" 적는다.
6. 구현 실습을 순서대로 푼다.

   | 실습 | 파일 | 내용 | 규칙 |
   |---|---|---|---|
   | E1 | `Exercises.sizePool` | SLO 숫자로 스레드 수와 큐 용량 계산 | R11 |
   | E2 | `Exercises.supplySafely` | 거절을 동기 예외 대신 실패한 future로 | R3 |
   | E3 | `Exercises.paymentPool` | 요구사항에 맞는 큐 + 거절 정책 선택 | R3, R11 |
   | E4 | `OrderAssembler.load` | starvation deadlock 고치기 | R11, R13, R5 |
   | E5 | `DownstreamClients` | bulkhead | R11 |

   ```bash
   mvn -pl m04-executors -am test -Dtest=ExercisesTest -Dsurefire.failIfNoSpecifiedTests=false
   ```
7. PREDICT.md의 서술 문제에 답한다(E3의 선택 근거 포함).

**통과 기준**: 예측 적중률(첫 시도) 90% 이상, `ExercisesTest` 전부 통과, 서술 문제 4번(E3 근거)을 decision-guide의 "합격" 수준으로 작성.

## 더 깊이: JDK 소스로 확인하기 (선택, 권장)

1. `CompletableFuture.ASYNC_POOL`: JDK 21 소스는 `USE_COMMON_POOL = (ForkJoinPool.getCommonPoolParallelism() > 1)`이고, 아니면 `new ThreadPerTaskExecutor()`를 쓴다. JDK 25 소스는 `ForkJoinPool.asyncCommonPool()`("ensures minimal parallelism")로 바뀌었다.
2. `CompletableFuture.asyncSupplyStage` → `e.execute(new AsyncSupply<U>(d, f))`: `d`를 완료시키는 코드는 `AsyncSupply.run()` 안에만 있다. 그리고 `ThreadPoolExecutor`의 네 정책 클래스(`AbortPolicy` 등)의 `rejectedExecution` 본문을 읽어 보라. 각각 몇 줄이 안 된다. (S02 채점 후에 읽으면 더 좋다)
3. `ThreadPoolExecutor.execute`의 주석에 있는 "3 steps": core → `workQueue.offer` → `addWorker(command, false)` → `reject`.
4. `CompletableFuture.waitingGet`: ForkJoinPool 워커에서 `join`하면 `ForkJoinPool.helpAsyncBlocker(...)`로 대기 중인 작업을 도와 실행하고, 그래도 안 되면 `ForkJoinPool.managedBlock`으로 보충 스레드를 만든다. JDK 21은 `helpAsyncBlocker(defaultExecutor(), q)`, JDK 25는 `helpAsyncBlocker(wt.pool, q)`다. 7절에서 JDK 25만 "직접 실행"한 이유다. `ThreadPoolExecutor`에는 이런 장치가 없다.
