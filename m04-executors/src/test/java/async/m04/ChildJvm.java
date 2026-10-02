package async.m04;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 테스트용: 지정한 JDK 와 JVM 옵션으로 main 클래스를 <b>별도 JVM 프로세스</b>에서 실행하고 {@code key=value} 출력을 모은다.
 *
 * <p>commonPool 병렬도나 CPU 개수는 JVM 시작 시에 정해지므로, 같은 테스트 JVM 안에서는 바꿀 수 없다. 그래서 자식 JVM 을 쓴다.
 */
final class ChildJvm {

    static final String DEFAULT_JDK21_HOME = "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home";

    private ChildJvm() {
    }

    /** 테스트를 실행 중인 JDK(이 repo 에서는 25). */
    static Path currentJavaHome() {
        return Path.of(System.getProperty("java.home"));
    }

    /** JDK 21 위치. 환경변수 JAVA21_HOME 이 있으면 그것을, 없으면 기본 설치 경로를 쓴다. 없으면 empty. */
    static Optional<Path> jdk21Home() {
        String env = System.getenv("JAVA21_HOME");
        Path home = Path.of(env != null && !env.isBlank() ? env : DEFAULT_JDK21_HOME);
        return Files.isExecutable(home.resolve("bin").resolve("java")) ? Optional.of(home) : Optional.empty();
    }

    static Map<String, String> run(Path javaHome, List<String> jvmOptions, Class<?> mainClass) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaHome.resolve("bin").resolve("java").toString());
        cmd.addAll(jvmOptions);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(mainClass.getName());
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread pump = new Thread(() -> copy(p.getInputStream(), out), "child-jvm-output");
            pump.setDaemon(true);
            pump.start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AssertionError("자식 JVM 이 30초 안에 끝나지 않았다: " + cmd);
            }
            pump.join(5_000);
            String text = out.toString(StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new AssertionError("자식 JVM 이 실패했다 (exit " + p.exitValue() + "): " + cmd + "\n" + text);
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (String line : text.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith(" ")) {
                    result.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try (in) {
            in.transferTo(out);
        } catch (IOException ignored) {
            // 프로세스 종료 시 스트림이 닫히는 것은 정상이다
        }
    }
}
