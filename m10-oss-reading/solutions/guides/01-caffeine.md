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

<!-- ANSWER-BEGIN -->
**요약**: `AsyncCache<K, V>`는 사실상 `ConcurrentMap<K, CompletableFuture<V>>`다. **진행 중인 로드(in-flight future)도 캐시 항목**이다. 그래서 같은 키 동시 요청은 한 future를 공유한다(중복 제거). 대신 그 future에 대한 **상태 변경(orTimeout, cancel)은 모두에게 전파된다**(O2).

```
 호출 스레드(caller)                         Caffeine.executor (기본 commonPool!)
 ─────────────────                           ──────────────────────────────────
 cache.get(k, fn)
   └ computeIfAbsent(k)  ──(없으면)──► supplyAsync(fn, executor) ──► fn(k) 실행 (blocking 이면 이 스레드 점유, R11)
        │                                                              │
        │ 맵에 저장되는 값 = 이 future F (in-flight)                       ▼
        ◄─────────────── F 반환 (두 번째 호출자도 같은 F) ──────── F.complete(v) / completeExceptionally(e)
                                                                       │  (완료시킨 스레드가 연달아 실행)
 F.thenApply(cb)  (F 미완료 시 등록) ───────────────────────────────►  cb   (R1: executor 스레드)
                                                                       │
                                                  handleCompletion 의 whenComplete:
                                                  value==null → cache.remove(k, F)   (실패/취소/타임아웃/null)
                                                  value!=null → cache.replace(k, F, F) (만료 시간 재설정)
 blocking 경계: synchronous().get(..) = F.join() (호출 스레드 blocking, R5)
 취소/타임아웃: F 를 직접 cancel/orTimeout → F 실패 → 모든 대기자 실패 + 항목 제거 (O2)
```

**실험으로 확인한 사실 (JDK 25, 3회 반복)**

- 같은 키 두 요청 → 로더 1회, `a == b` (같은 인스턴스). (C1)
- 기본 executor: 동기 로더와 그 future의 non-async 콜백 모두 `ForkJoinPool.commonPool-worker-*`. (C2)
- `(key, executor) -> CF` 매핑 함수는 **caller**에서 호출된다. 이 함수 안에서 blocking하면 호출 스레드 + `computeIfAbsent`의 해시 빈 잠금까지 잡는다. (C3)
- 실패 직후 `getIfPresent`는 null, 다음 `get`은 로더를 다시 부른다. (C4)
- **경합 주의**: 실패를 `join()`으로 관찰한 호출자가 곧바로 `get`하면, 제거가 아직 안 끝나 **같은 실패 future를 다시 받을 수 있다.** 제거는 `whenComplete` 콜백이고, CF는 완료 시 나중에 등록된 의존 단계(`join`의 대기자)부터 처리하므로 대기자가 먼저 깨어날 수 있다. E1 테스트가 이 때문에 "제거될 때까지 기다린 뒤" 재요청한다.
- 요청 A가 공유 future에 `orTimeout(50ms)` → 요청 B도 `TimeoutException`, 항목 제거, 다음 요청은 DB 재조회. (C5)
- 동기 뷰: 로드 중인 키의 `getIfPresent`는 0ms에 null, `get(key, fn)`은 로드가 끝날 때까지(200ms) 막힌다. (C6)
<!-- ANSWER-END -->

## 3. "왜 이렇게 설계했나" 질문

1. 왜 값을 `V`가 아니라 `CompletableFuture<V>`로 저장했을까? 로드가 끝난 뒤 `V`로 바꿔 저장하면 무엇을 잃는가?
2. 실패한 future는 캐시 항목으로 남는가(C4)? Caffeine의 선택을 실패를 캐시하는(negative caching) 선택지와 비교하라.
3. `get(K, Function)`의 로더와 `get(K, BiFunction)`의 매핑 함수는 각각 어느 스레드에서 실행되는가(C2, C3)? 왜 그렇게 설계했을까? BiFunction에 executor를 인자로 넘겨주는 이유는?
4. 동기 뷰의 `getIfPresent`와 `get`은 진행 중인 로드를 각각 어떻게 다루는가(C6)? 각 메서드의 의미로 그 동작을 설명하라.
5. 기본 executor(읽기 과제 1)는 라이브러리 설계로서 적절한가? (docs/review-checklist.md #19와 비교)
6. 반환된 future를 호출자가 `cancel`/`orTimeout`하면 다른 호출자와 캐시 항목에도 영향이 가는가(C5)? 간다면 그것은 API 설계의 구멍인가, 의도인가? 방어하려면 Caffeine은 무엇을 반환했어야 하나(`copy()`? `minimalCompletionStage()`?) 그 대가는?

<!-- ANSWER-BEGIN -->
**모범 답안**

1. 진행 중인 로드를 다른 요청이 **찾을 수 있게** 하기 위해서다. 값으로만 저장하면 로드가 끝나기 전까지는 "없음"이라 같은 키 동시 요청이 모두 로더를 호출한다(cache stampede). future를 저장하면 in-flight 중복 제거가 공짜로 생긴다. 대가로 future가 공유 객체가 된다(O2).
2. 일시 장애(타임아웃, DB 순간 장애)를 캐시하면 TTL 동안 모든 요청이 실패한다. 그래서 기본은 제거이고, negative caching이 필요하면 사용자가 `Optional.empty()` 같은 **성공 값**으로 표현하게 했다. null 결과도 같은 이유로 "값 없음 = 저장하지 않음"이다.
3. `Function`은 동기 계산이므로 라이브러리가 실행 위치(executor)를 책임진다. `BiFunction`은 "이미 비동기인 작업을 시작하는 함수"라고 가정한다(예: 비동기 HTTP 호출). 그래서 시작만 하고 즉시 future를 돌려줄 것을 기대하며, 직접 executor를 쓸 수 있도록 cache의 executor를 인자로 준다. 이 함수 안에서 blocking하면 호출 스레드와 computeIfAbsent 잠금을 함께 막는다.
4. `getIfPresent`의 의미는 "지금 있으면 달라"이므로 기다리는 것은 의미에 어긋난다. 진행 중 = "아직 값이 없음"으로 보고 null을 준다. `get`은 "없으면 만들어서라도 달라"이므로 진행 중인 로드를 기다리는(join) 것이 의미에 맞다. 그래서 이벤트 루프 같은 곳에서 동기 뷰의 `get`을 쓰면 blocking이다.
5. 편의를 위한 기본값이지만, blocking 로더(JDBC)를 쓰는 사용자에게는 위험하다(R11). 라이브러리 입장에서 다른 합리적 기본값(전용 풀을 만들면 스레드 수명 관리 책임이 생긴다)이 마땅치 않다는 절충이다. 그래서 사용자는 blocking 로더라면 **반드시 executor를 지정**해야 한다(E1).
6. 의도된 트레이드오프다. 같은 인스턴스를 줘야 할당 없이 빠르고, 중복 제거가 성립한다. 방어하려면 호출마다 `copy()`(Java 9+)나 `minimalCompletionStage()`를 반환해야 하는데, 호출마다 할당이 늘고 사용자가 기대하는 `CompletableFuture` API 일부를 잃는다. 그래서 "공유 future를 변경하지 말라"는 책임이 사용자에게 있다. 사용자 쪽 해결책이 E2다.
<!-- ANSWER-END -->

## 4. 기여 시뮬레이션

- **E1 `ProductCacheFactory.create`**: 팀 공용 캐시 모듈의 기본 설정 PR. `Caffeine.executor`, `maximumSize`, `expireAfterWrite`를 정하고, 왜 그 executor인지를 PR 설명에 쓴다.
- **E2 `TimeoutAwareProductCache.getAsync(id, timeout)`**: "타임아웃 인자 추가" PR. 리뷰어가 물을 질문: "이 호출자의 타임아웃과 cancel이 같은 키를 기다리는 다른 호출자와 캐시 항목에 영향을 주는가?" 읽기 과제 3, 4번을 근거로 답하라.

PR 설명 양식 (journal에 작성):
```
변경: ...
근거: (읽기 과제의 파일:줄을 인용) ...
테스트: ...
```
