package async.m10.netty;

import java.util.concurrent.Executor;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

import async.harness.Gate;
import async.harness.ThreadRecorder;

/**
 * 3장 Netty 프렐류드 예측 시나리오. <b>이 파일은 수정하지 않는다.</b> 읽기 가이드: {@code guides/03-netty-prelude.md}
 *
 * <p>{@code loop} 는 테스트가 만든 {@code MultiThreadIoEventLoopGroup(1, NioIoHandler)} 의 이벤트 루프다.
 * 스레드 이름은 {@code multiThreadIoEventLoopGroup-<그룹번호>-1} 이다.
 * {@code completer} 는 이름이 {@code completer-1} 인 평범한 스레드 풀이다.
 */
public final class NettyScenarios {

    private NettyScenarios() {
    }

    /**
     * N1. 이벤트 루프의 Promise 에 caller 가 리스너를 붙이고(미완료), <b>completer 스레드</b>가 setSuccess 한다.
     * 리스너("listener")는 어느 스레드에서 실행되는가? (같은 모양의 CF 라면 R1 에 따라 completer 다)
     */
    public static Future<String> n1(ThreadRecorder rec, EventLoop loop, Executor completer) {
        Promise<String> promise = loop.newPromise();
        promise.addListener(f -> rec.mark("listener"));
        completer.execute(() -> promise.setSuccess("pong"));
        return promise;
    }

    /**
     * N2. 이미 완료된 Promise 에 <b>caller 가</b> 리스너를 붙인다.
     * 리스너("listener")는 어느 스레드에서 실행되는가? (같은 모양의 CF 라면 R1 에 따라 caller 다)
     */
    public static Future<String> n2(ThreadRecorder rec, EventLoop loop) {
        Promise<String> promise = loop.newPromise();
        promise.setSuccess("pong");
        promise.addListener(f -> rec.mark("listener"));
        return promise;
    }

    /**
     * N3. {@code ImmediateEventExecutor} 의 Promise. caller 가 리스너를 붙이고(미완료), completer 가 setSuccess 한다.
     * 리스너("listener")는 어느 스레드에서 실행되는가?
     */
    public static Future<String> n3(ThreadRecorder rec, Executor completer) {
        Promise<String> promise = ImmediateEventExecutor.INSTANCE.newPromise();
        promise.addListener(f -> rec.mark("listener"));
        completer.execute(() -> promise.setSuccess("pong"));
        return promise;
    }

    /**
     * N4. 이벤트 루프 안에서 같은 루프의 미완료 Promise 를 {@code sync()} 한다(채널 핸들러 안에서 응답을 기다리는 실수).
     * 결과는? ("completed" 또는 던져진 예외의 단순 클래스 이름)
     */
    public static String n4(EventLoop loop) {
        Promise<String> response = loop.newPromise();
        Gate done = new Gate();
        String[] outcome = new String[1];
        loop.execute(() -> {
            try {
                response.sync();
                outcome[0] = "completed";
            } catch (Throwable t) {
                outcome[0] = t.getClass().getSimpleName();
            } finally {
                done.open();
            }
        });
        done.await();
        return outcome[0];
    }
}
