package async.m08.probe;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.harness.Sleep;
import async.m08.Symptom;

/** C08 관측: SKU = commonPool 병렬도 × 2, imageCdn 300ms, image 풀 32(이상). 도중에 무관한 commonPool 작업 1개. */
public final class C08Probe implements AutoCloseable {

    public final int skuCount = ForkJoinPool.getCommonPoolParallelism() * 2;
    public final List<String> skus = IntStream.range(0, skuCount).mapToObj(i -> "sku-" + i).collect(Collectors.toList());
    public final FakeBlockingService imageCdn = new FakeBlockingService("image-cdn", 300);
    public final NamedPool imagePool = Pools.fixed("image", Math.max(32, skuCount));
    public Outcome<List<String>> outcome;
    public long pingDelayMillis;

    public Diagnosis run(Function<List<String>, CompletableFuture<List<String>>> thumbnails) {
        CompletableFuture<List<String>> f = thumbnails.apply(skus);
        Sleep.ms(30);
        long submitted = System.nanoTime();
        CompletableFuture<Long> ping = CompletableFuture.supplyAsync(System::nanoTime); // 다른 팀 코드의 작은 commonPool 작업
        Outcome<Long> pingOutcome = Outcome.await(ping, 5000);
        pingDelayMillis = pingOutcome.succeeded() ? (pingOutcome.value() - submitted) / 1_000_000 : 5000;
        outcome = Outcome.await(f, 5000);
        boolean usedCommonPool = imageCdn.callerThreads().stream().anyMatch(t -> t.startsWith("ForkJoinPool.commonPool"));
        return new Diagnosis("C08")
                .measured("SKU %d개, imageCdn 호출 스레드 예: %s (commonPool 사용=%s)", skuCount,
                        imageCdn.callerThreads().stream().sorted().limit(3).collect(Collectors.toList()), usedCommonPool)
                .completed(outcome.completed() ? 1 : 0, 1, 5000)
                .flagIf(pingDelayMillis >= Diagnosis.COMMON_POOL_DELAY_MS, Symptom.COMMON_POOL_STARVATION,
                        "무관한 commonPool 작업의 시작 지연 %dms (기준 ≥ %dms)", pingDelayMillis, Diagnosis.COMMON_POOL_DELAY_MS);
    }

    @Override
    public void close() {
        imagePool.close();
    }
}
