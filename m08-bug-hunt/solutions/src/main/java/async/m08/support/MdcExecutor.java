package async.m08.support;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * MDC 를 작업 스레드로 복사해 주는 executor 데코레이터. Spring 의 {@code TaskDecorator} 와 같은 역할이다.
 *
 * <p>이 구현은 <b>올바르다</b>: 제출 시점의 MDC 를 캡처하고, 작업이 끝나면 작업 스레드의 원래 MDC 로 복원한다(R12).
 * 단, 이 executor 가 실행하는 작업에만 전파된다. 이벤트 루프나 타임아웃 스케줄러가 실행하는 non-async 콜백에는 전파되지 않는다.
 */
public final class MdcExecutor {

    private MdcExecutor() {
    }

    public static Executor wrap(Executor delegate) {
        return task -> {
            Map<String, String> captured = Mdc.getCopyOfContextMap();
            delegate.execute(() -> {
                Map<String, String> previous = Mdc.getCopyOfContextMap();
                Mdc.setContextMap(captured);
                try {
                    task.run();
                } finally {
                    Mdc.setContextMap(previous);
                }
            });
        };
    }
}
