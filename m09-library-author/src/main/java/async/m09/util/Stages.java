package async.m09.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Java 8 에서 쓸 수 있는 CompletionStage 유틸리티. (이 모듈의 main 은 {@code --release 8} 이다.)
 */
public final class Stages {

    private Stages() {
    }

    /** CompletionException / ExecutionException 껍질을 벗긴다 (R8). */
    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * E1. Java 9 {@code source.copy()} 의 Java 8 대체를 만든다. 공개 API 가 내부 future 대신 이것을 반환한다.
     *
     * <p>요구사항
     * <ol>
     *   <li>호출할 때마다 <b>새 future</b> 를 반환한다. 호출자가 사본에 {@code complete}/{@code obtrudeValue}/{@code cancel}
     *       을 해도 {@code source} 와 <b>다른 호출자의 사본</b>은 영향을 받지 않는다.</li>
     *   <li>{@code source} 의 결과를 그대로 전달한다. 실패라면 <b>감싸지 않은 원인</b>으로 완료한다.
     *       즉 사본에 바로 붙인 {@code exceptionally} 는 {@code CompletionException} 이 아니라 원래 예외를 받는다.</li>
     * </ol>
     * 생각해 볼 것: {@code source.thenApply(x -> x)} 는 1 과 2 를 각각 만족하는가? 왜 그런가? (R6, R8)
     */
    public static <T> CompletableFuture<T> copyOf(CompletableFuture<T> source) {
        // TODO(E1): new CompletableFuture<>() + source.whenComplete(...) 로 결과를 "중계"한다. 실패는 unwrap 해서 (L1, R8)
        throw new UnsupportedOperationException("TODO");
    }
}
