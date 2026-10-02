# m09 API 리뷰 실습: `BadKvClient`

사내 공용 라이브러리 "kv-client v0.1"의 첫 PR이다. 이 라이브러리는 사내 수십 개 서비스(Spring MVC, WebFlux, 배치)가 쓰게 된다. **라이브러리 작성자 관점**(L1~L8, 체크리스트 #17~#19)으로 리뷰하라.

> PR 설명: "비동기 KV 클라이언트를 추가합니다. 같은 키 동시 조회는 캐시로 합쳤고, 동기 API도 제공합니다. 성능을 위해 CompletableFuture를 사용했습니다."

`src/main/java/async/m09/review/BadKvClient.java` (전송 계층 `FakeTransport`는 minikv와 같다. `send`는 non-blocking이고 I/O 스레드가 콜백한다.)

```java
16  public class BadKvClient {
17
18      private final FakeTransport transport;
19      private final Map<String, CompletableFuture<String>> cache = new ConcurrentHashMap<>();
20
21      public BadKvClient(FakeTransport transport) {
22          this.transport = transport;
23      }
24
25      /**
26       * 값을 비동기로 조회한다.
27       */
28      public CompletableFuture<String> getAsync(String key) {
29          return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> fetch(k)));
30      }
31
32      /**
33       * 캐시를 거치지 않고 최신 값을 비동기로 조회한다.
34       */
35      public CompletableFuture<String> getFreshAsync(String key) {
36          return CompletableFuture.completedFuture(fetch(key));
37      }
38
39      /**
40       * 값을 조회한다.
41       */
42      public String get(String key) {
43          return getAsync(key).join();
44      }
45
46      private String fetch(String key) {
47          CompletableFuture<String> response = new CompletableFuture<>();
48          transport.send(key, (value, error) -> {
49              if (error != null) {
50                  response.completeExceptionally(error);
51              } else {
52                  response.complete(value);
53              }
54          });
55          try {
56              return response.get();
57          } catch (Exception e) {
58              throw new RuntimeException("kv fetch failed", e);
59          }
60      }
61  }
```

## 작성 방법

- [리뷰 체크리스트](../docs/review-checklist.md)의 양식을 따른다: `[규칙][#번호] 위치 — 문제 → 증상(누가, 언제, 무엇을 겪는가) → 수정 제안`.
- **증상**을 구체적으로 쓴다. "좋지 않다"가 아니라 "어떤 부하에서 어떤 지표가 어떻게 된다"를 쓴다.
- 지적 사항은 9개 정도다. 다 쓴 뒤 `ReviewEvidenceTest`를 읽고 실행해서 증상을 확인한다.
- 마지막에 "이 PR을 머지하기 위한 최소 수정안"을 공개 API 시그니처와 Javadoc 수준으로 적는다.

## 내 리뷰

(여기에 작성)
