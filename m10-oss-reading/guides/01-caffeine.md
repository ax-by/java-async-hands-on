# 1장. Caffeine 3.2.4: CompletableFuture를 "값"으로 저장하는 라이브러리

> 예측: C1~C6, Q_CAFFEINE_* · 구현: E1, E2 · 규칙: O1, O2, R1, R2, R5, R10, R11
> 소스 루트: `caffeine-3.2.4-sources.jar` (`target/oss-src/` 아래). 경로는 모두 `com/github/benmanes/caffeine/cache/` 기준이다(아래에서는 `cache/`로 줄인다).

## 0. 이 장에서 답할 질문

- `AsyncCache.get(key, loader)`가 돌려주는 future는 **호출마다 새 객체**인가, **캐시에 저장된 객체**인가? 그 차이가 왜 중요한가?
- 로더는 어느 스레드에서 실행되는가? 로더 종류(`Function` vs `(key, executor) -> CF`)에 따라 다른가?
- 로드가 실패하거나, 취소되거나, 타임아웃되면 캐시 항목은 어떻게 되는가? 그 처리는 어느 스레드가 하는가?
- `synchronous()` 뷰의 메서드들은 진행 중인 로드를 만나면 기다리는가?

## 1. 읽기 과제 (순서대로. "찾을 것"에 대한 답을 journal에 파일:줄과 함께 적는다)

| # | 위치 | 찾을 것 |
|---|---|---|
| 1 | `cache/Caffeine.java:349-375` (`executor(Executor)` Javadoc, `getExecutor()`) | executor를 지정하지 않으면 무엇을 쓰는가? → C2 |
| 2 | `cache/LocalAsyncCache.java:78-83` (`get(K, Function)`) | 동기 로더(`Function`)를 무엇으로 감싸서, 어디서 실행하는가? |
| 3 | `cache/LocalAsyncCache.java:91-107` (`get(K, BiFunction, boolean)`) | 비동기 매핑 함수는 누가, 어떤 잠금 안에서 호출하는가? → C3. 메서드가 반환하는 것은 무엇인가(새 future? 맵의 값?) → C1 |
| 4 | `cache/LocalAsyncCache.java:202-242` (default 메서드 하나) | future가 어떤 상태로 끝나면 항목을 지우는가? 지우는 코드는 어떤 콜백 안에 있고, 그 콜백은 어느 스레드에서 실행되는가(R1)? → C4, C5, Q_CAFFEINE_REMOVAL_METHOD |
| 5 | `cache/LocalAsyncCache.java:208-209` | 실패 로그에서 제외하는 예외 두 가지는? 그 선택이 말해 주는 설계 의도는? |
| 6 | `cache/LocalAsyncCache.java:184-200` (`put`) | 이미 실패한 future를 put하면? 미완료 future를 put하면? |
| 7 | `cache/LocalAsyncCache.java:575-579` (동기 뷰 `getIfPresent`) + `cache/Async.java:43-53` (`isReady`, `getIfReady`) | 진행 중인 항목을 만나면 무엇을 반환하는가? 기다리는가? → C6 |
| 8 | `cache/LocalAsyncCache.java:609-612, 623-638` (동기 뷰 `get` → `resolve`) | `resolve`는 future를 어떻게 다루는가? → C6 |
| 9 | `cache/CacheLoader.java:103-119` (`asyncLoad` 기본 구현) | `buildAsync(CacheLoader)`의 로더는 어디서 실행되는가? → E1 |
| 10 | `cache/Async.java:68-108` (`AsyncRemovalListener`) | 비동기 캐시의 removal listener는 어떤 조건에서만 호출되는가? → Q_CAFFEINE_LISTENER_ON_FAILURE |
| 11 | `cache/Async.java:38, 165-214` (`ASYNC_EXPIRY`, `AsyncExpiry`) + `cache/BoundedLocalCache.java:329-332` (`isComputingAsync`) | 진행 중인 항목은 만료·축출 대상인가? 로드가 끝나면 무엇이 바뀌는가(`LocalAsyncCache.java:229`)? |
| 12 | `cache/BoundedLocalCache.java:418-437` (`notifyRemoval`) | 제거 리스너는 어디서 실행되는가? executor 설정이 영향을 주는 곳은 로더 말고 또 어디인가? |

## 2. 파이프라인 지도

README의 "파이프라인 지도 양식"을 Caffeine에 대해 채운다. 반드시 표시할 것: ① 로더 실행 풀 ② 콜백 실행 스레드(등록 시점에 따라 두 가지) ③ 동기 뷰의 blocking 지점 ④ 반환 future의 정체와 그 결과.

(여기에 작성)

## 3. "왜 이렇게 설계했나" 질문

1. 왜 값을 `V`가 아니라 `CompletableFuture<V>`로 저장했을까? 로드가 끝난 뒤 `V`로 바꿔 저장하면 무엇을 잃는가?
2. 실패한 future는 캐시 항목으로 남는가(C4)? Caffeine의 선택을 실패를 캐시하는(negative caching) 선택지와 비교하라.
3. `get(K, Function)`의 로더와 `get(K, BiFunction)`의 매핑 함수는 각각 어느 스레드에서 실행되는가(C2, C3)? 왜 그렇게 설계했을까? BiFunction에 executor를 인자로 넘겨주는 이유는?
4. 동기 뷰의 `getIfPresent`와 `get`은 진행 중인 로드를 각각 어떻게 다루는가(C6)? 각 메서드의 의미로 그 동작을 설명하라.
5. 기본 executor(읽기 과제 1)는 라이브러리 설계로서 적절한가? (docs/review-checklist.md #19와 비교)
6. 반환된 future를 호출자가 `cancel`/`orTimeout`하면 다른 호출자와 캐시 항목에도 영향이 가는가(C5)? 간다면 그것은 API 설계의 구멍인가, 의도인가? 방어하려면 Caffeine은 무엇을 반환했어야 하나(`copy()`? `minimalCompletionStage()`?) 그 대가는?

(여기에 작성)

## 4. 기여 시뮬레이션

- **E1 `ProductCacheFactory.create`**: 팀 공용 캐시 모듈의 기본 설정 PR. `Caffeine.executor`, `maximumSize`, `expireAfterWrite`를 정하고, 왜 그 executor인지를 PR 설명에 쓴다.
- **E2 `TimeoutAwareProductCache.getAsync(id, timeout)`**: "타임아웃 인자 추가" PR. 리뷰어가 물을 질문: "이 호출자의 타임아웃과 cancel이 같은 키를 기다리는 다른 호출자와 캐시 항목에 영향을 주는가?" 읽기 과제 3, 4번을 근거로 답하라.

PR 설명 양식 (journal에 작성):
```
변경: ...
근거: (읽기 과제의 파일:줄을 인용) ...
테스트: ...
```
