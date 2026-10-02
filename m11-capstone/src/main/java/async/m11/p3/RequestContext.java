package async.m11.p3;

/** 요청 채널(APP/WEB) 컨텍스트. 웹 필터가 요청 스레드의 ThreadLocal 에 넣는다. (주어진 타입) */
public final class RequestContext {

    private static final ThreadLocal<String> CHANNEL = new ThreadLocal<>();

    private RequestContext() {
    }

    public static String channel() {
        return CHANNEL.get();
    }

    public static void setChannel(String channel) {
        if (channel == null) {
            CHANNEL.remove();
        } else {
            CHANNEL.set(channel);
        }
    }

    public static void clear() {
        CHANNEL.remove();
    }
}
