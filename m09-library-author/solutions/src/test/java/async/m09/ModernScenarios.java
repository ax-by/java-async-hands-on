package async.m09;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Java 9+ API 가 필요한 예측 시나리오. main 은 {@code --release 8} 이라 이 코드를 컴파일할 수 없어서 test 쪽에 둔다.
 * <b>이 파일은 수정하지 않는다.</b>
 */
public final class ModernScenarios {

    private ModernScenarios() {
    }

    /**
     * S09. Java 9 의 {@code minimalCompletionStage()} 로 보호한 stage 를 호출자가 CompletableFuture 로 <b>캐스팅</b>해서
     * {@code complete("hacked")} 한다. 결과는? (complete 의 반환값 "true"/"false", 또는 던져진 예외의 단순 이름)
     */
    public static String s09() {
        CompletableFuture<String> internal = new CompletableFuture<>();
        CompletionStage<String> stage = internal.minimalCompletionStage();
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<String> casted = (CompletableFuture<String>) stage;
            return String.valueOf(casted.complete("hacked"));
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }
}
