package async.m05;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import async.harness.Sleep;

/**
 * (측정 장치) 일정한 간격으로 요청을 보내는 부하 발생기. 요청 하나 = {@code load.apply(productId)} 호출 하나.
 * 호출 스레드 하나가 모든 요청을 보낸다. 그러므로 load 가 호출 스레드를 막으면 부하 자체가 줄어든다(그것도 결함이다).
 * <b>이 파일은 수정하지 않는다.</b>
 */
public final class LoadDriver {

    /** 부하 결과. maxSuccessLatencyMillis 는 성공한 요청 중 가장 느린 요청의 응답시간. */
    public record LoadReport(int succeeded, int failed, long maxSuccessLatencyMillis, long maxSubmitMillis) {

        public LoadOutcome outcome() {
            return LoadOutcome.classify(failed, maxSuccessLatencyMillis);
        }
    }

    private LoadDriver() {
    }

    /**
     * @param requests       보낼 요청 수
     * @param intervalMillis 요청 간격 (10ms 면 100 RPS)
     * @param load           요청 하나를 처리하는 함수. 즉시 future 를 반환해야 한다
     */
    public static LoadReport run(int requests, long intervalMillis, Function<String, CompletableFuture<ProductDetail>> load) {
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong maxLatency = new AtomicLong();
        long maxSubmit = 0;
        List<CompletableFuture<?>> all = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < requests; i++) {
            long due = start + TimeUnit.MILLISECONDS.toNanos(i * intervalMillis);
            long wait = TimeUnit.NANOSECONDS.toMillis(due - System.nanoTime());
            if (wait > 0) {
                Sleep.ms(wait);
            }
            long sent = System.nanoTime();
            CompletableFuture<ProductDetail> f = load.apply("p" + i);
            maxSubmit = Math.max(maxSubmit, (System.nanoTime() - sent) / 1_000_000);
            all.add(f.whenComplete((v, ex) -> {
                if (ex == null) {
                    succeeded.incrementAndGet();
                    maxLatency.accumulateAndGet((System.nanoTime() - sent) / 1_000_000, Math::max);
                } else {
                    failed.incrementAndGet();
                }
            }));
        }
        try {
            CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).handle((v, ex) -> null).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("부하 시나리오가 10초 안에 끝나지 않았다 (전체 타임아웃이 없는가?)", e);
        }
        return new LoadReport(succeeded.get(), failed.get(), maxLatency.get(), maxSubmit);
    }
}
