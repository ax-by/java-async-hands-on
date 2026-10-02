package async.m04;

/** S06 예측용: 같은 풀 안에서 join 하는 파이프라인이 끝나는가, 영원히 멈추는가. */
public enum Outcome {
    /** 모든 요청이 (느리더라도) 완료된다. */
    COMPLETES,
    /** thread starvation deadlock: 어떤 요청도 끝나지 않는다. */
    DEADLOCK
}
