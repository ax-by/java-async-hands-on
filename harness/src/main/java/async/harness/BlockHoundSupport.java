package async.harness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import reactor.blockhound.BlockHound;

/**
 * BlockHound 연동. "막히면 안 되는 스레드" 위에서 blocking 호출(Thread.sleep, LockSupport.park, 소켓/파일 I/O,
 * Object.wait ...)이 일어나면 기록한다.
 *
 * <p>막히면 안 되는 스레드: 이름이 다음으로 시작하는 스레드
 * <ul>
 *   <li>{@code event-loop-} : {@link FakeEventLoopClient}</li>
 *   <li>{@code nonblocking-} : 실습에서 직접 만드는 "non-blocking 전용" 풀</li>
 *   <li>Netty/Lettuce/AWS SDK/DataStax 의 이벤트 루프 스레드</li>
 * </ul>
 *
 * <p>기본 모드는 {@link Mode#RECORD}: 예외를 던지지 않고 위반만 기록한다(운영에서처럼 "조용히 느려지는" 상황을 재현).
 * {@link Mode#THROW} 로 바꾸면 위반 지점에서 {@code BlockingOperationError} 를 던진다.
 *
 * <pre>{@code
 * BlockHoundSupport.reset();
 * ... 파이프라인 실행 ...
 * assertThat(BlockHoundSupport.violations()).isEmpty();
 * }</pre>
 */
public final class BlockHoundSupport {

    public enum Mode { RECORD, THROW }

    /** 기록된 위반 한 건. */
    public record Violation(String threadName, String blockingMethod) {
        @Override
        public String toString() {
            return threadName + " 에서 blocking 호출: " + blockingMethod;
        }
    }

    public static final List<String> NON_BLOCKING_THREAD_PREFIXES = List.of(
            FakeEventLoopClient.THREAD_PREFIX + "-",
            "nonblocking-",
            "nioEventLoopGroup-",          // Netty 기본
            "multiThreadIoEventLoopGroup-", // Netty 4.2 기본
            "lettuce-nioEventLoop-",
            "lettuce-epollEventLoop-",
            "lettuce-kqueueEventLoop-",
            "aws-java-sdk-NettyEventLoop-",
            "s0-io-"                        // DataStax Java Driver
    );

    private static final List<Violation> VIOLATIONS = new CopyOnWriteArrayList<>();
    private static volatile Mode mode = Mode.RECORD;
    private static volatile boolean installed;
    private static volatile Throwable installFailure;

    private BlockHoundSupport() {
    }

    /** JVM 당 한 번 설치한다. 설치할 수 없는 환경이면 조용히 넘어가고 {@link #isInstalled()} 가 false 가 된다. */
    public static synchronized void installIfAvailable() {
        if (installed || installFailure != null) {
            return;
        }
        try {
            BlockHound.builder()
                    .nonBlockingThreadPredicate(current -> current.or(isNonBlockingThread()))
                    // 스레드 풀/스케줄러가 자기 큐에서 일을 기다리는 park 는 "일이 없어서 쉬는 것"이지 blocking 이 아니다.
                    .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "getTask")
                    .allowBlockingCallsInside("java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue", "offer")
                    .allowBlockingCallsInside("java.util.concurrent.LinkedBlockingQueue", "offer")
                    .allowBlockingCallsInside("java.util.concurrent.ArrayBlockingQueue", "offer")
                    .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "addWorker")
                    .blockingMethodCallback(method -> {
                        Violation v = new Violation(Thread.currentThread().getName(), method.toString());
                        VIOLATIONS.add(v);
                        if (mode == Mode.THROW) {
                            throw new reactor.blockhound.BlockingOperationError(method);
                        }
                    })
                    .install();
            installed = true;
        } catch (Throwable t) {
            installFailure = t;
        }
    }

    private static Predicate<Thread> isNonBlockingThread() {
        return t -> {
            String n = t.getName();
            for (String p : NON_BLOCKING_THREAD_PREFIXES) {
                if (n.startsWith(p)) {
                    return true;
                }
            }
            return false;
        };
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static Throwable installFailure() {
        return installFailure;
    }

    public static void mode(Mode newMode) {
        mode = newMode;
    }

    /** 위반 기록을 비우고 모드를 RECORD 로 되돌린다. 각 테스트 시작 시 호출한다. */
    public static void reset() {
        installIfAvailable();
        VIOLATIONS.clear();
        mode = Mode.RECORD;
    }

    public static List<Violation> violations() {
        return List.copyOf(VIOLATIONS);
    }
}
