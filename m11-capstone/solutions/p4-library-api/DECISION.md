# P4 결정 기록: geo-lookup 공개 비동기 API 계약

라이브러리 API 는 한 번 공개하면 바꾸기 어렵다. 구현보다 **계약**을 먼저 정한다. 각 항목에 "사용자가 무엇을 믿어도 되는가"를 한 문장으로 쓰고, 고려한 대안과 버린 이유를 적는다.

## 1. 반환 타입
- 결정 (CompletableFuture / CompletionStage / minimalCompletionStage / 자체 Future 타입):
- 이유와 버린 대안:
- 취소 요구사항(사용자 cancel → 전송 취소)과 어떻게 양립시키는가:

## 2. 콜백 스레드 계약
- javadoc 에 쓸 문장:
- 기본 executor 를 라이브러리가 만든다면 크기·스레드 이름·데몬 여부·종료 시점:
- 이 계약이 없으면 사용자에게 생기는 일 (#18):

## 3. 타임아웃
- 기본값과 근거:
- 구현 방법 (orTimeout / delayedExecutor / 자체 타이머) 과 그 선택이 콜백 스레드 계약(2번)에 미치는 영향:
- 타임아웃 후 전송 자원 처리 (R10):

## 4. 취소
- 사용자가 cancel 하면 무엇이 일어나는가 (javadoc 문장):
- 취소가 전송 계층까지 가는 경로 (R10: 취소는 저절로 상류로 가지 않는다):

## 5. 실패 표현
- 입력 오류·종료 후 호출을 throw 하지 않고 실패한 stage 로 주는 이유:
- 예외 래핑 규칙 (사용자는 어떤 타입을 보게 되는가):

## 6. close()
- 진행 중인 조회, 이후 호출, 소유한 스레드, 소유하지 않은 executor 각각의 처리:

## 7. 버전 호환
- 이 API 를 Java 8 사용자에게도 제공해야 한다면 바꿔야 할 구현 (java-version-matrix.md 참고):

<!-- ANSWER-BEGIN -->
### 모범 답안 (solutions/ 폴더에만 있음)

## 1. 반환 타입
- **`CompletionStage<GeoResult>` 로 선언하고, 실제로는 호출마다 새로 만든 `CompletableFuture` 를 반환**한다.
- `CompletableFuture` 로 선언하면 `complete`/`obtrudeValue` 가 공개 계약처럼 보인다(내부 상태 오염 경로, #17). `CompletionStage` 가 의도("조합하라")를 드러낸다.
- `minimalCompletionStage()` 는 가장 안전하지만, `toCompletableFuture()` 가 **사본**을 주므로 사용자의 `cancel` 이 원본에 닿지 않는다(JDK 25 에서 확인: 사본을 cancel 해도 원본은 취소되지 않음). 취소 요구사항과 충돌 → 버림.
- 그래서 "호출마다 독립된 CF" + "그 CF 의 완료(취소 포함)를 라이브러리가 관찰해 전송을 취소"한다. 사용자가 자기 CF 를 조작해도 그 조회에만 영향이 있다(공유 future 를 내주지 않는다).

## 2. 콜백 스레드
- javadoc: "반환된 stage 는 성공·실패·타임아웃 모두 **callbackExecutor** 에서 완료된다(지정하지 않으면 라이브러리 소유의 `geo-callback-N` 데몬 스레드 2개). 따라서 non-async 콜백은 그 스레드에서 실행된다. 전송 I/O 스레드에서는 절대 실행되지 않는다. 사용자가 `cancel` 한 경우에만 취소를 호출한 스레드에서 완료된다."
- 기본 풀: 2 스레드(전달만 하므로 작게, 사용자 blocking 에 대비한 최소 여유), 데몬, `close()` 에서 `shutdown()`(이미 맡은 전달은 마치고 종료).
- 계약이 없으면: 사용자가 `thenApply(x -> jdbc...)` 를 붙였을 때 Netty I/O 스레드에서 실행돼 라이브러리 전체 I/O 가 멈추고, 사용자는 원인을 라이브러리 버그로 오해한다(#18).

## 3. 타임아웃
- 기본 1초: 위치 조회는 부가 정보라 길게 기다릴 이유가 없다. 사용자가 조정 가능.
- `orTimeout` 은 쓰지 않는다: 시간 초과 시 future 를 **JDK 지연 스케줄러 스레드**가 완료시켜(JDK 25 에서는 commonPool 의 delayScheduler) 사용자 콜백이 그 JVM 공용 스레드에서 돈다 → 2번 계약 위반, #19 위반.
- 자체 `ScheduledThreadPoolExecutor`(1 스레드, `geo-timer`, removeOnCancel) 가 **신호만** 보내고, 실제 완료는 callbackExecutor 로 넘긴다. 타이머 스레드에서는 blocking 없음.
- 시간 초과 시 `request.cancel(true)` 로 전송 자원 반납(R10: future 를 실패시키는 것만으로는 전송이 멈추지 않는다).
- **경쟁 주의**: 결과를 정하는 사건(응답, 타임아웃, close, 그리고 타임아웃이 일으킨 전송 취소)이 여럿이다. 각 사건이 따로 `callbackExecutor` 로 넘기면 순서가 보장되지 않아, 타임아웃 직후의 `request.cancel` 이 만든 CancellationException 이 TimeoutException 보다 먼저 전달될 수 있다(실제로 재현됨). 내부 future 하나에 **동기적으로 먼저 확정**(first-wins)한 뒤 한 번만 전달한다.

## 4. 취소
- javadoc: "`stage.toCompletableFuture().cancel(..)` 은 이 조회의 전송 요청을 취소한다. 다른 조회에는 영향이 없다."
- 경로: 반환한 CF 에 `whenComplete` 를 붙여, 어떤 이유로든 끝나면 타이머를 취소하고 `request.cancel(true)` 한다(이미 끝난 요청이면 no-op). 취소는 `thenXxx` 사슬을 거슬러 올라가지 않으므로 직접 전파해야 한다(R10).

## 5. 실패 표현
- 비동기 API 의 사용자는 오류 처리를 stage 에 모아 둔다. 메서드가 던지면 try/catch 와 `exceptionally` 두 군데를 다 써야 한다. 그래서 입력 오류(IllegalArgumentException)·종료(IllegalStateException)도 실패한 stage 로 준다.
- 원인은 `completeExceptionally(원래 예외)` 로 직접 넣는다. 사용자의 첫 콜백은 원래 예외를 그대로 받고, 그 뒤 단계나 `join()` 에서는 `CompletionException` 으로 감싸진다(R8) — javadoc 에 "unwrap 해서 판별하라"고 적는다.

## 6. close()
- 진행 중인 조회: `IllegalStateException` 으로 실패(callbackExecutor 로 전달) + 전송 요청 취소.
- 이후 `lookup`: 던지지 않고 `IllegalStateException` 으로 실패한 stage.
- 소유한 스레드: 타이머 `shutdownNow()`, 기본 콜백 풀 `shutdown()`.
- 사용자가 준 executor: 종료하지 않는다(소유하지 않은 자원을 닫으면 사용자 앱이 깨진다).

## 7. Java 8 호환
- `orTimeout`/`completeOnTimeout`/`failedFuture`/`copy`/`minimalCompletionStage` 없음 → 이미 자체 타이머를 쓰므로 영향 적음. 실패한 stage 는 `new CompletableFuture<>()` + `completeExceptionally`.
- `String.isBlank()`(11), `record`(16), `Duration` 은 8 에 있음 → `trim().isEmpty()`, 일반 final 클래스로.
- JDK 8~24 에서 commonPool 병렬도 ≤ 1 이면 Async 기본 executor 가 작업마다 새 스레드라는 점도 commonPool 을 쓰지 않을 근거(버전표).
<!-- ANSWER-END -->
