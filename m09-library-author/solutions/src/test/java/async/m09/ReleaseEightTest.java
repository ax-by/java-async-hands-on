package async.m09;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** L8: main(라이브러리)은 Java 8 바이트코드여야 하고, Java 9+ API 는 컴파일 단계에서 막혀야 한다. */
class ReleaseEightTest {

    @Test
    void mainClassesAreJava8Bytecode() throws IOException {
        List<Path> classes;
        try (Stream<Path> s = Files.walk(Path.of("target", "classes"))) {
            classes = s.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
        }
        assertThat(classes).isNotEmpty();
        for (Path c : classes) {
            try (InputStream in = Files.newInputStream(c); DataInputStream data = new DataInputStream(in)) {
                data.readInt(); // magic
                data.readUnsignedShort(); // minor
                assertThat(data.readUnsignedShort()).as("[L8] %s 는 major 52(Java 8)여야 한다", c).isEqualTo(52);
            }
        }
    }

    // SOLUTION-REMOVE-BEGIN (S13 예측의 답을 단언하므로 main 에서는 뺀다)
    @Test
    void java9ApisAreRejectedUnderRelease8(@TempDir Path dir) throws Exception {
        assertThat(PredictionTest.compiles(dir.resolve("r8"), "--release", "8")).as("[L8] --release 8 은 orTimeout 을 거부한다").isFalse();
        assertThat(PredictionTest.compiles(dir.resolve("r9"), "--release", "9")).isTrue();
    }
    // SOLUTION-REMOVE-END
}
