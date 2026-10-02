package async.harness;

import java.util.concurrent.Callable;

/** 소요시간 측정. */
public final class Timed<T> {

    private final T value;
    private final long millis;

    private Timed(T value, long millis) {
        this.value = value;
        this.millis = millis;
    }

    /** 작업을 현재 스레드에서 실행하고 결과와 소요시간(ms)을 반환한다. 작업의 예외는 그대로 전파된다. */
    public static <T> Timed<T> run(Callable<T> work) throws Exception {
        long start = System.nanoTime();
        T v = work.call();
        return new Timed<>(v, (System.nanoTime() - start) / 1_000_000);
    }

    /** 체크 예외를 던지지 않는 작업용. */
    public static <T> Timed<T> of(java.util.function.Supplier<T> work) {
        long start = System.nanoTime();
        T v = work.get();
        return new Timed<>(v, (System.nanoTime() - start) / 1_000_000);
    }

    public T value() {
        return value;
    }

    public long millis() {
        return millis;
    }

    @Override
    public String toString() {
        return value + " (" + millis + "ms)";
    }
}
