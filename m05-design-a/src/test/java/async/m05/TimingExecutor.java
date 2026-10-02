package async.m05;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * 테스트용 executor 래퍼. 실제 실행은 delegate 에 맡기고, 각 작업이 스레드를 점유한 시간을 기록한다.
 * "기다리기만 하는 작업"(예: 풀 안에서 get(timeout))이 있으면 다운스트림 호출 수보다 오래 걸린 작업이 많아진다.
 */
final class TimingExecutor implements Executor {

    private final Executor delegate;
    private final List<Long> durations = new CopyOnWriteArrayList<>();

    TimingExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(() -> {
            long start = System.nanoTime();
            try {
                command.run();
            } finally {
                durations.add((System.nanoTime() - start) / 1_000_000);
            }
        });
    }

    /** thresholdMillis 이상 스레드를 점유한 (끝난) 작업 수. */
    long tasksLongerThan(long thresholdMillis) {
        return durations.stream().filter(d -> d >= thresholdMillis).count();
    }

    List<Long> durations() {
        return List.copyOf(durations);
    }
}
