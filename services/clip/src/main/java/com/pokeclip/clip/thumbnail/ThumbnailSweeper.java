package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.segment.TimelineOriginReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 1분마다 사진 주문을 낸다(POK-277). 세 갈래를 차례로 훑는다.
 *
 * <ul>
 *   <li><b>라이브</b>: 방송 중인 방송마다 가장 최근에 올라간 조각의 가운데 장면. 지난번 사진과 같은 조각이면 안 낸다.</li>
 *   <li><b>카드</b>: 사진이 없는 카드의 시점 장면. 조각이 올라온 뒤에야 대상이 된다.</li>
 *   <li><b>완성 영상</b>: 영상 구간 안 최고 점수 카드 시점, 없으면 영상 가운데. 완성 영상 파일에서 뽑는다(보관함이 세로 영상이라
 *       원본 조각이 아니라 실제 결과물의 장면이어야 한다).</li>
 * </ul>
 *
 * <p>주문은 멱등이다: 같은 대상은 늘 같은 키에 올라가고 보고도 같은 값을 적는다. 그래서 주문이 두 번 나가도(순회가 겹치거나 clip이 두
 * 대여도) 사진이 둘이 되지 않는다. 대신 card·clip은 {@link #MAX_ATTEMPTS}번까지만 낸다: 조각이 지워졌으면 영원히 실패한다.
 * 횟수는 <b>줄에 실은 뒤에</b> 적는다. 앞에 적으면 줄이 15분 넘게 안 될 때 일꾼이 한 번도 못 받은 대상이 세 번을 다 써 영영 안 찍힌다
 * (로컬 리뷰 1라운드). 실은 뒤 적기가 실패하면 다음 순회가 한 번 더 내는데, 같은 키라 해가 없다.
 *
 * <p>주문을 못 실어도(줄 오류) 다음 순회가 다시 찾는다. 따로 outbox가 없는 이유다: 사진은 놓쳐도 다음 분에 다시 찍으면 된다.
 */
@Component
@ConditionalOnProperty(prefix = "pokeclip.thumbnail", name = "enabled", havingValue = "true")
public class ThumbnailSweeper {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailSweeper.class);

    /** 방송 중인 방송을 한 번에 이만큼까지(방송 목록 창구의 상한과 같다). */
    static final int LIVE_LIMIT = 500;

    /** 카드·영상은 한 순회에 이만큼까지. 밀린 것은 다음 순회가 잇는다(처음 켤 때 옛 카드가 한꺼번에 몰리지 않게). */
    static final int BATCH_LIMIT = 50;

    static final int MAX_ATTEMPTS = 3;

    /** 주문을 내고 이만큼 지나도 사진이 안 왔으면 다시 낸다. 일꾼 줄의 숨김 시간(2분)보다 넉넉하게. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    private final ThumbnailTargets targets;
    private final ThumbnailRepository thumbnails;
    private final ThumbnailQueueClient queue;
    private final BroadcastRepository broadcasts;
    private final TimelineOriginReader origins;
    private final ObjectMapper mapper;
    private final String segmentBucket;
    private final String outputBucket;
    private final Clock clock;

    @Autowired
    ThumbnailSweeper(ThumbnailTargets targets, ThumbnailRepository thumbnails, ThumbnailQueueClient queue,
                     BroadcastRepository broadcasts, TimelineOriginReader origins, ObjectMapper mapper,
                     @Value("${pokeclip.render.segment-bucket}") String segmentBucket,
                     @Value("${pokeclip.render.output-bucket}") String outputBucket) {
        this(targets, thumbnails, queue, broadcasts, origins, mapper, segmentBucket, outputBucket, Clock.systemUTC());
    }

    ThumbnailSweeper(ThumbnailTargets targets, ThumbnailRepository thumbnails, ThumbnailQueueClient queue,
                     BroadcastRepository broadcasts, TimelineOriginReader origins, ObjectMapper mapper,
                     String segmentBucket, String outputBucket, Clock clock) {
        this.targets = targets;
        this.thumbnails = thumbnails;
        this.queue = queue;
        this.broadcasts = broadcasts;
        this.origins = origins;
        this.mapper = mapper;
        this.segmentBucket = segmentBucket;
        this.outputBucket = outputBucket;
        this.clock = clock;
    }

    /**
     * 🔴 갈래마다 {@code Throwable}을 잡는다. 하나라도 밖으로 새면 스프링이 이 일정을 영영 멈춘다({@code StaleBroadcastReaper}와
     * 같은 이유). 한 갈래가 죽어도 나머지 둘은 돈다.
     */
    @Scheduled(fixedDelayString = "${pokeclip.thumbnail.interval}", initialDelayString = "${pokeclip.thumbnail.interval}")
    public void tick() {
        guarded("live", this::sweepLive);
        guarded("card", this::sweepCards);
        guarded("clip", this::sweepClips);
    }

    private static void guarded(String kind, Runnable sweep) {
        try {
            sweep.run();
        } catch (Throwable e) {
            log.warn("thumbnail.sweep_failed kind={} reason={}", kind, e.getClass().getSimpleName());
        }
    }

    void sweepLive() {
        List<ThumbnailTargets.Live> rows = targets.live(LIVE_LIMIT);
        Map<String, Instant> last = thumbnails.liveCapturedAt(rows.stream().map(ThumbnailTargets.Live::streamId).toList());
        int sent = 0;
        for (ThumbnailTargets.Live row : rows) {
            long offsetMs = row.durationMs() / 2;
            Instant shotAt = row.shotAt().plusMillis(offsetMs);
            Instant previous = last.get(row.streamId());
            if (previous != null && !shotAt.isAfter(previous)) {
                continue; // 새 조각이 없다(송출이 멈췄다). 같은 장면을 다시 찍지 않는다
            }
            send(ThumbnailKind.LIVE, row.streamId(), segmentBucket, row.s3Key(), offsetMs, shotAt);
            sent++;
        }
        log.debug("thumbnail.sweep kind=live candidates={} sent={}", rows.size(), sent);
    }

    void sweepCards() {
        Instant now = clock.instant();
        List<ThumbnailTargets.Card> rows = targets.cards(MAX_ATTEMPTS, now.minus(RETRY_AFTER), BATCH_LIMIT);
        for (ThumbnailTargets.Card row : rows) {
            String target = String.valueOf(row.id());
            send(ThumbnailKind.CARD, target, segmentBucket, row.s3Key(),
                    row.streamTimestampMs() - row.segmentStartPtsMs(), now);
            thumbnails.markRequested(ThumbnailKind.CARD, target, now);
        }
        if (!rows.isEmpty()) {
            log.info("thumbnail.sweep kind=card sent={}", rows.size());
        }
    }

    void sweepClips() {
        Instant now = clock.instant();
        List<ThumbnailTargets.ClipRow> rows = targets.clips(MAX_ATTEMPTS, now.minus(RETRY_AFTER), BATCH_LIMIT);
        for (ThumbnailTargets.ClipRow row : rows) {
            String video = firstVideoKey(row.outputsJson());
            String target = String.valueOf(row.id());
            if (video == null) {
                // 영상 산출물이 없는 완성 영상(자막만)은 찍을 것이 없다. 다시 안 보게 횟수를 다 쓴 것으로 적는다
                thumbnails.markGivenUp(ThumbnailKind.CLIP, target, MAX_ATTEMPTS, now);
                continue;
            }
            send(ThumbnailKind.CLIP, target, outputBucket, video, clipOffsetMs(row), now);
            thumbnails.markRequested(ThumbnailKind.CLIP, target, now);
        }
        if (!rows.isEmpty()) {
            log.info("thumbnail.sweep kind=clip sent={}", rows.size());
        }
    }

    /**
     * 완성 영상 안에서 찍을 자리. 구간 안 최고 점수 카드가 있으면 그 시점, 없으면 가운데. 카드 시각은 카드 축이라 시각 기준점으로
     * 재생 축(구간)과 맞춘다. 기준점을 못 재면 가운데로 간다.
     */
    long clipOffsetMs(ThumbnailTargets.ClipRow row) {
        if (row.inAtMs() == null || row.outAtMs() == null) {
            return 0;
        }
        long length = row.outAtMs() - row.inAtMs();
        long middle = length / 2;
        Broadcast broadcast = broadcasts.findByStreamId(row.streamId()).orElse(null);
        if (broadcast == null) {
            return middle;
        }
        Instant origin = origins.originsOf(List.of(broadcast)).get(row.streamId());
        if (origin == null) {
            return middle;
        }
        long originMs = origin.toEpochMilli();
        OptionalLong card = targets.topCardIn(row.streamId(), row.inAtMs() - originMs, row.outAtMs() - originMs);
        if (card.isEmpty()) {
            return middle;
        }
        long offset = originMs + card.getAsLong() - row.inAtMs();
        return Math.max(0, Math.min(offset, length - 1));
    }

    private String firstVideoKey(String outputsJson) {
        if (outputsJson == null) {
            return null;
        }
        for (JsonNode output : mapper.readTree(outputsJson)) {
            if ("video".equals(output.path("kind").asString())) {
                return output.path("s3Key").asString();
            }
        }
        return null;
    }

    /**
     * 주문서. 일꾼은 {@code source}를 받아 {@code offsetMs} 자리의 한 장면을 jpg로 만들어 {@code output}에 올리고
     * {@code POST /internal/thumbnails}로 {@code kind}·{@code targetId}·{@code capturedAt}을 돌려준다.
     */
    private void send(ThumbnailKind kind, String targetId, String bucket, String key, long offsetMs, Instant capturedAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("kind", kind.value());
        root.put("targetId", targetId);
        root.put("capturedAt", capturedAt.toString());
        ObjectNode source = root.putObject("source");
        source.put("bucket", bucket);
        source.put("s3Key", key);
        source.put("offsetMs", Math.max(0, offsetMs));
        ObjectNode output = root.putObject("output");
        output.put("bucket", outputBucket);
        output.put("s3Key", kind.keyOf(targetId));
        queue.send(mapper.writeValueAsString(root));
    }
}
