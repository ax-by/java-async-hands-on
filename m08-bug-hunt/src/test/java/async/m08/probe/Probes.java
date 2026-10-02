package async.m08.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;

/** 프로브 공용 도구. */
public final class Probes {

    private Probes() {
    }

    public static long since(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 여러 future 를 하나의 마감 시각까지 기다린다. */
    public static <T> List<Outcome<T>> awaitAll(List<CompletableFuture<T>> futures, long boundMillis) {
        long deadline = System.nanoTime() + boundMillis * 1_000_000;
        List<Outcome<T>> out = new ArrayList<>();
        for (CompletableFuture<T> f : futures) {
            long left = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            out.add(Outcome.await(f, left));
        }
        return out;
    }

    public static int completedCount(List<? extends Outcome<?>> outcomes) {
        return (int) outcomes.stream().filter(Outcome::completed).count();
    }

    /** 조건이 참이 될 때까지 최대 boundMillis 동안 기다린다. */
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

    /**
     * 동기 메서드를 "요청 스레드"(blocking 이 허용되는 스레드)에서 실행하고 제한 시간 동안 기다린다.
     * 학습자의 구현이 영원히 멈춰도 테스트는 멈추지 않는다.
     */
    public static <T> Outcome<T> onRequestThread(Callable<T> work, long boundMillis) {
        NamedPool request = Pools.single("request");
        try {
            CompletableFuture<T> f = new CompletableFuture<>();
            request.execute(() -> {
                try {
                    f.complete(work.call());
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
            return Outcome.await(f, boundMillis);
        } finally {
            request.close();
        }
    }

    /**
     * starvation deadlock 에 빠진 풀을 정리한다. 큐에 남은 작업을 꺼내 현재 스레드에서 실행하면
     * 그 작업을 기다리던 풀 스레드들이 풀려난다({@code join} 은 interrupt 로 깨울 수 없기 때문).
     */
    public static void rescue(NamedPool pool) {
        for (Runnable pending : pool.shutdownNow()) {
            try {
                pending.run();
            } catch (Throwable ignored) {
                // 정리 중 실패는 무시한다
            }
        }
    }

    /** 예외의 cause 사슬에 target 이 있는가. */
    public static boolean causeChainContains(Throwable t, Throwable target) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c == target) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
