package async.m08.probe;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import async.harness.FakeBlockingService;
import async.harness.Pools;
import async.harness.Pools.NamedPool;

/** C02 관측: 빈 캐시에서 find("p-1"), productDb 150ms, db 4. */
public final class C02Probe implements AutoCloseable {

    public final FakeBlockingService productDb = new FakeBlockingService("product-db", 150);
    public final NamedPool dbPool = Pools.fixed("db", 4);
    public long firstReturnMillis;
    public Outcome<String> first;
    public long secondReturnMillis;
    public CompletableFuture<String> secondFuture;

    public Diagnosis run(Function<String, CompletableFuture<String>> find) {
        long start = System.nanoTime();
        CompletableFuture<String> f = find.apply("p-1");
        firstReturnMillis = Probes.since(start);
        first = Outcome.await(f, 2000);

        long start2 = System.nanoTime();
        secondFuture = find.apply("p-1");
        secondReturnMillis = Probes.since(start2);

        return new Diagnosis("C02")
                .measured("첫 호출(캐시 미스) 결과: %s, productDb 호출 스레드: %s", first, productDb.callerThreads())
                .callerBlocked(firstReturnMillis)
                .completed(first.completed() ? 1 : 0, 1, 2000);
    }

    @Override
    public void close() {
        dbPool.close();
    }
}
