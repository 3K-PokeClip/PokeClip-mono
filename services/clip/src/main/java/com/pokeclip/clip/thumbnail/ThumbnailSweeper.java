package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.CardPositionReader.Scene;
import com.pokeclip.clip.jumpcard.JumpCardCreated;
import com.pokeclip.clip.segment.OffsetOrigin;
import com.pokeclip.clip.segment.TimelineOriginReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1분마다 사진 주문을 낸다(POK-277). 세 갈래를 차례로 훑는다.
 *
 * <ul>
 *   <li><b>라이브</b>: 방송 중인 방송마다 회차에서 가장 최근에 올라간 조각의 가운데 장면. 지난번 사진과 같은 조각이면 안
 *       낸다.</li>
 *   <li><b>카드</b>: 사진이 없는 카드의 장면(카드 장면 함수 — 틈 안이면 뒤 조각 첫 화면 · POK-196). 장면 조각이 올라온 뒤에야
 *       주문이 나간다.</li>
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
 *
 * <p><b>새 카드는 따로 빨리 본다</b>(POK-475). 1분 순회만 있으면 카드가 생긴 뒤 사진이 붙기까지 1분 넘게 걸렸다(실측 76초: 조각 올림
 * 33초 + 순회 대기 44초). 카드가 생기면(커밋 뒤) {@link #newCards} 명단에 오르고 {@link #NEW_CARD_POLL_MS}마다 명단의 카드만 본다.
 * 조각이 아직 안 올라왔으면 명단에 남아 다음에 다시 본다. 표 전체를 그 간격으로 훑지 않는 이유: 카드 질의는 사진 없는 카드를 찾느라
 * 표를 끝까지 훑을 수 있다. 명단은 이 순회기 안에만 있어 clip을 다시 띄우면 비지만, 그 카드는 1분 순회가 찍는다.
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

    /** 새 카드 명단을 보는 간격(ms). 조각이 이미 있으면 사진이 이만큼 + 일꾼 시간(약 4초) 뒤에 붙는다 */
    static final long NEW_CARD_POLL_MS = 2_000;

    /** 이만큼 지나도 조각이 안 오면 명단에서 뺀다: 그 뒤는 1분 순회가 맡는다(2초마다 묻기를 끝없이 하지 않게) */
    static final Duration NEW_CARD_WINDOW = Duration.ofMinutes(10);

    /** 새 카드 명단: 카드 번호 → 알림을 받은 시각. 알림은 요청 스레드(커밋 뒤)가, 순회는 일정 스레드가 만진다 */
    private final Map<Long, Instant> newCards = new ConcurrentHashMap<>();

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

    /** 새 카드 명단을 본다(POK-475). 명단이 비면 DB를 안 묻는다. */
    @Scheduled(fixedDelay = NEW_CARD_POLL_MS, initialDelay = NEW_CARD_POLL_MS)
    public void tickNewCards() {
        guarded("new_card", this::sweepNewCards);
    }

    /**
     * 카드가 새로 생겼다. <b>커밋 뒤에만</b> 받는다: 되감긴 카드는 명단에 안 오른다. 여기서는 명단에 적기만 한다: 줄에 싣는 일을
     * 요청 스레드(마킹 응답)에 붙이지 않는다.
     */
    @TransactionalEventListener
    void cardCreated(JumpCardCreated event) {
        newCards.put(event.cardId(), clock.instant());
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
            send(ThumbnailKind.LIVE, row.streamId(), segmentBucket, row.s3Key(), offsetMs, row.offsetOrigin(), shotAt);
            sent++;
        }
        log.debug("thumbnail.sweep kind=live candidates={} sent={}", rows.size(), sent);
    }

    void sweepCards() {
        Instant now = clock.instant();
        ThumbnailTargets.CardBatch batch = targets.cards(MAX_ATTEMPTS, now.minus(RETRY_AFTER), BATCH_LIMIT);
        for (ThumbnailTargets.Card row : batch.orders()) {
            orderCard(row, now);
        }
        batch.givenUp().forEach(id -> giveUpCard(id, now));
        if (!batch.orders().isEmpty() || !batch.givenUp().isEmpty()) {
            log.info("thumbnail.sweep kind=card sent={} givenUp={}", batch.orders().size(), batch.givenUp().size());
        }
    }

    /**
     * 명단의 카드 중 조각이 올라온 것을 주문하고 명단에서 뺀다. 주문 조건(사진 없음 · 재시도 간격 · 횟수)은 1분 순회와 같아서 둘이
     * 같은 카드를 겹쳐 내지 않는다(같은 일정 스레드라 동시에 돌지도 않는다). 줄에 못 실으면 명단에 남는다. 내려놓은 카드도 빠진다.
     */
    void sweepNewCards() {
        if (newCards.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Instant stale = now.minus(NEW_CARD_WINDOW);
        newCards.values().removeIf(at -> at.isBefore(stale));
        if (newCards.isEmpty()) {
            return;
        }
        ThumbnailTargets.CardBatch batch = targets.cardsIn(List.copyOf(newCards.keySet()), MAX_ATTEMPTS,
                now.minus(RETRY_AFTER), BATCH_LIMIT);
        for (ThumbnailTargets.Card row : batch.orders()) {
            orderCard(row, now);
            newCards.remove(row.id());
        }
        for (long id : batch.givenUp()) {
            giveUpCard(id, now);
            newCards.remove(id);
        }
        if (!batch.orders().isEmpty() || !batch.givenUp().isEmpty()) {
            log.info("thumbnail.sweep kind=new_card sent={} givenUp={} waiting={}", batch.orders().size(),
                    batch.givenUp().size(), newCards.size());
        }
    }

    private void orderCard(ThumbnailTargets.Card row, Instant now) {
        String target = String.valueOf(row.id());
        send(ThumbnailKind.CARD, target, segmentBucket, row.s3Key(), row.offsetMs(), row.offsetOrigin(), now);
        thumbnails.markRequested(ThumbnailKind.CARD, target, now);
    }

    /**
     * 장면이 영구히 안 나오는 카드(끝난 방송 · 완결 회차에서 장면 없음 — {@code ThumbnailTargets})를
     * 내려놓는다. 영상 산출물이 없는 완성 영상과 같은 길이다: 횟수를 다 쓴 것으로 적어 다시 후보에 안 든다(POK-196 · kty
     * 2026-11-12 (가)).
     */
    private void giveUpCard(long id, Instant now) {
        thumbnails.markGivenUp(ThumbnailKind.CARD, String.valueOf(id), MAX_ATTEMPTS, now);
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
            long offsetMs;
            try {
                offsetMs = clipOffsetMs(row);
            } catch (RuntimeException e) {
                // 이 영상만 건너뛴다 — 횟수를 올려 같은 결함이 다시 와도 세 번 뒤 후보에서 빠진다. 던지면 그 뒤 영상들까지 이번
                // 순회를 잃고, 이 줄은 횟수가 안 올라 매 순회 같은 자리에서 다시 멈춘다(커밋 리뷰 r1). 줄에 싣기 실패는 여기 안
                // 든다(아래 send — 줄 장애는 횟수를 안 쓴다).
                log.warn("clip.thumbnail.clip_photo clipId={} source=failed reason={}", row.id(),
                        e.getClass().getSimpleName());
                thumbnails.markRequested(ThumbnailKind.CLIP, target, now);
                continue;
            }
            send(ThumbnailKind.CLIP, target, outputBucket, video, offsetMs, null, now);
            thumbnails.markRequested(ThumbnailKind.CLIP, target, now);
        }
        if (!rows.isEmpty()) {
            log.info("thumbnail.sweep kind=clip sent={}", rows.size());
        }
    }

    /**
     * 완성 영상 안에서 찍을 자리(결과 영상 ms). 구간 안 최고 점수 카드가 있으면 그 장면, 없으면 가운데.
     *
     * <p><b>배치표가 있는 영상</b>(새 꼴 주문 · POK-196 S-c5 · 설계 r7 8.3): 계획 in · out 구간 안 최고 카드의 장면(카드 장면 함수)을
     * 렌더 배치표로 결과에 놓는다({@link ResultPlacements#resultMsOf} — 원점은 장면 행의 판). 카드가 없으면 결과 가운데다. 배치표가
     * 깨졌으면(모양 · 범위) 옛 식으로 떨어지고 WARN 을 남긴다. 장면을 읽거나 자리를 계산하다 실패하면 결과 가운데로 찍고 WARN 을
     * 남긴다. <b>배치표가 없는 영상</b>(옛 꼴)은 옛 식({@link #legacyOffsetMs}). 그 밖에서 던지면(옛 식의 질의 등) {@link #sweepClips}
     * 가 그 영상만 횟수를 올리고 넘어간다 — 한 영상 때문에 순회 묶음 전체가 멈추지 않는다.
     *
     * <p>신호 {@code clip.thumbnail.clip_photo}(DEBUG — 깨짐 · 실패만 WARN): {@code source} = {@code placements} · {@code legacy} ·
     * {@code invalid} · {@code failed}(순회가 건너뜀), {@code card} = {@code scene}(카드 장면) · {@code none}(가운데) ·
     * {@code failed}(장면 읽기 · 자리 계산 실패 → 가운데).
     */
    long clipOffsetMs(ThumbnailTargets.ClipRow row) {
        if (row.placementsJson() == null) {
            log.debug("clip.thumbnail.clip_photo clipId={} source=legacy", row.id());
            return legacyOffsetMs(row);
        }
        ResultPlacements layout;
        try {
            layout = ResultPlacements.parse(mapper.readTree(row.placementsJson()));
        } catch (RuntimeException e) {
            log.warn("clip.thumbnail.clip_photo clipId={} source=invalid reason={}", row.id(), e.getClass().getSimpleName());
            return legacyOffsetMs(row);
        }
        try {
            return placedOffsetMs(row, layout);
        } catch (RuntimeException e) {
            log.warn("clip.thumbnail.clip_photo clipId={} source=placements card=failed reason={}", row.id(),
                    e.getClass().getSimpleName());
            return layout.middleMs();
        }
    }

    /** 배치표가 있는 영상의 자리 — 장면 읽기 · 자리 계산이 던질 수 있다(부르는 쪽이 결과 가운데로 떨어뜨린다). */
    private long placedOffsetMs(ThumbnailTargets.ClipRow row, ResultPlacements layout) {
        Optional<Scene> scene = planCutOf(row).flatMap(cut -> targets.topCardScene(row.streamId(), cut));
        if (scene.isEmpty()) {
            log.debug("clip.thumbnail.clip_photo clipId={} source=placements card=none", row.id());
            return layout.middleMs();
        }
        OffsetOrigin origin = OffsetOrigin.ofTimingVersion(scene.get().row().timingVersion());
        long offsetMs = layout.resultMsOf(scene.get().seq(), scene.get().offsetMs(), origin);
        log.debug("clip.thumbnail.clip_photo clipId={} source=placements card=scene", row.id());
        return offsetMs;
    }

    /** 주문서 계획의 in · out. 옛 꼴 주문이거나 칸이 틀리면 비어 있다(카드 없음 — 결과 가운데). */
    private Optional<ThumbnailTargets.PlanCut> planCutOf(ThumbnailTargets.ClipRow row) {
        if (row.planInJson() == null || row.planOutJson() == null) {
            return Optional.empty();
        }
        JsonNode in = mapper.readTree(row.planInJson());
        JsonNode out = mapper.readTree(row.planOutJson());
        if (!integral(in, "seq") || !integral(in, "offsetUs") || !integral(out, "seq")) {
            return Optional.empty();
        }
        boolean toSegmentEnd = out.path("toSegmentEnd").asBoolean(false);
        if (!toSegmentEnd && !integral(out, "offsetUs")) {
            return Optional.empty();
        }
        return Optional.of(new ThumbnailTargets.PlanCut(in.get("seq").asLong(), in.get("offsetUs").asLong(),
                out.get("seq").asLong(), toSegmentEnd ? null : out.get("offsetUs").asLong()));
    }

    private static boolean integral(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong();
    }

    /**
     * 옛 식(배치표가 없는 완성 영상). 구간 안 최고 점수 카드가 있으면 그 시점, 없으면 가운데. 카드 시각은 카드 축이라 시각 기준점으로
     * 재생 축(구간)과 맞춘다. 기준점을 못 재면 가운데로 간다.
     */
    long legacyOffsetMs(ThumbnailTargets.ClipRow row) {
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
     *
     * @param origin 조각 안 {@code offsetMs}를 어디서부터 재나(카드 · 라이브 — 고른 행의 판 · POK-196). 완성 영상은 결과 파일 안
     *               자리라 칸을 싣지 않는다(null)
     */
    private void send(ThumbnailKind kind, String targetId, String bucket, String key, long offsetMs,
                      OffsetOrigin origin, Instant capturedAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("kind", kind.value());
        root.put("targetId", targetId);
        root.put("capturedAt", capturedAt.toString());
        ObjectNode source = root.putObject("source");
        source.put("bucket", bucket);
        source.put("s3Key", key);
        source.put("offsetMs", Math.max(0, offsetMs));
        if (origin != null) {
            source.put("offsetOrigin", origin.name());
        }
        ObjectNode output = root.putObject("output");
        output.put("bucket", outputBucket);
        output.put("s3Key", kind.keyOf(targetId));
        queue.send(mapper.writeValueAsString(root));
    }
}
