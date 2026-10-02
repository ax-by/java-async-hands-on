package async.m08.probe;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;
import async.m08.Symptom;

/** C18 관측: taskExecutor 4(큐 무제한), orderDb 50ms, inventoryDb 50ms. 동시 1건, 그다음 8건. 최대 1.5초. */
public final class C18Probe implements AutoCloseable {

    public final FakeBlockingService orderDb = new FakeBlockingService("order-db", 50);
    public final FakeBlockingService inventoryDb = new FakeBlockingService("inventory-db", 50);
    public final NamedPool taskExecutor = Pools.fixed("task", 4);
    public Outcome<String> single;
    public List<Outcome<String>> peak;

    public Diagnosis run(Function<String, CompletableFuture<String>> checkout) {
        single = Outcome.await(checkout.apply("o-0"), 1500);

        List<CompletableFuture<String>> fs = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> checkout.apply("o-" + i)).collect(Collectors.toList());
        peak = Probes.awaitAll(fs, 1500);
        int done = Probes.completedCount(peak);
        int active = taskExecutor.getActiveCount();
        int queued = taskExecutor.queuedCount();
        boolean deadlock = done < 8 && active == 4 && queued > 0;
        if (done < 8) {
            Probes.rescue(taskExecutor);   // 멈춘 스레드를 풀어 주고 정리한다
        }
        return new Diagnosis("C18")
                .measured("동시 1건: %s", single)
                .flagIf(deadlock, Symptom.STARVATION_DEADLOCK,
                        "동시 8건: 1.5초 안에 완료 %d/8, task 풀 active %d/4, 큐 대기 %d건 (모든 스레드가 큐 안의 작업을 기다림)",
                        done, active, queued)
                .completed(single.completed() ? done + 1 : done, 9, 1500);
    }

    @Override
    public void close() {
        taskExecutor.close();
    }
}
