// SOLUTIONS-BRANCH-ONLY: 예측 정답에 해당하는 사실을 단언하므로 solutions/src/test 에만 둔다(학습자용 src/test 에는 없다).
package async.m07vt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * README 의 "버전별 사실"을 고정한다 (예측 채점 아님).
 * JDK 21 이 설치되어 있으면 자식 JVM 으로 같은 코드를 돌려 JDK 21 과 25 의 차이를 확인한다.
 */
@Timeout(60)
class VerifiedFactsTest {

    static final Path JDK21 = Path.of("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java");

    static final String PIN_PROBE = """
            import java.util.concurrent.*;
            public class PinProbe {
                public static void main(String[] a) throws Exception {
                    int n = Runtime.getRuntime().availableProcessors() * 4;
                    long t0 = System.nanoTime();
                    try (var vt = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (int i = 0; i < n; i++) {
                            Object lock = new Object();
                            vt.submit(() -> { synchronized (lock) { Thread.sleep(100); } return null; });
                        }
                    }
                    System.out.println((System.nanoTime() - t0) / 1_000_000);
                }
            }
            """;

    @Test
    void synchronizedNoLongerPinsOnJdk25() {
        long took = VtScenarios.v07();
        assertThat(took).as("JDK 24+ (JEP 491): synchronized 안에서 blocking 해도 캐리어를 놓아준다").isLessThan(250);
    }

    @Test
    void synchronizedPinsOnJdk21(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(JDK21), "JDK 21 이 없으면 건너뛴다");
        Path src = dir.resolve("PinProbe.java");
        Files.writeString(src, PIN_PROBE, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder(JDK21.toString(), src.toString()).redirectErrorStream(true).start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        long took = Long.parseLong(out.lines().reduce((x, y) -> y).orElseThrow());
        assertThat(took).as("JDK 21: synchronized 안의 sleep 은 캐리어를 고정(pin)한다 → 코어 수만큼씩 4라운드 ≈ 400ms").isGreaterThan(300);
    }

    @Test
    void virtualThreadExecutorCloseWaitsForAllTasks_evenAfterFailure() {
        VtScenarios.Services s = VtScenarios.v01Services(); // product 50ms 실패, 나머지 500ms
        long start = System.nanoTime();
        try {
            VtScenarios.withVirtualThreads(s, "k");
        } catch (Exception expected) {
            // product.get() 에서 ExecutionException
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsed).as("get() 은 50ms 에 실패하지만 try-with-resources 의 close() 가 나머지(500ms)를 기다린다").isGreaterThan(400);
        assertThat(s.interruptedTotal()).as("아무것도 취소하지 않는다").isZero();
    }

    @Test
    void virtualThreadsHaveEmptyNamesByDefault() throws Exception {
        try (var vt = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(vt.submit(() -> Thread.currentThread().getName()).get()).isEmpty();
        }
    }
}
