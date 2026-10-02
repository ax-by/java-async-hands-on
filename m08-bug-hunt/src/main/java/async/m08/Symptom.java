package async.m08;

/**
 * 버그 사례를 실행했을 때 <b>측정되는</b> 증상. 증상 테스트는 이 값을 하드코딩하지 않고, 측정값(BlockHound 위반,
 * 이벤트 루프 lag, 소요시간, 동시 실행 수, inFlight, 로그 ...)을 이 분류로 바꿔서 채점한다.
 *
 * <p><b>판정 규칙</b>: 각 사례 클래스의 javadoc 에 적힌 "관측 조건"에서 실행했을 때 측정되는 증상을 고른다.
 * 여러 증상이 동시에 측정되면 <b>이 enum 에서 위에 선언된 것</b>이 답이다. 아무것도 측정되지 않으면 {@link #NO_BUG}.
 */
public enum Symptom {

    /** 관측 조건에서 아무 증상도 측정되지 않는다. 코드는 의도대로 동작한다. 과잉 지적도 오답이다. */
    NO_BUG,

    /**
     * 풀의 모든 스레드가 <b>같은 풀의 큐에 있는 작업</b>을 기다리며 멈춘다(thread starvation deadlock).
     * 측정: 제한 시간 안에 완료되지 않음 + active 스레드 == 풀 크기 + 큐가 비지 않음.
     */
    STARVATION_DEADLOCK,

    /**
     * 결과 future 가 제한 시간(관측 조건에 적힌 정상 지연의 수 배) 안에 완료되지 않는다. 풀은 멀쩡하다.
     * 측정: 제한 시간 초과 + 막힌 풀 없음.
     */
    NEVER_COMPLETES,

    /**
     * 이벤트 루프 스레드 위에서 blocking 이 일어나 그 루프가 처리하는 다른 응답까지 늦어진다.
     * 측정: 이벤트 루프 스레드에서 BlockHound 위반 ≥ 1건 또는 이벤트 루프 lag ≥ 100ms.
     */
    EVENT_LOOP_STALL,

    /**
     * future 를 반환하는 메서드가 <b>반환하기 전에</b> 호출 스레드를 막는다.
     * 측정: 메서드 호출이 반환되기까지 ≥ 100ms.
     */
    CALLER_THREAD_BLOCKED,

    /**
     * commonPool 이 blocking 작업으로 가득 차서, 관계없는 commonPool 작업(parallel stream, 다른 라이브러리)이 늦게 시작한다.
     * 측정: 그 사이에 제출한 무관한 commonPool 작업의 시작 지연 ≥ 150ms.
     */
    COMMON_POOL_STARVATION,

    /**
     * 호출자는 타임아웃 덕분에 빨리 응답을 받았지만, 실제 blocking 작업은 계속 실행되며 풀 스레드(와 커넥션)를 점유한다.
     * 측정: 반환된 future 가 모두 완료된 뒤에도 blocking 호출이 진행 중(inFlight ≥ 1).
     */
    TIMEOUT_DOES_NOT_FREE_RESOURCES,

    /**
     * 병렬로 의도한 호출이 하나씩 실행된다.
     * 측정: 최대 동시 실행 수 1, 총 소요시간 ≈ 각 호출 지연의 합.
     */
    SEQUENTIAL_NOT_PARALLEL,

    /**
     * blocking 작업이 끝난 뒤의 <b>가벼운</b> 단계들이 바쁜 풀의 큐 뒤에서 차례를 기다려 응답이 늦어진다.
     * 측정: 첫 요청의 응답시간이 그 요청의 blocking 시간보다 ≥ 100ms 길다.
     */
    HOP_QUEUEING_DELAY,

    /**
     * 반환된 future 가 그것이 대표해야 하는 내부 작업이 끝나기 <b>전에</b> 완료된다.
     * 측정: 결과 future 가 완료된 시점에 내부 작업이 아직 끝나지 않았다.
     */
    COMPLETES_TOO_EARLY,

    /**
     * 코드에 정의된 폴백이 적용되지 않고, 실패가 호출자에게 그대로 전달된다.
     * 측정: 폴백 대상 실패를 주입했는데 결과 future 가 예외로 완료된다.
     */
    FALLBACK_NOT_APPLIED,

    /**
     * 어딘가에서 실패가 일어났는데 호출자도, 로그도 그 실패를 모른다.
     * 측정: 주입한 실패가 실제로 발생했지만 반환된 future 는 성공이고, 그 예외를 담은 로그도 없다.
     */
    EXCEPTION_LOST,

    /**
     * 한 호출자가 반환받은 future 에 한 일(타임아웃, 취소, complete)이 <b>다른 호출자</b>의 결과를 바꾼다.
     * 측정: 자기 시간 예산 안에 값이 도착했는데도 다른 호출자의 행동 때문에 실패(또는 엉뚱한 값)를 받는다.
     */
    SHARED_FUTURE_CORRUPTED,

    /**
     * 파이프라인의 일부 단계에서 요청 컨텍스트(MDC traceId, 인증 정보)가 사라진다.
     * 측정: 그 요청이 남긴 로그 중 traceId 가 없는 줄이 있다.
     */
    CONTEXT_LOST,

    /**
     * 이전 요청의 컨텍스트(인증 정보 등)가 풀 스레드에 남아 <b>다른 작업</b>에서 보인다(보안 사고).
     * 측정: 컨텍스트가 없는 작업이 이전 요청의 사용자로 실행된다.
     */
    CONTEXT_LEAK
}
