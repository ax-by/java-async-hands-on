package async.m05;

/** R0 의 세 가지 선택. 설계 판단 퀴즈(D01~D06)에 쓴다. */
public enum Design {
    /** 병렬 blocking fan-out: 전용 blocking 풀에서 독립 호출을 동시에 실행해 응답시간을 줄인다. */
    A,
    /** end-to-end non-blocking: I/O 를 기다리는 동안 어떤 스레드도 점유하지 않는다. */
    B,
    /** CompletableFuture 를 쓰지 않는다 (동기 코드가 더 단순하고 성능이 같다). */
    NO_CF
}
