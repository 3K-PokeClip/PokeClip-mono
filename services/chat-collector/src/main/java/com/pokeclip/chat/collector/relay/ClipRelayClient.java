package com.pokeclip.chat.collector.relay;

import com.pokeclip.chat.collector.link.LinkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * clip의 {@code POST /internal/broadcasts/{streamId}/chat-events}에 채팅·후원 묶음을 민다.
 *
 * <p><b>쌍둥이는 {@code broadcast/reattach/LiveBroadcastClient}다</b> — 같은 clip·같은 토큰 헤더·
 * 「주입받은 빌더를 쓴다」가 같다. 다른 것은 <b>실패를 예외가 아니라 {@link Outcome}으로 돌려준다</b>는 것 —
 * 중계는 재시도하지 않는다. 표가 정본이고 놓친 것은 프론트가 범위 창구로 메운다(F3).
 *
 * <p><b>final이 아니다</b> — 검사가 {@link #send}를 덮어 <b>보내는 쪽의 스레드 이름</b>을 잰다(F2).
 * 가짜 clip의 핸들러에서 재면 가짜 서버의 스레드가 보인다.
 */
public class ClipRelayClient {

    private static final Logger log = LoggerFactory.getLogger(ClipRelayClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String PATH = "/internal/broadcasts/{streamId}/chat-events";

    /** clip의 {@code InternalTokenFilter}가 보는 이름. 빠지면 401이다. */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /**
     * 실패 경고를 이 간격에 한 줄로 모은다. clip이 죽으면 <b>묶음마다</b> 실패하고, 중계 스레드는 쌓인 만큼
     * 바로 다음 묶음을 보내므로 채팅이 초당 수십 건이면 경고도 초당 수십 줄이 된다.
     */
    static final Duration WARN_EVERY = Duration.ofSeconds(10);

    public enum Outcome {
        /** 2xx. clip이 받았다 — 연결에 뿌렸는지는 clip의 셈이다 */
        SENT,
        /** 404. clip 명부에 그 방송이 없다. 다시 보내도 안 바뀐다 */
        UNKNOWN_BROADCAST,
        /** 그 밖의 상태·못 닿음·시한 초과. 재시도하지 않는다 */
        FAILED
    }

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;

    /**
     * 직전 {@link #send}가 2xx였는데 clip이 <b>일부를 모르는 종류로 건너뛰었다</b>고 답한 수(응답 {@code dropped}).
     * 수집기를 clip보다 먼저 배포하는 날 새 종류가 여기로 온다 — 안 세면 전부 {@code relayed}로 들어가 유실이
     * 어디에도 안 남는다(PR #181 codex). 중계 스레드 하나만 부르므로 {@link #takePartialDropped}로 곧바로 거둔다.
     */
    private final AtomicLong partialDropped = new AtomicLong();

    private final AtomicLong nextWarnNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressedWarns = new AtomicLong();

    /**
     * <b>{@code RestClient.create()}를 쓰지 않는다</b> — 자동 설정을 우회해 시한이 어디에도 안 걸린다.
     * 시한은 넘겨받은 빌더에 이미 걸려 있다({@link RelayConfiguration.Enabled#clipRelayClient}).
     */
    public ClipRelayClient(RestClient.Builder builder, RelayProperties relay, LinkProperties link) {
        relay.validate();
        link.validate();
        this.restClient = builder.build();
        this.baseUrl = relay.clipBaseUrl();
        this.internalToken = link.internalToken();
    }

    /** 던지지 않는다 — 부르는 쪽은 중계 스레드이고 거기서 예외가 새면 스레드가 죽는다. */
    public Outcome send(String streamId, List<RelayEvent> events) {
        int status = -1;
        String causeType = "none";
        Outcome outcome;
        try {
            byte[] body = MAPPER.writeValueAsBytes(Map.of("events", toJson(events)));
            status = restClient.post()
                    .uri(baseUrl + PATH, streamId)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        int code = response.getStatusCode().value();
                        if (code >= 200 && code < 300) {
                            partialDropped.set(readDropped(response.getBody()));
                        }
                        return code;
                    });
            outcome = status >= 200 && status < 300 ? Outcome.SENT
                    : status == 404 ? Outcome.UNKNOWN_BROADCAST
                    : Outcome.FAILED;
        } catch (RuntimeException e) {
            // 타입만 — 메시지에 주소가 실린다. 본문은 요청에만 있고 예외 메시지에는 안 들어가지만
            // 넣지 않는 쪽이 검사로 지키기 쉽다.
            causeType = e.getClass().getSimpleName();
            outcome = Outcome.FAILED;
        }
        if (outcome != Outcome.SENT) {
            warnThrottled(streamId, outcome, status, causeType, events.size());
        }
        return outcome;
    }

    /** 직전 성공 응답이 건너뛰었다고 한 수를 거두고 0으로 되돌린다. 대역 클라이언트는 늘 0이다. */
    public long takePartialDropped() {
        return partialDropped.getAndSet(0);
    }

    /** 본문이 없거나 모양이 달라도 0 — 옛 clip·202 빈 본문. 셈을 위해 요청을 실패로 돌리지 않는다. */
    private static long readDropped(java.io.InputStream body) {
        try {
            return Math.max(0, MAPPER.readTree(body).path("dropped").asLong(0));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void warnThrottled(String streamId, Outcome outcome, int status, String causeType, int events) {
        long now = System.nanoTime();
        long next = nextWarnNanos.get();
        if (next != Long.MIN_VALUE && now < next) {
            suppressedWarns.incrementAndGet();
            return;
        }
        if (!nextWarnNanos.compareAndSet(next, now + WARN_EVERY.toNanos())) {
            suppressedWarns.incrementAndGet();
            return;
        }
        log.warn("chat.relay.send_failed stream={} outcome={} status={} causeType={} events={} suppressed={}",
                streamId, outcome, status, causeType, events, suppressedWarns.getAndSet(0));
    }

    /**
     * 창구({@code ChatWindowItem})의 칸 아홉(id 뺌) + {@code seq}·{@code seqEpoch}를 <b>늘 싣는다</b>(없는 칸은 null) —
     * 창구가 그렇게 준다.
     * 🔴 {@code id} 칸은 없다: 창구의 id는 표 PK라 같은 이름에 다른 값을 실으면 프론트가 짝짓는다.
     * 시각은 {@code Instant} 그대로 넘겨 창구와 같은 ISO-8601 문자열이 된다.
     */
    private static List<Map<String, Object>> toJson(List<RelayEvent> events) {
        List<Map<String, Object>> out = new ArrayList<>(events.size());
        for (RelayEvent event : events) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", event.seq());
            item.put("seqEpoch", event.seqEpoch());
            item.put("kind", event.payload().kind());
            item.put("time", event.payload().time());
            item.put("timeBasis", event.payload().timeBasis());
            switch (event.payload()) {
                case RelayPayload.Chat chat -> {
                    item.put("nickname", chat.nickname());
                    item.put("senderChannelId", chat.senderChannelId());
                    item.put("role", chat.role());
                    item.put("text", chat.text());
                    item.put("amount", null);
                    item.put("donationType", null);
                }
                case RelayPayload.Donation donation -> {
                    item.put("nickname", donation.nickname());
                    item.put("senderChannelId", donation.senderChannelId());
                    item.put("role", null);
                    item.put("text", donation.text());
                    item.put("amount", donation.amount());
                    item.put("donationType", donation.donationType());
                }
            }
            out.add(item);
        }
        return out;
    }
}
