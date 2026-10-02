package async.m08.probe;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** future 를 제한 시간 동안 기다린 결과. 테스트가 절대 영원히 멈추지 않게 한다. */
public record Outcome<T>(boolean completed, T value, Throwable error, long millis) {

    public static <T> Outcome<T> await(CompletableFuture<T> future, long timeoutMillis) {
        long start = System.nanoTime();
        try {
            T v = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return new Outcome<>(true, v, null, Probes.since(start));
        } catch (TimeoutException e) {
            return new Outcome<>(false, null, null, Probes.since(start));
        } catch (ExecutionException e) {
            return new Outcome<>(true, null, e.getCause(), Probes.since(start));
        } catch (CancellationException e) {
            return new Outcome<>(true, null, e, Probes.since(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public boolean succeeded() {
        return completed && error == null;
    }

    public boolean failed() {
        return completed && error != null;
    }

    @Override
    public String toString() {
        if (!completed) {
            return "미완료(" + millis + "ms 기다림)";
        }
        return (error == null ? "성공 " + value : "실패 " + error) + " @" + millis + "ms";
    }
}
