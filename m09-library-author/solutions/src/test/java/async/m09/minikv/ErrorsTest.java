package async.m09.minikv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

/** E3 채점: Errors.toPublic (L6 예외 정책). */
class ErrorsTest {

    @Test
    void e3_publicExceptionIsReturnedAsIs() {
        MiniKvException notFound = new MiniKvException("NOT_FOUND");
        assertThat(Errors.toPublic(notFound)).as("[L6] 이미 공개 예외면 그대로 (다시 감싸면 사용자가 두 번 벗겨야 한다)").isSameAs(notFound);
        MiniKvTimeoutException timeout = new MiniKvTimeoutException("t", new TimeoutException());
        assertThat(Errors.toPublic(timeout)).as("[L6] 하위 타입도 그대로").isSameAs(timeout);
    }

    @Test
    void e3_wrappersAreRemoved() {
        MiniKvException notFound = new MiniKvException("NOT_FOUND");
        assertThat(Errors.toPublic(new CompletionException(notFound)))
                .as("[R8] CompletionException 껍질을 벗긴다").isSameAs(notFound);
        assertThat(Errors.toPublic(new ExecutionException(new CompletionException(notFound))))
                .as("[R8] 여러 겹이라도 모두 벗긴다").isSameAs(notFound);
    }

    @Test
    void e3_cancellationIsKept() {
        CancellationException cancelled = new CancellationException();
        assertThat(Errors.toPublic(cancelled)).as("[L6] 사용자의 취소는 CancellationException 그대로 (isCancelled 의미 유지)").isSameAs(cancelled);
    }

    @Test
    void e3_timeoutBecomesMiniKvTimeoutException() {
        TimeoutException timeout = new TimeoutException("100ms");
        RuntimeException mapped = Errors.toPublic(new CompletionException(timeout));
        assertThat(mapped).as("[L6] TimeoutException(checked) → MiniKvTimeoutException").isExactlyInstanceOf(MiniKvTimeoutException.class);
        assertThat(mapped.getCause()).as("[L6] 원인은 보존한다").isSameAs(timeout);
    }

    @Test
    void e3_otherFailuresBecomeMiniKvException() {
        IOException reset = new IOException("connection reset");
        RuntimeException mapped = Errors.toPublic(reset);
        assertThat(mapped).as("[L6] 나머지는 MiniKvException(타임아웃 하위 타입이 아님)").isExactlyInstanceOf(MiniKvException.class);
        assertThat(mapped.getCause()).as("[L6] 원인은 보존한다").isSameAs(reset);

        NullPointerException npe = new NullPointerException();
        assertThat(Errors.toPublic(new CompletionException(npe)).getCause()).as("[R8, L6] 벗긴 뒤의 원인을 cause 로").isSameAs(npe);
    }
}
