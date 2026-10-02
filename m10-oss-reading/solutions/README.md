# m10. 오픈소스 코드베이스 읽기: CF를 쓰는 라이브러리를 이해하고 기여하기

> 난이도 ★★★★~★★★★★ · 관련 규칙 **R1, R2, R4, R5, R8, R10, R11, R13** + 모듈 규칙 **O1~O4** · 예측 47개(실행 33 + 소스 읽기 퀴즈 14) · 구현 8개

## 이 모듈을 마치면

- 처음 보는 라이브러리라도 **30분 안에 "파이프라인 지도"를 그릴 수 있다**: 어떤 스레드 풀이 있는지, 누가 future를 완료시키는지, 내 콜백은 어디서 실행되는지, 어디가 blocking 경계인지, 예외·타임아웃·취소가 어떤 경로로 흐르는지.
- 라이브러리 고유의 future 타입(KafkaFuture, Netty Future, RedisFuture)이 CompletableFuture 규칙(R1~R13)과 **어디서 다른지** 소스로 확인하고 말할 수 있다.
- 그 라이브러리를 쓰는 코드(또는 그 라이브러리 자체)에 PR을 보낼 때, **라이브러리의 설계 관례를 따르는** 변경을 할 수 있다.

---

## 핵심 개념

### 1. 왜 라이브러리 소스를 읽어야 하는가

m01~m09의 규칙은 "CF 자체"의 규칙이다. 그런데 운영 코드의 future는 대부분 **라이브러리가 만들어서 준다.** 그 future에 대해 R1("non-async 콜백은 완료시키는 스레드에서 실행된다")을 적용하려면 **"이 라이브러리에서는 누가 complete()를 호출하는가"**를 알아야 한다. 이것은 문서에 없는 경우가 많고, 버전마다 바뀐다. 답은 소스에 있다.

| 라이브러리 | 내가 받는 것 | 각 장에서 답할 질문 |
|---|---|---|
| Caffeine `AsyncCache` | `CompletableFuture` | 이 future는 호출마다 새로 만들어지는가? 누가 완료시키는가? |
| Kafka `Admin` | `KafkaFuture` (CF를 숨긴 래퍼) | 어떤 스레드가 완료시키는가? CF로 꺼내면 무엇이 달라지는가? |
| Kafka `Producer.send` | `java.util.concurrent.Future` + `Callback` | `send()`는 정말 막지 않는가? 콜백은 어디서 실행되는가? |
| Netty | `io.netty.util.concurrent.Future` (CF 아님) | 리스너는 어느 스레드에서 실행되는가? R1과 같은가? |
| Lettuce | `RedisFuture` | 응답 콜백은 어디서 실행되는가? 거기서 막으면 무엇이 멈추는가? |
| AWS SDK v2 | `CompletableFuture` | 정상 응답·타임아웃 각각 누가 완료시키는가? |
| DataStax Driver 4 | `CompletionStage<AsyncResultSet>` | 누가 완료시키는가? 다음 페이지를 이어 받는 코드는 안전한가? |

모든 장을 마친 뒤 이 표의 셋째 칸을 "완료시키는 스레드 / blocking 시 멈추는 것 / 보호 장치"로 바꿔 채운다(PREDICT.md 서술 1).

<!-- ANSWER-BEGIN -->
**완성된 표 (실험으로 확인)**

| 라이브러리 | 누가 완료시키는가 |
|---|---|
| Caffeine `AsyncCache` | 로더를 실행한 `Caffeine.executor` 스레드(기본 commonPool). 반환 future는 **캐시에 저장된 바로 그 객체** |
| Kafka `Admin` | `kafka-admin-client-thread \| <clientId>` |
| Kafka `Producer.send` | 보통 `kafka-producer-network-thread \| <clientId>`, 메타데이터 대기 실패 시 **호출 스레드** |
| Netty | 리스너는 항상 **그 future의 이벤트 루프**에서 실행 |
| Lettuce | `lettuce-nioEventLoop-*` (Netty I/O 스레드) |
| AWS SDK v2 | 정상 응답: `sdk-async-response-*` / 타임아웃: `sdk-ScheduledExecutor-*` |
| DataStax Driver 4 | `s0-io-*` (Netty I/O 스레드) |
<!-- ANSWER-END -->

### 2. 모듈 규칙 O1~O4 (라이브러리 읽기 전용 규칙)

| 규칙 | 내용 | 확인 방법 |
|---|---|---|
| **O1 완료 주체** | 라이브러리가 준 future의 non-async 콜백은 **라이브러리가 그 future를 완료시키는 스레드**에서 실행된다(R1의 라이브러리판). 그 스레드가 I/O 스레드라면 콜백 안의 blocking은 **그 스레드가 담당하는 모든 연결**을 멈춘다. | 소스에서 `complete(`/`completeExceptionally(`/`setSuccess(` 호출 지점을 찾고, 그 코드가 어느 스레드의 루프에서 도는지 거슬러 올라간다. 스레드 이름은 `ThreadFactory`/`threadNamePrefix`/`setNameFormat`을 grep한다. |
| **O2 공유 상태** | 라이브러리가 **같은 future 인스턴스를 여러 호출자에게** 돌려줄 수 있다(캐시, 중복 제거). 그 future의 상태를 바꾸는 연산(`orTimeout`, `completeOnTimeout`, `cancel`, `complete`, `obtrude*`)은 **모두에게** 영향을 준다. | 반환 경로를 따라가며 "새 future를 만드는가, 저장된 것을 꺼내 주는가"를 본다. |
| **O3 래퍼 계약** | 라이브러리 고유 future 타입은 CF와 계약이 다르다: 리스너 실행 스레드, 금지된 연산(UnsupportedOperationException), 예외 래핑, 취소 의미. **CF 규칙을 그대로 적용하면 틀린다.** | 래퍼 클래스의 Javadoc과 오버라이드된 메서드를 읽는다. |
| **O4 I/O 스레드 보호 장치** | 라이브러리마다 I/O 스레드를 지키는 장치가 다르다: 사용자 future 완료를 다른 executor로 넘기기, 루프 안에서 자기 future를 기다리면 예외, 드라이버 스레드에 표식을 달아 sync 호출 거부, 또는 **아무것도 없음**(→ 사용자 책임). 장치가 없으면 R4 위반은 **조용히 느려지는 장애**가 된다. 어느 라이브러리가 어느 쪽인지는 각 장에서 찾는다. | "blocking", "event loop", "deadlock" 으로 Javadoc을 grep한다. |

### 3. 소스 읽기 방법 (도구 없이도 되는 순서)

1. **진입점**: 내가 호출하는 public 메서드(`cache.get`, `admin.describeCluster`, `redis.get`, `client.getItem`, `session.executeAsync`)에서 시작한다.
2. **future 생성 지점**: 그 메서드가 반환하는 future가 어디서 `new` 되는지(또는 어디서 꺼내지는지) 찾는다. → O2
3. **완료 지점**: 그 future의 `complete`/`completeExceptionally`를 호출하는 곳을 찾는다. IDE의 *Find Usages*나 `grep -n "complete("`. → O1
4. **완료 지점의 스레드**: 완료 코드를 감싸는 루프(`channelRead`, `run()`, `handleResponses`, `whenCompleteAsync(.., executor)`)를 거슬러 올라가 스레드 팩토리를 찾는다. 스레드 이름 접두사를 적는다.
5. **실패·타임아웃·취소 경로**: 타임아웃을 누가 발화시키는지(별도 스케줄러?), 취소가 어디까지 전파되는지, 예외가 감싸지는지 확인한다. → O3, R8, R10
6. **보호 장치**: blocking을 감지/회피하는 코드가 있는지 확인한다. → O4
7. **지도 그리기**: 각 가이드의 "파이프라인 지도" 양식을 채운다.
8. **실험으로 확인**: 읽은 내용을 예측으로 적고 테스트로 확인한다. 소스 읽기와 실험이 다르면, **실험이 맞다.** 다시 읽어라.

### 4. 실무에서의 증상 (이 모듈에서 재현한다: 결과는 예측한 뒤에 확인하라)

- 캐시 앞단에서 호출자별 `orTimeout`을 걸었다. 같은 상품을 조회하던 **다른 API**에는 무슨 일이 생길까? → C5, E2
- Redis 조회 콜백에서 JDBC로 감사 로그를 남긴다. 그 Redis 연결을 쓰는 **다른 조회**는? → L2, E6
- Redis 콜백 안에서 같은 연결의 sync API를 호출한다. → L3
- Kafka `toCompletionStage()`로 꺼낸 future에 `orTimeout`을 건다. → K3, E3
- 브로커 메타데이터가 없을 때 `producer.send()`를 요청 스레드에서 호출한다. → K6
- AWS SDK를 "최적화"한다며 completion executor를 `Runnable::run`으로 바꾼다. → A2, E7
- 비동기 페이징을 `thenCompose` 재귀로 짰다. 페이지가 이미 도착해 있는 경우에는? → D1, E8

### 5. 파이프라인 지도 양식 (모든 장에서 이 양식을 채운다)

```
[진입점]          내가 호출하는 메서드: ______   반환 future 는 새로 만드는가, 저장된 것을 꺼내 주는가: ______
[완료 지점]       complete()/setSuccess() 호출 위치(파일:줄): ______   그 코드가 도는 스레드(이름 접두사): ______
[non-async 콜백]  응답 전에 등록하면: ______   응답 후에 등록하면: ______
[blocking 경계]   라이브러리 안에서 호출 스레드를 막는 곳: ______
[실패]            예외 타입과 래핑(R8): ______
[타임아웃]        누가 발화시키는가(스레드): ______   진행 중인 실제 작업은 어떻게 되는가: ______
[취소]            어디까지 전파되는가(R10): ______
[보호 장치 O4]    I/O 스레드 blocking 을 막거나 감지하는 장치: ______
```

---

## 진행 방법

### 0. 소스 준비

```bash
# (1) IDE 용: 고정 버전의 sources jar 를 로컬 저장소로 내려받는다 → IntelliJ 에서 클래스를 열면 소스가 보인다
mvn -pl m10-oss-reading dependency:sources

# (2) grep / 줄 번호 확인용: 6개 라이브러리 소스를 한 디렉터리에 푼다
mvn -pl m10-oss-reading dependency:unpack-dependencies -Dclassifier=sources \
    -DincludeGroupIds=com.github.ben-manes.caffeine,org.apache.kafka,io.netty,io.lettuce,software.amazon.awssdk,org.apache.cassandra \
    -DoutputDirectory=target/oss-src
# 결과: m10-oss-reading/target/oss-src/<패키지 경로>/<클래스>.java
```

IntelliJ: `⌘O`(Go to Class)로 클래스를 열고 `⌘L`(Go to Line)로 가이드의 줄 번호로 이동한다. 소스가 안 보이면 에디터 상단의 *Download Sources*를 누른다. `⌥F7`(Find Usages)로 `complete(` 호출 지점을, `⌘⌥B`로 구현 클래스를 찾는다.

**버전 고정**: 가이드의 `파일:줄`은 모두 `pom.xml`에 고정한 아래 버전 기준이다. 버전을 바꾸면 줄 번호가 어긋난다.

| 장 | 라이브러리 | 버전 | 가이드 |
|---|---|---|---|
| 1 | Caffeine | 3.2.4 | [guides/01-caffeine.md](guides/01-caffeine.md) |
| 2 | Kafka clients | 4.3.1 | [guides/02-kafka.md](guides/02-kafka.md) |
| 3 | Netty (프렐류드) | 4.2.15.Final | [guides/03-netty-prelude.md](guides/03-netty-prelude.md) |
| 4 | Lettuce | 7.5.2.RELEASE | [guides/04-lettuce.md](guides/04-lettuce.md) |
| 5 | AWS SDK for Java v2 (DynamoDB, Netty NIO) | 2.55.10 | [guides/05-aws-sdk-v2.md](guides/05-aws-sdk-v2.md) |
| 6 | Apache Cassandra Java Driver (DataStax 4.x) | 4.19.3 | [guides/06-datastax-driver.md](guides/06-datastax-driver.md) |

### 1. 장마다 반복하는 순서

1. 가이드의 **읽기 과제**를 소스를 열고 따라간다. 파이프라인 지도를 종이에(또는 journal에) 그린다.
2. 그 장의 `*Scenarios.java`를 읽고 [PREDICT.md](PREDICT.md)에 **예측과 근거(R/O 규칙 + 소스 위치)**를 적는다.
3. `Predictions.java`의 그 장 항목(소스 읽기 퀴즈 `Q_*` 포함)을 채운다.
4. 채점한다. **첫 채점은 ledger에 고정된다.** 그 장의 항목을 전부 채운 뒤 실행하라.
   ```bash
   mvn -pl m10-oss-reading -am test -Dtest=CaffeinePredictionTest -Dsurefire.failIfNoSpecifiedTests=false
   # 장별: CaffeinePredictionTest, KafkaPredictionTest, NettyPredictionTest,
   #       LettucePredictionTest, AwsPredictionTest, CassandraPredictionTest
   python3 tools/score.py
   ```
5. 가이드의 **"왜 이렇게 설계했나"** 질문에 journal로 답한 뒤 모범답안과 비교한다.
6. 그 장의 **기여 시뮬레이션**(구현 실습)을 한다.
   ```bash
   mvn -pl m10-oss-reading -am test -Dtest=CaffeineExercisesTest -Dsurefire.failIfNoSpecifiedTests=false
   # 장별: CaffeineExercisesTest(E1,E2), KafkaExercisesTest(E3,E4), NettyExercisesTest(E5),
   #       LettuceExercisesTest(E6), AwsExercisesTest(E7), CassandraExercisesTest(E8)
   ```

순서는 1 → 2 → 3 → 4 → 5 → 6을 권장한다. 3장(Netty)은 4~6장의 공통 기반이므로 **건너뛰지 않는다.**

### 2. 구현 실습(기여 시뮬레이션) 목록

| # | 파일 | 시뮬레이션하는 PR | 관련 규칙 |
|---|---|---|---|
| E1 | `caffeine/ProductCacheFactory` | 팀 공용 캐시의 기본 설정: blocking 로더를 전용 executor로 | R11, O1, O2 |
| E2 | `caffeine/TimeoutAwareProductCache` | `getAsync(id, timeout)`: 다른 호출자와 캐시 항목에 영향을 주지 않는 호출자별 타임아웃 | O2, R6, R10 |
| E3 | `kafka/KafkaFutures.toCompletableFuture` | Kafka 2.x도 지원하는 Java 8 스타일 KafkaFuture → CF 브리지 | O3, R8, R10 |
| E4 | `kafka/KafkaFutures.allOf` | 키별 KafkaFuture 맵 → 하나의 future (fail-fast) | R1, R5, R13 |
| E5 | `netty/NettyFutures` | Netty Future → CF 어댑터 (성공/실패/양방향 취소) | O3, R8, R10 |
| E6 | `lettuce/ProductRedisRepository` | blocking 콜백 버그 수정 | R1, R2, R4, O1 |
| E7 | `aws/ProductDynamoClients` | completion executor·재시도·타임아웃을 명시한 클라이언트 설정 | O1, O4, R10 |
| E8 | `cassandra/AsyncPaging` | 드라이버 위에 얹는 non-blocking "모든 페이지 모으기" 유틸 | R1, R5, O4 |

---

## 통과 기준

- 예측 적중률(첫 시도) **85% 이상**. 이 모듈은 처음 보는 코드를 읽고 맞히는 것이므로 기준을 m01보다 조금 낮춘다. 대신 틀린 항목마다 **"소스의 어느 줄을 놓쳤는가"**를 journal에 적는다.
- `*ExercisesTest` 전부 통과.
- 6개 가이드의 파이프라인 지도를 모두 그렸고, PREDICT.md 서술 문제에 답했다.
- 최종 확인: 6개 라이브러리 중 하나를 골라, 가이드를 보지 않고 **빈 종이에 파이프라인 지도를 다시 그릴 수 있다.**

## 실행 명령 (전체)

```bash
mvn -pl m10-oss-reading -am test          # 모든 예측 + 구현 채점
mvn -q -pl m10-oss-reading test           # (harness 가 설치되어 있으면) 이 모듈만
```

모든 실험은 로컬에서만 동작한다(브로커/Redis/DynamoDB/Cassandra 불필요). Kafka는 아무도 듣지 않는 로컬 포트, Lettuce는 테스트 소스의 `FakeRedisServer`(RESP2), AWS는 harness의 `StubHttpServer`, DataStax는 `FakeAsyncPage`를 쓴다.

## 라이선스 메모

이 저장소에는 라이브러리 소스를 복사하지 않는다. 가이드는 `파일:줄` 참조와 몇 줄 이내의 짧은 인용만 쓴다. 전체 코드는 위 명령으로 내려받아 읽는다.
