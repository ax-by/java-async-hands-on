# 3장. Netty 4.2.15 프렐류드: Lettuce·AWS SDK·DataStax 밑에 깔린 스레드 모델

> 예측: N1~N4, Q_NETTY_* · 구현: E5 · 규칙: O3, O4, R1, R5, R8, R10
> 이 장은 **전체 읽기 대상이 아니다.** 4~6장을 읽기 위한 최소한만 본다.
> 소스 루트: `netty-common-4.2.15.Final-sources.jar`(`io/netty/util/concurrent/`), `netty-transport-4.2.15.Final-sources.jar`(`io/netty/channel/`).

## 0. 이 장에서 답할 질문

- 채널과 이벤트 루프 스레드는 어떤 관계인가? 그 스레드를 막으면 무엇이 멈추는가?
- Netty의 `Future`/`Promise`는 CompletableFuture/CompletionStage인가? 아니라면 Lettuce·AWS·DataStax는 어떻게 CF를 만들어 사용자에게 주는가?
- Netty 리스너는 어느 스레드에서 실행되는가? CF의 R1(완료시킨 스레드 또는 등록한 스레드)과 같은가?
- 이벤트 루프 안에서 promise를 기다리면 어떻게 되는가?

## 1. 읽기 과제

| # | 위치 | 찾을 것 |
|---|---|---|
| 1 | `io/netty/util/concurrent/Future.java:26, 47, 55, 87, 101, 160, 168` | 무엇을 확장하는가? `thenApply` 같은 조합 API가 있는가? |
| 2 | `io/netty/util/concurrent/DefaultPromise.java:190-202` (`addListener`) | 이미 완료된 promise에 리스너를 붙이면 무엇을 호출하는가? → N2 |
| 3 | `io/netty/util/concurrent/DefaultPromise.java:498-520` (`notifyListeners`) | 리스너를 "지금 이 스레드에서" 실행하는 조건과, 그렇지 않을 때 어디로 보내는지 → N1, N2. 52-53행의 깊이 제한은 무엇을 막는가? |
| 4 | `io/netty/util/concurrent/DefaultPromise.java:646-655` (`setValue0`) | 완료 시 리스너 통지는 어떤 경로로 가는가? |
| 5 | `io/netty/util/concurrent/DefaultPromise.java:877-883` (`safeExecute`) | 루프가 종료되어 제출이 거절되면 리스너는 어떻게 되는가? |
| 6 | `io/netty/util/concurrent/DefaultPromise.java:474-479`, `252-275`, `417-422` | `await`/`sync`가 호출하는 검사와 그 조건 → N4, Q_NETTY_DEADLOCK_CHECK (O4) |
| 7 | `io/netty/channel/DefaultChannelPromise.java:56-64, 156-161` | ChannelPromise의 executor는 무엇인가? deadlock 검사를 건너뛰는 경우는? |
| 8 | `io/netty/util/concurrent/DefaultPromise.java:391-406` (`cancel`) | `mayInterruptIfRunning`의 의미는? 취소는 어떤 원인으로 완료되는가? → Q_NETTY_MAY_INTERRUPT |
| 9 | `io/netty/util/concurrent/ImmediateEventExecutor.java:26-32, 60-68` | `inEventLoop()`은 무엇을 반환하는가? 그러면 3번의 규칙은 어떻게 동작하는가? → N3 |
| 10 | `io/netty/channel/AbstractChannel.java:323-364` (`AbstractUnsafe.register`) | 채널은 언제, 어떤 이벤트 루프에 묶이는가? 두 번 등록하면? |
| 11 | `io/netty/channel/MultithreadEventLoopGroup.java:39-41, 71-74` + `io/netty/util/concurrent/DefaultThreadFactory.java:93, 103-105` | 기본 스레드 수와 스레드 이름 형식 → N1~N2의 스레드 이름 |
| 12 | `io/netty/channel/ChannelFuture.java:86-121` (Javadoc) | 핸들러 안에서 하지 말라는 것은? |

> Netty 4.2.15의 `netty-common`과 `netty-transport` 전체를 `CompletionStage|CompletableFuture|asStage`로 grep하면 결과가 0건이다. CompletionStage 브리지는 Netty 5 알파(패키지 `io.netty5`, 예: `netty5-common:5.0.0.Alpha5`의 `Future.asStage()`)에서 시도된 것이고 4.x에는 없다.

## 2. 파이프라인 지도 (4~6장의 공통 밑그림)

README 양식 중 "완료 지점", "non-async 콜백", "보호 장치"를 Netty promise에 대해 채운다. 특히 "누가 완료시키든 리스너는 어디서 실행되는가"를 R1과 나란히 적는다.

(여기에 작성)

## 3. 어댑터 리뷰 포인트 (E5 전에 읽기)

Netty Future → CF 어댑터는 이 생태계에서 가장 많이 복제되는 코드다. 실제 구현을 비교하라.

| 구현 | 위치 | 관찰할 점 |
|---|---|---|
| Lettuce `Futures.toCompletionStage` | `lettuce-core-7.5.2: io/lettuce/core/internal/Futures.java:70-94` | 성공 시 무엇으로 완료하는가? 어떤 future에만 안전한가? 이미 완료된 경우를 어떻게 처리하는가? 취소는? |
| Lettuce `Futures.adapt` | 같은 파일 `103-120` | Javadoc("value result")과 구현이 일치하는가? 일치하지 않는다면 이슈/PR 거리인가? |
| AWS SDK `NettyUtils.promiseNotifyingBiConsumer` | `netty-nio-client-2.55.10: software/amazon/awssdk/http/nio/netty/internal/utils/NettyUtils.java:200-213` | 반대 방향(CF → Netty Promise). 변환 함수가 던지는 예외는 어떻게 되는가? |
| DataStax `CompletableFutures.propagateCancellation` | `java-driver-core-4.19.3: com/datastax/oss/driver/internal/core/util/concurrent/CompletableFutures.java:185-198` | 취소를 다른 stage로 전달하는 헬퍼. 왜 필요한가(R10)? |

어댑터 체크리스트: ① 값 보존 ② 원인 예외를 감싸지 않음(R8) ③ 취소 ↔ 취소 매핑(양방향 여부는 의도적으로 결정) ④ 이미 완료된 future 처리 ⑤ **의존 단계가 어느 스레드에서 실행되는지 문서화**(O1).

(여기에 작성)

## 4. "왜 이렇게 설계했나" 질문

1. Netty 리스너는 누가 완료시키든·언제 등록하든 같은 스레드에서 실행되는가(N1~N3)? 그 규칙은 핸들러 코드 작성자에게 어떤 보장을 주는가(또는 주지 못하는가)?
2. 왜 `notifyListeners`는 스택 깊이를 제한하고 넘으면 루프에 제출할까? CF에는 이런 장치가 있는가? 없다면 어떤 코드에서 문제가 될 수 있을까?
3. 루프 안의 `sync()`/`await()`와 루프 안의 JDBC 호출을 Netty는 각각 감지하는가(N4)? 차이가 있다면 왜인가?
4. Netty 4가 CompletionStage를 구현하지 않은 이유를 추측하라(Java 버전, 그리고 Netty의 리스너 실행 규칙이 R1과 같은지).

(여기에 작성)

## 5. 기여 시뮬레이션: E5 `NettyFutures.toCompletableFuture`

위 체크리스트 ①~⑤를 만족하는 어댑터를 쓴다. 테스트는 진짜 이벤트 루프와 loopback 소켓(`Bootstrap.connect`)으로 성공·실패(연결 거절)·취소·이미 완료된 future·의존 단계 실행 스레드를 검사한다.
