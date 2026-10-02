package async.m11.p4;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * P4. 오픈소스 라이브러리 "geo-lookup" 의 공개 클라이언트. 요구사항은 {@code p4-library-api/PROBLEM.md},
 * 공개 계약(스레드·타임아웃·취소·종료)의 결정은 {@code DECISION.md} 에 먼저 쓴다.
 *
 * <p>TODO(P4): 이 클래스와 {@link #lookup(String)} 의 javadoc 에 공개 계약을 문서화하라
 * (콜백이 실행되는 스레드, 타임아웃 시 예외, 취소의 의미, close 후 동작). 사용자는 javadoc 만 읽고 쓴다.
 *
 * <p>공개 시그니처는 바꾸지 않는다. 필드·헬퍼는 자유롭게 추가한다.
 */
public final class GeoClient implements AutoCloseable {

    private final Transport transport;
    private final Executor userCallbackExecutor;   // 사용자가 지정한 executor (없으면 null)
    private final Duration requestTimeout;


    private GeoClient(Builder builder) {
        this.transport = builder.transport;
        this.userCallbackExecutor = builder.callbackExecutor;
        this.requestTimeout = builder.requestTimeout;
        // TODO(P4): 라이브러리가 소유할 자원을 만든다 (R2, R10, R11, 체크리스트 #19)
    }

    public static Builder builder(Transport transport) {
        return new Builder(transport);
    }

    /**
     * IP 의 위치를 조회한다.
     *
     * @param ip 조회할 IP (null·빈 문자열 불가)
     * @return 조회 결과 stage. (계약은 TODO(P4): 문서화)
     */
    public CompletionStage<GeoResult> lookup(String ip) {
        // TODO(P4): DECISION.md 를 먼저 채운 뒤 구현한다
        throw new UnsupportedOperationException("TODO");
    }


    /**
     * 클라이언트를 닫는다. (계약은 TODO(P4): 진행 중인 조회는? 이후 호출은? 라이브러리 스레드는?)
     */
    @Override
    public void close() {
        throw new UnsupportedOperationException("TODO");
    }

    /** 빌더. */
    public static final class Builder {

        private final Transport transport;
        private Executor callbackExecutor;
        private Duration requestTimeout = Duration.ofSeconds(1);

        private Builder(Transport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
        }

        /** 결과(성공·실패·타임아웃)를 전달할 executor. 지정하지 않으면 라이브러리가 소유한 작은 풀을 쓴다. */
        public Builder callbackExecutor(Executor executor) {
            this.callbackExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** 조회 하나의 최대 대기 시간. 기본 1초. */
        public Builder requestTimeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.requestTimeout = timeout;
            return this;
        }

        public GeoClient build() {
            return new GeoClient(this);
        }
    }
}
