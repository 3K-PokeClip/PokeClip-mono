package com.pokeclip.chat.collector.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 방송 한 구간의 채팅량 차트 창구(POK-234).
 *
 * <p>{@code GET /internal/streams/{streamId}/chat-chart?from&to&bucket&channelId}
 *
 * <p>문·오류 본문·시각 축은 {@link ChatWindowController}와 같다 — 두 창구가 다른 규칙을 쓰면
 * 프론트가 창구마다 다시 배운다. 400 사유는 {@link QueryErrors}가 낸다.
 */
@RestController
public class ChatChartController {

    /**
     * 받아 주는 구간 크기(초). <b>아무 값이나 받지 않는 이유</b>는 점 수 상한과 짝이기 때문이다 —
     * 1초를 받으면 1시간 창이 3,600점이라 상한에 걸려 400만 나가고, 3초 같은 값은 화면 눈금과
     * 안 맞는다. 넷은 5초(가장 촘촘) · 10초(기본) · 30초 · 60초다.
     */
    private static final Set<Integer> BUCKETS = Set.of(5, 10, 30, 60);

    private static final int DEFAULT_BUCKET = 10;

    private final ChatChartQuery query;
    private final QueryProperties properties;

    public ChatChartController(ChatChartQuery query, QueryProperties properties) {
        this.query = query;
        this.properties = properties;
    }

    @GetMapping("/internal/streams/{streamId}/chat-chart")
    public ChatChartPage get(@PathVariable String streamId,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false) String bucket,
                             @RequestParam(required = false) String channelId) {
        WindowRequest window = WindowRequest.parse(from, to, properties.windowMax());
        int bucketSeconds = parseBucket(bucket);
        requirePointCount(window, bucketSeconds);
        return query.count(streamId, channelId, window, bucketSeconds);
    }

    /** {@code String}으로 받는 이유는 {@link ChatWindowController#get}의 {@code limit}과 같다. */
    private static int parseBucket(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BUCKET;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidWindowException("bucket");
        }
        if (!BUCKETS.contains(seconds)) {
            throw new InvalidWindowException("bucket");
        }
        return seconds;
    }

    /**
     * 🔴 <b>세는 방법을 질의와 <u>공유한다</u></b>(봇 codex). 한때 여기서
     * {@code toSeconds() / bucketSeconds} 로 따로 셌는데, <b>둘 다 내림</b>이라
     * 질의가 실제로 만드는 격자보다 적게 나왔다 — {@code from} 에 마이크로초가 실리면
     * 상한 720 을 통과하고 721점이 나간다. 같은 답을 두 곳에서 따로 구하면
     * <b>한쪽만 고쳐져 낡는다.</b>
     *
     * <p><b>잘라 주지 않고 400이다.</b> 잘라 주면 프론트가 그린 그래프가 물어본 구간의 앞부분만
     * 담는데 화면에는 그렇게 안 보인다 — {@code limit} 상한과 방향이 반대인 이유는
     * 목록은 「이어서 더 받는 길(커서)」이 있고 차트는 없기 때문이다.
     */
    private void requirePointCount(WindowRequest window, int bucketSeconds) {
        if (ChatChartQuery.bucketCount(window.from(), window.to(), bucketSeconds)
                > properties.chartMaxBuckets()) {
            throw new InvalidWindowException("too_many_buckets");
        }
    }
}
