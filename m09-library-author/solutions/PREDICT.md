# m09 예측 시트

`Scenarios.java`와 `ModernScenarios.java`(S09)를 읽고 **코드를 실행하기 전에** 채운다. "근거 규칙"이 비어 있으면 예측한 것이 아니라 찍은 것이다. 규칙은 R 번호([추론 규칙 카드](../docs/reasoning-rules.md))와 이 모듈의 L 번호([README](README.md#0-왜-라이브러리는-기준이-더-엄격한가))를 쓴다.

값의 형식: 스레드는 `On.pool("nonblocking-minikv-io")`, `On.pool("app")`, `On.pool("minikv-timer")`, `On.caller()` 중 하나. 문자열은 값이면 그대로(`"val:k"`), 예외면 **단순 클래스 이름**(`"IllegalStateException"`). 참/거짓은 `true`/`false`, 시간은 ms, 개수는 정수.

| 시나리오 | 항목 | 예측 | 근거 규칙과 한 줄 이유 | 결과 (채점 후) | 틀렸다면 무엇을 잘못 적용했나 |
|---|---|---|---|---|---|
| S01 | user (스레드) | | | | |
| S02 | user (스레드) | | | | |
| S02 | late (스레드) | | | | |
| S03 | user (스레드) | | | | |
| S03 | 예외 이름 | | | | |
| S04 | 호출자 B의 값 | | | | |
| S05 | 호출자 B의 결과 | | | | |
| S06 | 호출자 B의 값 | | | | |
| S06 | `toCompletableFuture() == stage` | | | | |
| S07 | 호출자 B의 값 | | | | |
| S08 | thenApply 사본의 exceptionally | | | | |
| S08 | 중계 future의 exceptionally | | | | |
| S09 | minimal stage 캐스팅 후 complete | | | | |
| S10 | returned (ms) | | | | |
| S10 | user (스레드) | | | | |
| S11 | 결과 | | | | |
| S12 | 큐에 남은 작업 수 | | | | |
| S13 | `--release 8` 컴파일 성공? | | | | |
| S13 | `-source 8 -target 8` 컴파일 성공? | | | | |

## 채점 후 서술 문제 (journal에 답한다)

1. S04~S07을 근거로 "Java 8에서 반환 타입을 `CompletionStage`로 바꾸면 내부 상태가 보호된다"는 주장을 평가하라. 무엇이 실제 보호 장치인가?
2. S08과 S03은 둘 다 "바로 붙인 콜백이 받는 예외"를 묻는다. 각 경우에 예외가 감싸졌는지 아닌지와 그 이유를 R8로 설명하고, 라이브러리가 사용자에게 주는 stage를 만들 때 어떤 방식을 써야 하는지 말하라.
3. 팀에서 minikv의 기본 완료 정책을 정책 1(I/O 스레드)에서 정책 2(라이브러리 소유 completion executor)로 바꾸자는 PR이 올라왔다. 찬성 근거 2개, 반대 근거 2개를 들고, 당신의 결론과 Javadoc에 반드시 들어가야 할 문장을 적어라.
4. S11에서 서버는 200ms 만에 응답했다. 그런데도 결과가 그렇게 나온 이유를 R1과 R11의 "같은 풀 안에서 기다리지 않는다"로 설명하고, E7이 이 상황을 어떻게 다루는지 적어라.
5. S12의 결과를 운영 규모(초당 요청 수, 타임아웃 시간)로 옮기면 어떤 증상이 되는가? 문제가 있다면 고치는 데 필요한 조건을 모두 적어라.
6. S13에서 `-source 8 -target 8` 빌드가 jar를 만들어 냈다면, 그 jar를 Java 8 사용자가 쓸 때 어떤 일이 언제 일어나는가? 왜 라이브러리 CI는 이것을 잡지 못할 수 있는가?

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

| 시나리오 | 답 | 근거 |
|---|---|---|
| S01 user | nonblocking-minikv-io | R1: 응답 전에 등록 → raw를 완료시킨 I/O 스레드가 중계까지 같은 스레드에서 실행 (L2 정책 1) |
| S02 user / late | app / caller | 정책 2: 완료 작업이 app에서 실행된다. late는 이미 완료된 stage에 등록 → 등록한 스레드(R1). 라이브러리가 바꿀 수 없는 부분 |
| S03 user / 예외 | minikv-timer / TimeoutException | R10: 타임아웃이 완료시키면 타이머 스레드. `completeExceptionally(new TimeoutException())`로 직접 실패시켰고 중계도 원인을 그대로 넘기므로 감싸지지 않는다(R8) |
| S04 | hacked | L1(#17): 모두 같은 객체. `complete`가 먼저 이기고, 나중의 I/O 응답은 무시된다 |
| S05 | CancellationException | L1: 같은 객체가 취소됐다. 요청 자체는 계속 진행된다(R10) |
| S06 B / same | val:k / true | 호출마다 새 의존 future라서 A의 complete는 A의 사본만 바꾼다(R6). CF 기반 stage의 `toCompletableFuture()`는 자기 자신이다 |
| S07 | hacked | 사본 하나를 모두가 공유하면 S04와 같다. 보호는 반환 타입이 아니라 "호출마다 새 객체"에서 나온다 |
| S08 | CompletionException / MiniKvException | `thenApply` 사본은 의존 단계라서 원인을 `CompletionException`으로 감싼다(R8). whenComplete로 받은 원인을 `completeExceptionally`로 직접 넘기면 그대로 |
| S09 | UnsupportedOperationException | `MinimalStage`는 CF 하위 클래스라 캐스팅은 되지만 쓰기 메서드(그리고 `isDone`, `join` 등)는 모두 UOE |
| S10 returned / user | 200 / caller | 가짜 async는 응답까지 호출자를 막는다(L4). 반환된 future는 이미 완료 → 콜백은 등록 스레드(R1) |
| S11 | TimeoutException | app(스레드 1개)이 `get(500ms)`으로 기다리는 동안 완료 작업은 app 큐 뒤에 있다. 같은 풀 안에서 기다림(R11) → 500ms 후 TimeoutException |
| S12 | 100 | 기본 `ScheduledThreadPoolExecutor`는 취소된 작업을 지연시간이 끝날 때까지 큐에 둔다(removeOnCancel=false) |
| S13 release 8 / source-target 8 | false / true | `--release 8`은 Java 8 API 목록으로 검사한다. `-source/-target`은 JDK 25 API로 컴파일하므로 통과한다 (L8) |

서술 1: 반환 타입이 `CompletionStage`여도 `toCompletableFuture()`가 자기 자신을 돌려주고 캐스팅도 되므로(S06) 호출자는 그 stage를 complete/cancel할 수 있다. 실제 보호 장치는 **호출마다 새 future를 주는 것**이다. 그러면 호출자가 망가뜨릴 수 있는 것은 자기 사본뿐이다. 사본을 캐시해서 공유하면(S07) 반환 타입과 상관없이 보호가 깨진다. 반환 타입은 "쓰기 연산을 하지 말라"는 의도 표시와 사용성(`join`이 없음)의 트레이드오프일 뿐이다.

서술 2: R8에 따르면 `completeExceptionally(ex)`로 직접 실패시킨 future에 **바로** 붙인 콜백은 `ex`를 받고, 그 뒤의 의존 단계부터 감싸진다. S03은 raw를 직접 실패시키고 whenComplete가 받은 원인을 `out.completeExceptionally(ex)`로 넘겼으므로 `out`도 "직접 실패"한 future다. S08의 `thenApply` 사본은 의존 단계라서 감싸진다. 라이브러리는 `new CompletableFuture<>()` + `whenComplete` 중계 + unwrap(E1, E4)으로 사용자 stage를 만든다.

서술 3: 찬성: (1) 사용자가 콜백에서 실수로 blocking해도 I/O 스레드가 멈추지 않는다. 한 사용자의 실수가 같은 클라이언트를 쓰는 다른 요청 전체의 지연으로 번지지 않는다. (2) 타임아웃 완료도 같은 executor에서 실행되므로 타이머 스레드가 보호되고, 사용자는 콜백 스레드를 하나만 이해하면 된다. 반대: (1) 요청마다 스레드 전환 1회(지연과 CPU 비용), executor 포화 시 큐 대기. (2) 라이브러리가 스레드 풀을 소유하면 크기, 종료(`close`), 거절 정책까지 책임진다. 포화 시 동작(AWS처럼 동기 완료 폴백)을 정해야 한다. 결론 예: 범용 SDK라면 안전한 기본값(정책 2, AWS SDK v2 방식)에 정책 1을 옵션으로 둔다. 지연에 민감한 저수준 드라이버라면 정책 1 기본에 명확한 문서를 둔다. 어느 쪽이든 Javadoc에 "반환된 stage에 응답 전에 붙인 non-async 콜백은 X 스레드에서 실행된다. 이 스레드에서 blocking하지 말라. 이미 완료된 stage에 붙인 콜백은 붙인 스레드에서 실행된다"를 쓴다.

서술 4: 응답은 200ms에 I/O 스레드에 도착했다. 정책 2라서 I/O 스레드는 완료 작업을 app에 제출만 한다. 그런데 app의 유일한 스레드는 바로 그 완료를 `get(500ms)`으로 기다리고 있다. 완료 작업은 큐에서 그 스레드가 풀려나기를 기다리므로 500ms 후 `TimeoutException`이 난다. 같은 bounded 풀 안에서 그 풀이 해야 할 일을 기다린 것이다(R11). E7은 completion executor를 거치지 않는 **raw future**(I/O 스레드가 완료)를 기다리므로 app 스레드에서 불러도 200ms 후 값을 받는다. I/O 스레드나 소유 타이머 스레드에서 부르면 기다리지 않고 즉시 거절한다.

서술 5: 초당 R개 요청, 타임아웃 T초면 취소된 타이머가 항상 약 R×T개 큐에 쌓인다(예: 1만 rps × 10초 = 10만 개). 각 항목이 future와 람다(캡처한 요청 객체)를 붙잡으므로 힙 사용량이 늘고 GC 부담과 스케줄러 큐 연산 비용이 커진다. 고치려면 (1) 완료 시 타이머를 `cancel`하고(E2) (2) 스케줄러를 `setRemoveOnCancelPolicy(true)`로 만든다(`Executors.newSingleThreadScheduledExecutor()`는 이 설정을 할 수 없으므로 `ScheduledThreadPoolExecutor`를 직접 생성).

서술 6: Java 8 JVM에는 `CompletableFuture.orTimeout`이 없으므로 그 코드가 **처음 실행되는 순간** `NoSuchMethodError`가 난다. 클래스 로딩이나 시작 시점이 아니라 그 경로를 처음 탈 때(예: 타임아웃을 설정한 첫 요청) 터진다. 라이브러리 CI가 JDK 11/17/21에서만 테스트를 돌리면 그 JVM에는 메서드가 있으므로 통과한다. major version도 52라서 "Java 8 호환"처럼 보인다. 그래서 `--release 8`로 컴파일하고, 가능하면 CI에 Java 8 런타임 테스트를 추가한다.
<!-- ANSWER-END -->
