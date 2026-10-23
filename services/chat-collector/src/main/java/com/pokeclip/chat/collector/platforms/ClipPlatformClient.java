package com.pokeclip.chat.collector.platforms;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.link.LinkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * clip의 {@code PUT /internal/broadcasts/{streamId}/platforms/{platform}}에 「켜져 있다」를 알린다(POK-397). clip은 그것을 방송 목록의
 * {@code platforms}로 보인다.
 *
 * <p><b>(방송, 플랫폼)마다 204를 받을 때까지만 보낸다.</b> 받은 뒤 주기 갱신은 없다: clip의 {@code last_seen_at}을 읽는 곳이 없고,
 * 꺼짐 판정은 수집기 관측 시각으로 한다(계획 검증 L3). 메모는 메모리라 재시작하면 한 번씩 다시 알리는데, 창구가 멱등이라 괜찮다.
 *
 * <p><b>실패는 다음 관측에 다시 보낸다.</b> 404는 시작 편지보다 알림이 먼저 왔거나 옛 clip이라 경로가 없는 경우다. 경고는 (방송, 플랫폼)마다
 * 첫 실패만 WARN이고 뒤는 DEBUG다: 옛 clip과 섞인 배포 중에는 방송 수 × 매분 실패해 로그가 넘친다(L4).
 *
 * <p>던지지 않는다. 시한은 짧다(접속 500ms · 읽기 1초, 중계와 같은 값): 부르는 쪽은 방송 정보 틱 한 스레드이고 clip이 매달리면
 * 뒤 방송이 밀린다. 쌍둥이는 {@code relay/ClipRelayClient}(같은 clip·같은 토큰 헤더·「주입받은 빌더를 쓴다」)다.
 */
public class ClipPlatformClient implements PlatformNotifier {

    private static final Logger log = LoggerFactory.getLogger(ClipPlatformClient.class);

    static final String PATH = "/internal/broadcasts/{streamId}/platforms/{platform}";
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    /** clip의 {@code InternalTokenFilter}가 보는 이름. 빠지면 401이다. */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private record Key(String streamId, Platform platform) {
    }

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;
    /** clip이 받았다고 확인한 것. */
    private final Set<Key> delivered = ConcurrentHashMap.newKeySet();
    /** 실패를 WARN으로 이미 알린 것. 받으면 지운다(다음 실패를 다시 WARN으로 보이게). */
    private final Set<Key> warned = ConcurrentHashMap.newKeySet();

    /** {@code RestClient.create()}를 쓰지 않는다. 시한은 넘겨받은 빌더에 걸려 있다({@link #create}). */
    public ClipPlatformClient(RestClient.Builder builder, PlatformsProperties properties, LinkProperties link) {
        properties.validate();
        link.validate();
        this.restClient = builder.build();
        this.baseUrl = properties.clipBaseUrl();
        this.internalToken = link.internalToken();
    }

    /**
     * 운영 배선. 🔴 주입받은 팩토리 빌더를 쓴다: {@code ClientHttpRequestFactoryBuilder.detect()}를 새로 부르면 클래스패스의
     * httpclient5가 잡혀 스택이 조용히 바뀐다({@code RelayConfiguration}과 같은 이유). 시한 시험이 이것을 그대로 부른다.
     */
    public static ClipPlatformClient create(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                            PlatformsProperties properties, LinkProperties link) {
        return new ClipPlatformClient(builder.requestFactory(factoryBuilder.build(
                HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT))), properties, link);
    }

    @Override
    public void seen(String streamId, Platform platform, Instant seenAt) {
        Key key = new Key(streamId, platform);
        if (delivered.contains(key)) {
            return;
        }
        int status = -1;
        String causeType = "none";
        try {
            status = restClient.put()
                    .uri(baseUrl + PATH, streamId, platform.name())
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("seenAt", seenAt.toString()))
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RuntimeException e) {
            // 타입만. 메시지에 주소가 실린다.
            causeType = e.getClass().getSimpleName();
        }
        if (status >= 200 && status < 300) {
            delivered.add(key);
            warned.remove(key);
            return;
        }
        if (warned.add(key)) {
            log.warn("platforms.notify_failed stream={} platform={} status={} causeType={}", streamId, platform, status,
                    causeType);
        } else {
            log.debug("platforms.notify_failed stream={} platform={} status={} causeType={}", streamId, platform, status,
                    causeType);
        }
    }

    @Override
    public void retain(Platform platform, Set<String> streamIds) {
        delivered.removeIf(key -> key.platform() == platform && !streamIds.contains(key.streamId()));
        warned.removeIf(key -> key.platform() == platform && !streamIds.contains(key.streamId()));
    }
}
