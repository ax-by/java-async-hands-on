package async.m10;

import async.harness.On;

/**
 * m10 예측 시트. 각 장의 {@code *Scenarios.java} 와 {@code guides/0N-*.md} 를 읽고 null 을 예측값으로 바꾼다.
 *
 * <p>규칙
 * <ol>
 *   <li>한 장(라이브러리)씩 진행한다. 그 장의 항목을 <b>전부</b> 채운 뒤 그 장의 PredictionTest 를 실행한다. 첫 채점은 ledger 에 고정된다.</li>
 *   <li>스레드 값: {@code On.caller()}, {@code On.commonPool()}, {@code On.pool("<스레드 이름에서 끝의 -숫자를 모두 뗀 것>")}.
 *       예: {@code shop-io-4-1} → {@code On.pool("shop-io")}, {@code report | client-1} → {@code On.pool("report | client")} ({@link Threads} 참고).
 *       풀 이름을 모르면 소스(가이드의 "파일:줄")에서 스레드 팩토리를 찾아라. 그게 이 모듈의 훈련이다.</li>
 *   <li>시간: ms 단위 Long (오차 ±50ms 또는 ±25%). 결과 문자열: 문제에 적힌 형식 그대로 (예외는 단순 클래스 이름, 예: "TimeoutException").</li>
 *   <li>Q_ 로 시작하는 항목은 <b>소스 읽기 퀴즈</b>다. 정답은 테스트 코드에 해시로만 있다. 대소문자·앞뒤 공백·따옴표는 무시된다.</li>
 * </ol>
 */
public final class Predictions {

    private Predictions() {
    }

    // ───────────── 1장 Caffeine (caffeine/CaffeineScenarios, guides/01-caffeine.md) ─────────────

    // C1 같은 키 동시 조회: 로더 호출 횟수, 두 future 가 같은 인스턴스인가
    public static final Integer C1_LOADER_CALLS = /*ANSWER*/ 1 /*END*/;
    public static final Boolean C1_SAME_INSTANCE = /*ANSWER*/ true /*END*/;

    // C2 executor 미지정 캐시: 동기 로더 실행 스레드, 로드 중 등록한 콜백 실행 스레드
    public static final On C2_LOADER = /*ANSWER*/ On.commonPool() /*END*/;
    public static final On C2_WHILE_LOADING = /*ANSWER*/ On.commonPool() /*END*/;

    // C3 비동기 매핑 함수 (key, executor) -> CF 자체를 호출하는 스레드
    public static final On C3_MAPPING_FN = /*ANSWER*/ On.caller() /*END*/;

    // C4 실패한 로드: 직후 getIfPresent 에 값이 있는가, 총 로더 호출 횟수
    public static final Boolean C4_PRESENT_AFTER_FAILURE = /*ANSWER*/ false /*END*/;
    public static final Integer C4_LOADER_CALLS = /*ANSWER*/ 2 /*END*/;

    // C5 A 가 자기가 받은 future 에 orTimeout 을 걸었을 때 B 의 결과 ("value:<값>" 또는 예외 단순 이름)
    public static final String C5_OTHER_CALLER = /*ANSWER*/ "TimeoutException" /*END*/;

    // C6 로드 중인 키를 synchronous() 뷰로: getIfPresent 소요 ms, get(key, fn) 소요 ms
    public static final Long C6_GET_IF_PRESENT_MS = /*ANSWER*/ 0L /*END*/;
    public static final Long C6_GET_MS = /*ANSWER*/ 200L /*END*/;

    // Q 소스 읽기: LocalAsyncCache 에서 로드 future 의 완료 결과(성공/실패/null)에 따라 캐시 항목을 처리하는 default 메서드 이름 (읽기 과제 4, 메서드 이름만)
    public static final String Q_CAFFEINE_REMOVAL_METHOD = /*ANSWER*/ "handleCompletion" /*END*/;
    // Q 소스 읽기: AsyncCache 에서 로드가 실패했을 때 removalListener 가 호출되는가? (yes/no)
    public static final String Q_CAFFEINE_LISTENER_ON_FAILURE = /*ANSWER*/ "no" /*END*/;

    // ───────────── 2장 Kafka (kafka/KafkaScenarios, guides/02-kafka.md) ─────────────

    // K1 thenApply 로 만든 KafkaFuture 의 whenComplete 가 받는 예외 (단순 클래스 이름)
    public static final String K1_AFTER_THEN_APPLY = /*ANSWER*/ "CompletionException" /*END*/;

    // K2 toCompletionStage().toCompletableFuture().complete("x") 의 결과 ("completed" 또는 예외 단순 이름)
    public static final String K2_COMPLETE_ON_STAGE = /*ANSWER*/ "UnsupportedOperationException" /*END*/;

    // K3 stage 에서 꺼낸 CF 에 orTimeout(100ms): 300ms 뒤 isDone 인가
    public static final Boolean K3_OR_TIMEOUT_FIRED = /*ANSWER*/ false /*END*/;

    // K4 stage 에서 꺼낸 CF 를 cancel: 원본 KafkaFuture 가 취소되는가
    public static final Boolean K4_ORIGINAL_CANCELLED = /*ANSWER*/ true /*END*/;

    // K5 브로커 없는 AdminClient 결과에 붙인 whenComplete 의 실행 스레드
    public static final On K5_CALLBACK = /*ANSWER*/ On.pool("kafka-admin-client-thread | adminclient") /*END*/;

    // K6 브로커 없는 KafkaProducer(max.block.ms=300): send() 반환까지 ms, 콜백 실행 스레드
    public static final Long K6_SEND_RETURNED_MS = /*ANSWER*/ 300L /*END*/;
    public static final On K6_CALLBACK = /*ANSWER*/ On.caller() /*END*/;

    // Q 소스 읽기: 브로커가 정상일 때 producer 콜백을 실행하는 스레드 이름의 접두사 (" | clientId" 앞부분)
    public static final String Q_KAFKA_PRODUCER_IO_THREAD = /*ANSWER*/ "kafka-producer-network-thread" /*END*/;
    // Q 소스 읽기: send() 가 돌려준 FutureRecordMetadata.get() 이 결국 기다리는 동기화 도구의 클래스 이름 (단순 이름)
    public static final String Q_KAFKA_RECORD_METADATA_WAIT = /*ANSWER*/ "CountDownLatch" /*END*/;

    // ───────────── 3장 Netty 프렐류드 (netty/NettyScenarios, guides/03-netty-prelude.md) ─────────────

    // N1 이벤트 루프 Promise, completer 가 완료: 리스너 실행 스레드
    public static final On N1_LISTENER = /*ANSWER*/ On.pool("multiThreadIoEventLoopGroup") /*END*/;
    // N2 이미 완료된 이벤트 루프 Promise 에 caller 가 리스너 추가: 리스너 실행 스레드
    public static final On N2_LISTENER = /*ANSWER*/ On.pool("multiThreadIoEventLoopGroup") /*END*/;
    // N3 ImmediateEventExecutor Promise, completer 가 완료: 리스너 실행 스레드
    public static final On N3_LISTENER = /*ANSWER*/ On.pool("completer") /*END*/;
    // N4 이벤트 루프 안에서 같은 루프의 Promise.sync() ("completed" 또는 예외 단순 이름)
    public static final String N4_SYNC_IN_LOOP = /*ANSWER*/ "BlockingOperationException" /*END*/;

    // Q 소스 읽기: DefaultPromise 의 await()/sync() 가 기다리기 전에 호출하는 검사 메서드 이름 (읽기 과제 6)
    public static final String Q_NETTY_DEADLOCK_CHECK = /*ANSWER*/ "checkDeadLock" /*END*/;
    // Q 소스 읽기: DefaultPromise.cancel(true) 는 작업 스레드를 interrupt 하는가? (yes/no)
    public static final String Q_NETTY_MAY_INTERRUPT = /*ANSWER*/ "no" /*END*/;

    // ───────────── 4장 Lettuce (lettuce/LettuceScenarios, guides/04-lettuce.md) ─────────────

    // L1 RedisFuture 에 응답 전에 등록한 콜백의 실행 스레드
    public static final On L1_CALLBACK = /*ANSWER*/ On.pool("lettuce-nioEventLoop") /*END*/;
    // L2 같은 연결의 콜백이 300ms blocking 하는 동안 보낸 다른 GET 의 완료까지 ms
    public static final Long L2_SAME_CONNECTION_MS = /*ANSWER*/ 300L /*END*/;
    // L3 async 콜백 안에서 같은 연결의 sync API 호출 (명령 타임아웃 300ms) 결과
    public static final String L3_SYNC_IN_CALLBACK = /*ANSWER*/ "RedisCommandTimeoutException" /*END*/;
    // L4 publishOnScheduler(true): async 콜백 스레드, reactive onNext 스레드
    public static final On L4_ASYNC = /*ANSWER*/ On.pool("lettuce-nioEventLoop") /*END*/;
    public static final On L4_REACTIVE = /*ANSWER*/ On.pool("lettuce-eventExecutorLoop") /*END*/;

    // Q 소스 읽기: RedisFuture 의 실제 구현 클래스 AsyncCommand 가 상속(extends)하는 JDK 클래스 (단순 이름)
    public static final String Q_LETTUCE_ASYNC_COMMAND_SUPER = /*ANSWER*/ "CompletableFuture" /*END*/;
    // Q 소스 읽기: ClientOptions.isPublishOnScheduler() 를 읽어 실제 동작을 바꾸는 유일한 클래스 (단순 이름)
    public static final String Q_LETTUCE_PUBLISH_ON_READER = /*ANSWER*/ "AbstractRedisReactiveCommands" /*END*/;

    // ───────────── 5장 AWS SDK v2 (aws/AwsScenarios, guides/05-aws-sdk-v2.md) ─────────────

    // A1 기본 설정: 응답 콜백 실행 스레드
    public static final On A1_CALLBACK = /*ANSWER*/ On.pool("sdk-async-response") /*END*/;
    // A2 FUTURE_COMPLETION_EXECUTOR = Runnable::run: 응답 콜백 실행 스레드
    public static final On A2_CALLBACK = /*ANSWER*/ On.pool("aws-java-sdk-NettyEventLoop") /*END*/;
    // A3 apiCallTimeout(200ms) 초과: 원인 예외 단순 이름, 실패 콜백 실행 스레드
    public static final String A3_CAUSE = /*ANSWER*/ "ApiCallTimeoutException" /*END*/;
    public static final On A3_CALLBACK = /*ANSWER*/ On.pool("sdk-ScheduledExecutor") /*END*/;
    // A4 항상 500 + standardRetryStrategy: 서버가 받은 요청 수
    public static final Integer A4_ATTEMPTS = /*ANSWER*/ 3 /*END*/;

    // Q 소스 읽기: SDK 기본 future completion executor 의 작업 큐 용량 (숫자)
    public static final String Q_AWS_QUEUE_CAPACITY = /*ANSWER*/ "1000" /*END*/;
    // Q 소스 읽기: 2.55.10 에서 재시도 설정을 하지 않은 DynamoDB 클라이언트의 기본 최대 시도 횟수 (환경 변수/프로필 설정 없음 가정, 숫자)
    public static final String Q_AWS_DYNAMODB_DEFAULT_ATTEMPTS = /*ANSWER*/ "9" /*END*/;

    // ───────────── 6장 DataStax Java Driver (cassandra/CassandraScenarios, guides/06-datastax-driver.md) ─────────────

    // D1 naive 재귀 페이징: (a) 이미 완료된 페이지들, (b) io 스레드가 나중에 완료하는 페이지들 ("rows=<개수>" 또는 예외 단순 이름)
    public static final String D1_IMMEDIATE = /*ANSWER*/ "StackOverflowError" /*END*/;
    public static final String D1_ASYNC = /*ANSWER*/ "rows=10000" /*END*/;

    // Q 소스 읽기: 세션 이름을 지정하지 않은 첫 세션의 I/O 스레드 이름 접두사 (끝의 -숫자 제외)
    public static final String Q_CASSANDRA_IO_THREAD = /*ANSWER*/ "s0-io" /*END*/;
    // Q 소스 읽기: 드라이버 스레드(콜백 안)에서 session.execute(동기)를 호출하면 던지는 예외 (단순 이름)
    public static final String Q_CASSANDRA_SYNC_ON_DRIVER_THREAD = /*ANSWER*/ "IllegalStateException" /*END*/;
    // Q 소스 읽기: reference.conf 의 기본 RequestThrottler 클래스 (단순 이름)
    public static final String Q_CASSANDRA_DEFAULT_THROTTLER = /*ANSWER*/ "PassThroughRequestThrottler" /*END*/;
    // Q 소스 읽기: 동시성 제한 throttler 의 대기열까지 가득 찼을 때 요청이 실패하는 예외 (단순 이름)
    public static final String Q_CASSANDRA_THROTTLED = /*ANSWER*/ "RequestThrottlingException" /*END*/;
}
