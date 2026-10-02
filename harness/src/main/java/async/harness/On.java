package async.harness;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 어떤 코드가 "어느 스레드(풀)에서" 실행됐는지를 나타내는 값.
 *
 * <p>스레드 이름은 실행마다 번호가 달라지므로(io-1, io-2 ...) 예측은 풀 단위로 한다.
 * 예측 시트에서는 아래 팩토리 메서드만 사용한다.
 *
 * <pre>{@code
 * On.caller()            // 파이프라인을 조립(호출)한 스레드. 테스트에서는 테스트 메서드를 실행 중인 스레드
 * On.commonPool()        // ForkJoinPool.commonPool 워커
 * On.pool("io")          // Pools.fixed("io", n) 등으로 만든 이름 있는 풀 (io-1, io-2 ... 모두 "io")
 * On.eventLoop()         // FakeEventLoopClient 의 이벤트 루프 스레드 (= On.pool("event-loop"))
 * On.timeoutScheduler()  // orTimeout/completeOnTimeout 을 발화시키는 JDK 내부 스케줄러 스레드
 * On.freshThread()       // 작업마다 새로 만들어지는 이름 없는 스레드 (Thread-0, Thread-1 ...)
 * On.virtualThread()     // 가상 스레드
 * }</pre>
 */
public final class On {

    private static final Pattern TRAILING_NUMBER = Pattern.compile("-\\d+$");
    private static final Pattern FRESH_THREAD = Pattern.compile("^Thread-\\d+$");

    private final String label;

    private On(String label) {
        this.label = Objects.requireNonNull(label);
    }

    public static On caller() {
        return new On("caller");
    }

    public static On commonPool() {
        return new On("commonPool");
    }

    public static On pool(String poolName) {
        return new On(poolName);
    }

    public static On eventLoop() {
        return new On(FakeEventLoopClient.THREAD_PREFIX);
    }

    public static On timeoutScheduler() {
        return new On("timeoutScheduler");
    }

    public static On freshThread() {
        return new On("freshThread");
    }

    public static On virtualThread() {
        return new On("virtualThread");
    }

    /**
     * 실제 스레드를 분류한다.
     *
     * <ul>
     *   <li>JDK 9~24: orTimeout 은 {@code CompletableFutureDelayScheduler} 스레드에서 발화</li>
     *   <li>JDK 25+: {@code ForkJoinPool.commonPool-delayScheduler} 스레드에서 발화</li>
     * </ul>
     * 두 경우 모두 {@link #timeoutScheduler()} 로 분류해 JDK 버전과 무관하게 예측할 수 있게 한다.
     */
    public static On classify(Thread thread, Thread callerThread) {
        if (thread == callerThread) {
            return caller();
        }
        if (isVirtual(thread)) {
            return virtualThread();
        }
        String name = thread.getName();
        if (name.equals("CompletableFutureDelayScheduler") || name.endsWith("-delayScheduler")) {
            return timeoutScheduler();
        }
        if (name.startsWith("ForkJoinPool.commonPool-worker")) {
            return commonPool();
        }
        if (FRESH_THREAD.matcher(name).matches()) {
            return freshThread();
        }
        return new On(TRAILING_NUMBER.matcher(name).replaceFirst(""));
    }

    private static boolean isVirtual(Thread thread) {
        // harness 는 --release 17 로 컴파일되므로 Thread.isVirtual() 을 리플렉션으로 호출한다.
        try {
            return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public String label() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof On && ((On) o).label.equals(label);
    }

    @Override
    public int hashCode() {
        return label.hashCode();
    }

    @Override
    public String toString() {
        return "On(" + label + ")";
    }
}
