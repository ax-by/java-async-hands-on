package async.m04.probe;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import async.harness.On;
import async.harness.Sleep;

/**
 * C01. <b>자식 JVM</b>에서 실행되는 시나리오. commonPool 병렬도를 2로 고정해서 실행한다
 * ({@code -Djava.util.concurrent.ForkJoinPool.common.parallelism=2}). 그래서 CPU 개수와 상관없이 결과가 같다.
 *
 * <p>어떤 팀이 "외부 API 호출"(1000ms blocking)을 executor 없이 {@code supplyAsync} 로 commonPool 워커 수만큼 넣었다.
 * 50ms 뒤, 전혀 관계없는 두 코드가 실행된다.
 * <ol>
 *   <li>다른 팀의 {@code supplyAsync(가벼운 계산)} — 언제 시작될까? (supplyAsyncStartMs: 시나리오 시작 후 ms)</li>
 *   <li>다른 팀의 parallel stream: 100ms 걸리는 원소 6개 — 얼마나 걸릴까? (parallelStreamMs)
 *       어떤 스레드들이 처리했을까? (parallelStreamRanOn)</li>
 * </ol>
 * 결과는 {@code key=value} 줄로 표준 출력에 쓴다. <b>이 파일은 수정하지 않는다.</b>
 */
public final class CommonPoolStarvationProbe {

    private CommonPoolStarvationProbe() {
    }

    public static void main(String[] args) {
        Thread caller = Thread.currentThread();
        int parallelism = ForkJoinPool.getCommonPoolParallelism();
        CompletableFuture.runAsync(() -> { }).join();           // (측정 장치) commonPool 예열

        long start = System.nanoTime();
        for (int i = 0; i < parallelism; i++) {
            CompletableFuture.runAsync(() -> Sleep.ms(1000));     // blocking 호출을 commonPool 에
        }
        Sleep.ms(50);

        CompletableFuture<Long> light = CompletableFuture.supplyAsync(() -> (System.nanoTime() - start) / 1_000_000);

        Set<String> ranOn = ConcurrentHashMap.newKeySet();
        long streamStart = System.nanoTime();
        IntStream.range(0, 6).parallel().forEach(i -> {
            ranOn.add(On.classify(Thread.currentThread(), caller).label());
            Sleep.ms(100);
        });
        long parallelStreamMs = (System.nanoTime() - streamStart) / 1_000_000;

        System.out.println("commonPoolParallelism=" + parallelism);
        System.out.println("parallelStreamMs=" + parallelStreamMs);
        System.out.println("parallelStreamRanOn=" + String.join(",", new TreeSet<>(ranOn)));
        System.out.println("supplyAsyncStartMs=" + light.join());
    }
}
