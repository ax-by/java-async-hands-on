package async.m11.p3;

/** 제재 대상 스크리닝에 걸렸다. 견적을 내면 안 된다. (주어진 타입) */
public class SanctionsHitException extends RuntimeException {

    public SanctionsHitException(String message) {
        super(message);
    }
}
