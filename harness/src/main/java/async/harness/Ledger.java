package async.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 예측의 첫 시도 결과를 {@code journal/ledger.tsv} 에 고정 기록한다.
 *
 * <p>형식: {@code id \t 첫예측 \t 실제 \t HIT|MISS \t 시각}
 * <p>{@code -Psolutions} 로 참고 해답을 검증할 때(시스템 속성 {@code cfhandson.mode=solutions})는 기록하지 않는다.
 */
public final class Ledger {

    private static final String ROOT_MARKER = ".cf-handson-root";

    private Ledger() {
    }

    /** @return 이번 호출이 첫 기록이면 true, 이미 기록이 있으면 false */
    static synchronized boolean recordFirstAttempt(String id, String predicted, String actual, boolean hit) {
        Optional<Path> root = repoRoot();
        // harness 자체 테스트와 해답 검증(-Psolutions)은 기록하지 않는다
        if (id.startsWith("harness.") || root.isEmpty() || "solutions".equals(System.getProperty("cfhandson.mode"))) {
            return true;
        }
        Path ledger = root.get().resolve("journal").resolve("ledger.tsv");
        try {
            Files.createDirectories(ledger.getParent());
            if (Files.exists(ledger)) {
                List<String> lines = Files.readAllLines(ledger, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.startsWith(id + "\t")) {
                        return false;
                    }
                }
            }
            String line = String.join("\t", id, clean(predicted), clean(actual), hit ? "HIT" : "MISS",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)) + "\n";
            Files.writeString(ledger, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }

    /** 현재 작업 디렉터리에서 위로 올라가며 저장소 루트를 찾는다. */
    public static Optional<Path> repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.exists(p.resolve(ROOT_MARKER))) {
                return Optional.of(p);
            }
            p = p.getParent();
        }
        return Optional.empty();
    }
}
