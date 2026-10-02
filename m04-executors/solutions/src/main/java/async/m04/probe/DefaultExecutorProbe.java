package async.m04.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

import async.harness.On;
import async.harness.Sleep;

/**
 * V01. <b>자식 JVM</b>에서 실행되는 시나리오. CPU 1개 컨테이너를 흉내 내기 위해 {@code -XX:ActiveProcessorCount=1} 로 실행한다.
 * 같은 코드를 JDK 21(운영 Java 17 과 같은 동작)과 JDK 25 로 각각 실행한다.
 *
 * <p>executor 를 생략한 {@code supplyAsync} 로 100ms 걸리는 blocking 작업 5개를 <b>한꺼번에</b> 제출하고 모두 기다린다.
 * <ul>
 *   <li>thread: 작업이 실행된 스레드 분류 (On 라벨)</li>
 *   <li>totalMs: 5개가 모두 끝날 때까지 걸린 시간</li>
 * </ul>
 * 결과는 {@code key=value} 줄로 표준 출력에 쓴다. <b>이 파일은 수정하지 않는다.</b>
 */
public final class DefaultExecutorProbe {

    private DefaultExecutorProbe() {
    }

    public static void main(String[] args) {
        Thread caller = Thread.currentThread();
        Set<String> labels = ConcurrentHashMap.newKeySet();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        long start = System.nanoTime();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(CompletableFuture.runAsync(() -> {      // executor 생략
                labels.add(On.classify(Thread.currentThread(), caller).label());
                threadNames.add(Thread.currentThread().getName());
                Sleep.ms(100);                                 // blocking I/O 라고 생각하라
            }));
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("javaVersion=" + System.getProperty("java.version"));
        System.out.println("availableProcessors=" + Runtime.getRuntime().availableProcessors());
        System.out.println("commonPoolParallelism=" + ForkJoinPool.getCommonPoolParallelism());
        System.out.println("thread=" + String.join(",", new java.util.TreeSet<>(labels)));
        System.out.println("distinctThreads=" + threadNames.size());
        System.out.println("totalMs=" + totalMs);
    }
}
