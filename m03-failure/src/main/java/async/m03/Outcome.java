package async.m03;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * future 의 최종 결과를 한 줄 문자열로 요약한다. 예측을 문자열로 적을 수 있게 하는 도구다.
 *
 * <ul>
 *   <li>성공: {@code "ok:<값>"}</li>
 *   <li>실패: {@code "failed:<근본 원인의 단순 클래스 이름>"}. CompletionException/ExecutionException 은 모두 벗긴다.
 *       예) {@code "failed:ProductNotFoundException"}</li>
 * </ul>
 */
public final class Outcome {

    private Outcome() {
    }

    /** 최대 2초 기다린 뒤 결과를 요약한다. */
    public static String of(CompletableFuture<?> future) {
        try {
            return "ok:" + future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("2초 안에 완료되지 않았다 (시나리오 버그)", e);
        } catch (ExecutionException | RuntimeException e) {
            return "failed:" + root(e).getClass().getSimpleName();
        }
    }

    static Throwable root(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
