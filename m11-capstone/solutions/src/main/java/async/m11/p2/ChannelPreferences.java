package async.m11.p2;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import async.harness.FakeEventLoopClient;

/**
 * 사용자별 수신 동의 채널 저장소 (Redis, Lettuce 비동기 클라이언트). (주어진 타입: 수정하지 않는다)
 *
 * <p>{@link #channelsOf(String)} 는 즉시 반환하고, 반환된 future 는 Redis 클라이언트의 <b>이벤트 루프 스레드</b>가 완료시킨다.
 * 호출마다 새 future 를 돌려준다. 저장된 동의가 없는 사용자는 빈 집합이다.
 */
public final class ChannelPreferences {

    private final FakeEventLoopClient redis;
    private final Map<String, Set<Channel>> optIns = new ConcurrentHashMap<>();

    public ChannelPreferences(FakeEventLoopClient redis) {
        this.redis = redis;
    }

    /** 테스트·초기화용: 사용자의 수신 동의를 저장한다. */
    public ChannelPreferences optIn(String userId, Channel... channels) {
        optIns.put(userId, channels.length == 0 ? EnumSet.noneOf(Channel.class) : EnumSet.of(channels[0], channels));
        return this;
    }

    public CompletableFuture<Set<Channel>> channelsOf(String userId) {
        return redis.fetch("prefs/" + userId)
                .thenApply(raw -> Set.copyOf(optIns.getOrDefault(userId, EnumSet.noneOf(Channel.class))));
    }
}
