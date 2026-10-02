package async.m11;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import async.harness.Sleep;

/** 채점 공용 도구. 모든 대기는 상한이 있다. */
public final class Grading {

    private Grading() {
    }

    public static long since(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 성공 결과를 기다린다. 시간 초과·실패면 무엇이 잘못됐는지 알려 주는 AssertionError. */
    public static <T> T await(CompletionStage<T> stage, long boundMillis, String what) {
        CompletableFuture<T> f = stage.toCompletableFuture();
        try {
            return f.get(boundMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(what + " 가 " + boundMillis + "ms 안에 완료되지 않았다 (타임아웃이 없는 경로가 있거나 deadlock, R10/R11)");
        } catch (ExecutionException e) {
            throw new AssertionError(what + " 가 실패했다: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** 실패를 기다려 unwrap 한 원인을 돌려준다. 성공·시간 초과면 AssertionError. */
    public static Throwable awaitFailure(CompletionStage<?> stage, long boundMillis, String what) {
        CompletableFuture<?> f = stage.toCompletableFuture();
        try {
            Object v = f.get(boundMillis, TimeUnit.MILLISECONDS);
            throw new AssertionError(what + " 는 실패해야 하는데 성공했다: " + v);
        } catch (TimeoutException e) {
            throw new AssertionError(what + " 가 " + boundMillis + "ms 안에 완료되지 않았다");
        } catch (ExecutionException e) {
            return unwrap(e.getCause());
        } catch (java.util.concurrent.CancellationException e) {
            return e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static boolean pollUntil(BooleanSupplier condition, long boundMillis) {
        long deadline = System.nanoTime() + boundMillis * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Sleep.ms(5);
        }
        return condition.getAsBoolean();
    }

    /** 살아 있는 모든 스레드 이름. */
    public static Set<String> liveThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive).map(Thread::getName).collect(Collectors.toSet());
    }
}
