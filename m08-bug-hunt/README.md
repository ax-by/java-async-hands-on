# m08. 버그 찾기: 실행하지 않고 증상을 말한다

> 난이도 ★★★★ · 관련 규칙 **R0~R13 전체** · [리뷰 체크리스트](../docs/review-checklist.md) #1~#17, #16b · 사례 22개 (예측 44개) · 수정 22개 · 리뷰 코멘트 5개

## 이 모듈을 마치면

- 남의 CF 파이프라인을 **읽기만 하고** "어떤 조건에서, 어떤 증상이, 왜" 나는지 말할 수 있다.
- 증상을 체크리스트 번호와 추론 규칙(R 번호)으로 설명하는 리뷰 코멘트를 쓸 수 있다.
- 겉보기에 수상하지만 **올바른 코드**를 버그로 몰지 않는다. 과잉 지적도 오답이다.
- 버그를 고친 뒤 "증상이 사라졌다"를 측정으로 증명할 수 있다.

---

## 핵심 개념: 코드 리뷰를 "추적"으로 바꾸는 방법

`bugs/` 의 22개 사례는 자신감 있는 시니어가 쓴 것처럼 생겼다. 주석은 그럴듯하고, 일부 코드는 실제로 옳다.
몇몇 사례에는 **버그가 없다.** 직감("join 이 보이니 나쁘다", "Async 가 많으니 안전하다")으로 판정하면 틀리도록 만들었다.

### 1단계: 람다마다 `[실행 스레드] / [blocking 여부]`를 적는다 (R1~R5)

```java
return orderClient.fetch(id)                                  // 반환 즉시. 완료는 event-loop (R1 표)
        .thenApply(order -> order + couponRepo.call(id))      // [event-loop] / [blocking: JDBC]   ← 여기서 멈춘다
        .thenApplyAsync(page -> page + history.call(id), db); // [db]         / [blocking: JDBC]   ← 격리됨 (R2)
```

질문 순서는 항상 같다.

1. 이 콜백이 **등록될 때** 앞 단계는 완료됐는가? (거의 항상 "아니오": 조립은 I/O 보다 빠르다)
2. 아니라면 **누가 앞 단계를 완료시키는가?** (supplyAsync 의 executor / 비동기 클라이언트의 이벤트 루프 / orTimeout 의 스케줄러 / thenCompose 면 **안쪽** future 를 완료시킨 스레드)
3. Async 라면 executor 는 무엇인가? 생략했다면 commonPool 이다(R2). executor 의 **큐 정책**은? (CallerRuns 면 "제출한 스레드"가 실행할 수 있다, R3)
4. 람다 본문에 blocking 이 있는가? JDBC, sleep, 파일, `RestTemplate`, **다른 future 의 `join()`/`get()`** (R4, R5)

### 2단계: 체크리스트를 위에서부터 훑는다

| 묻는 것 | 체크리스트 | 규칙 |
|---|---|---|
| 그 [blocking] 이 막으면 안 되는 스레드(이벤트 루프, 요청 스레드, commonPool, 같은 풀)에 있는가 | #1~#6 | R1~R5, R11 |
| "병렬"이라는 코드에서 시작과 대기가 분리됐는가 | #7 | R7 |
| 스레드를 옮길 이유가 있는 단계에서만 옮기는가 | #8 | R2 |
| 반환된 future 를 버리는 곳이 있는가. 그 분기의 실패는 누가 보는가 | #9 | R6 |
| 예외를 판별하는 곳에서 unwrap 하는가. 복구 API 가 결과를 정말 바꾸는가 | #10, #13 | R8, R9 |
| 결과까지 이르는 **모든 경로**에 시간 상한이 있는가. 타임아웃 뒤에 실제 작업은 어떻게 되는가 | #11, #12 | R10 |
| 함수가 future 를 반환하는데 `thenApply` 를 쓰지 않았는가 (타입을 끝까지 적어 보라) | #14 | R13 |
| ThreadLocal 컨텍스트가 필요한 단계가 어느 스레드에서 도는가. 전파 코드는 복원하는가. 감싼 executor 의 `execute()` 를 **누가** 부르는가 | #15, #16, #16b | R12 |
| 공유 future 를 바깥에 그대로 내주는가 | #17 | R6, R10 |

### 3단계: "관측 조건"에서 증상을 예측한다

각 사례 클래스 javadoc 에 **관측 조건**(동시 요청 수, 지연, 풀 크기, 주입하는 실패)이 있다. 많은 버그는 조건이 맞아야만 드러난다.
"부하가 낮으면 멀쩡하고 피크에만 멈춘다"는 것까지 예측해야 실무에서 원인을 찾을 수 있다.

### 증상 카탈로그: 운영에서 보이는 모습과 이 모듈의 측정 방법

| Symptom | 운영에서 보이는 모습 | 이 모듈의 측정 |
|---|---|---|
| `STARVATION_DEADLOCK` | 피크에만 특정 API 가 전부 타임아웃, 스레드 덤프에 같은 풀 스레드가 전부 `CompletableFuture.join` 에서 WAITING | 제한 시간 초과 + active == 풀 크기 + 큐 비지 않음 |
| `NEVER_COMPLETES` | 다운스트림 하나가 응답을 안 줄 때 요청이 무한 대기, 커넥션·메모리 누적 | 제한 시간 초과(풀은 멀쩡) |
| `EVENT_LOOP_STALL` | Redis/WebClient 응답 p99 급등, 관계없는 요청까지 함께 느려짐 | 이벤트 루프 위 BlockHound 위반, lag ≥ 100ms |
| `CALLER_THREAD_BLOCKED` | "비동기 메서드"를 불렀는데 호출 스레드가 멈춤 (이벤트 루프에서 부르면 위와 같은 장애) | future 반환까지 ≥ 100ms |
| `COMMON_POOL_STARVATION` | parallel stream·다른 라이브러리의 Async 작업이 이유 없이 느려짐 | 무관한 commonPool 작업의 시작 지연 ≥ 150ms |
| `TIMEOUT_DOES_NOT_FREE_RESOURCES` | 응답은 빠른데 풀 active 가 꽉 차 있고 DB 세션이 쌓임 → 결국 전부 타임아웃 | 응답 후에도 blocking 호출 진행 중 |
| `SEQUENTIAL_NOT_PARALLEL` | "병렬화했는데" 응답시간이 그대로 | 최대 동시 실행 1, 총 시간 ≈ 합 |
| `HOP_QUEUEING_DELAY` | 풀이 바쁠 때 가벼운 API 의 응답시간까지 계단식으로 늘어남 | 첫 요청 응답이 blocking 시간보다 ≥ 100ms 김 |
| `COMPLETES_TOO_EARLY` | "완료" 알림(메일, 이벤트)이 실제 작업보다 먼저 나감, 실패해도 성공 처리 | 결과 완료 시점에 내부 작업 미완료 |
| `FALLBACK_NOT_APPLIED` | 폴백 코드가 있는데 장애가 사용자에게 그대로 노출 | 폴백 대상 실패 주입 → 결과 실패 |
| `EXCEPTION_LOST` | 데이터는 틀렸는데 로그에 아무것도 없음 | 실패 발생, 호출자 성공, 해당 예외 로그 없음 |
| `SHARED_FUTURE_CORRUPTED` | 한 화면의 타임아웃이 다른 화면을 실패시킴, 재현 불가 버그 | 자기 예산 안에 값이 왔는데 실패 |
| `CONTEXT_LOST` | 로그 일부에 traceId 없음, 비동기 구간에서 인증 정보 없음 | traceId 없는 로그 줄 |
| `CONTEXT_LEAK` | 다른 사용자의 데이터가 보임 (보안 사고) | 컨텍스트 없는 작업이 이전 사용자로 실행 |

여러 증상이 동시에 측정되면 **표에서 위에 있는 것**(= `Symptom` enum 선언 순서)이 답이다.

### 증상은 측정으로 판정한다

`SymptomTest` 는 `bugs/` 의 원본을 관측 조건대로 실제로 실행하고, 측정값을 `Symptom` 으로 분류해서 예측과 비교한다(정답을 하드코딩하지 않는다).
그래서 채점 결과 위에 출력되는 **"측정 결과" 블록이 곧 그 버그가 진짜라는 증거**다. 형식:

```
── Cxx 측정 결과 → (측정으로 분류된 Symptom)
  [증상] (기준을 넘은 측정값과 그 기준)
  [정상] (기준을 넘지 않은 측정값과 그 기준)
```

`FixTest` 는 **같은 측정**을 `fixed/` 구현에 돌려 증상이 사라졌는지(`NO_BUG`) 확인하고, 요구사항대로 동작하는지 검사한다.

---

## 진행 방법

1. 이 README 의 방법과 [리뷰 체크리스트](../docs/review-checklist.md), `Symptom.java` 의 각 증상 정의를 읽는다.
2. `src/main/java/async/m08/bugs/C01 ~ C22` 를 순서대로 읽는다. **실행하지 않는다.** [PREDICT.md](PREDICT.md) 에 람다마다 `[스레드]/[blocking]` 을 적고, 증상과 체크리스트 번호, 근거 규칙을 쓴다.
3. 예측을 `Predictions.java` 로 옮긴다. 증상(`Cxx_SYMPTOM`)과 체크리스트 번호(`Cxx_CHECKLIST`, 문자열: "3", "16b". 버그가 없으면 "0"). 체크리스트 정답은 해시로만 들어 있다.
4. **전부 채운 뒤** 채점한다. 첫 채점 결과는 고정된다.
   ```bash
   mvn -pl m08-bug-hunt -am test -Dtest=SymptomTest -Dsurefire.failIfNoSpecifiedTests=false
   python3 tools/score.py
   ```
5. 틀린 사례는 출력된 "측정 결과"를 읽고 [journal](../journal/README.md) 에 "어느 단계(1~3단계)에서 무엇을 놓쳤는지" 적는다.
6. [REVIEW.md](REVIEW.md) 의 5개 사례에 리뷰 코멘트를 쓴다(체크리스트 맨 아래 양식).
7. `fixed/` 의 TODO 를 구현한다. **`fixed/` 패키지는 SymptomTest 채점 후에 처음 연다**(각 클래스의 요구사항과 힌트가 진단의 답을 담고 있다). 각 클래스 javadoc 이 요구사항이다. 버그가 없다고 판단한 사례는 원본과 같은 동작을 구현한다.
   ```bash
   mvn -pl m08-bug-hunt -am test -Dtest=FixTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

**통과 기준**: 예측 적중률(첫 시도) 90% 이상(증상과 체크리스트 번호 모두), `FixTest` 전부 통과, REVIEW.md 5개 코멘트가 모범 답안의 "원인 규칙·증상·조건·수정안"을 모두 담고 있음.

## 자주 틀리는 지점 (채점 후에 읽어라)

<details>
<summary>펼치기</summary>

- "join 이 있으면 버그"는 틀린 규칙이다. **그 시점에 완료가 보장되면** 막지 않는다(R5). 반대로 "Async 니까 안전"도 틀린 규칙이다. 제출하는 스레드, 큐 정책, 컨텍스트 캡처 시점을 함께 봐야 한다.
- 컨텍스트 전파 데코레이터는 **제출 시점의 스레드**에서 컨텍스트를 캡처한다. 비동기 단계를 이벤트 루프가 제출하면 빈 컨텍스트가 전파된다(컨텍스트 사례를 고칠 때 직접 확인하라).
- 같은 증상이라도 원인이 다르면 고치는 법이 다르다. 그래서 체크리스트 번호를 함께 채점한다.

</details>
