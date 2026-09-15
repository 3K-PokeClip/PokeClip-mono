package com.pokeclip.clip.render;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.recipe.Recipe;
import com.pokeclip.clip.recipe.RecipeDocument;
import com.pokeclip.clip.recipe.RecipeErrors.RecipeNotFoundException;
import com.pokeclip.clip.recipe.RecipeRepository;
import com.pokeclip.clip.render.RenderErrors.ClipNotFoundException;
import com.pokeclip.clip.render.RenderErrors.MessageTooLargeException;
import com.pokeclip.clip.render.RenderErrors.RecipeNotRenderableException;
import com.pokeclip.clip.render.RenderErrors.RenderUnavailableException;
import com.pokeclip.clip.render.RenderErrors.SourceNotReadyException;
import com.pokeclip.clip.segment.SegmentSource;
import com.pokeclip.clip.segment.SegmentWindow;
import com.pokeclip.clip.segment.SegmentWindowAssembler;
import com.pokeclip.clip.segment.StreamSegmentReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 「이 편집본으로 영상을 만들어 줘」를 받아 주문서를 쓰고 큐에 싣는다. 자격 판정이 여기 있다(컨트롤러가 아니라).
 *
 * <p><b>순서가 계약이다 — 자격 → 편집본 → 조각 준비 → 선점(표) → 발행(큐).</b> 자격이 맨 앞인 이유는 다른 문과 같다
 * (없는 방송과 자격 없음이 같은 404·같은 바닥). 표에 먼저 적고 큐에 나중에 싣는 것은 계약1 4절 「선기록·후발행」이다 —
 * 큐에 실렸는데 표에 없으면 일꾼의 보고가 404를 맞고, 표에는 있는데 큐에 못 실렸으면 {@link RenderPublisher}가 다시 보낸다.
 *
 * <p>트랜잭션은 표 쓰기에만 건다. auth 왕복(최대 7초)과 큐 왕복은 밖이다({@code JumpCardService}와 같은 이유).
 */
@Service
public class RenderRequestService {

    private static final Logger log = LoggerFactory.getLogger(RenderRequestService.class);

    /**
     * 이웃 조각 사이에 허용하는 재생 시각 구멍. 조립기는 <b>번호 연속</b>만 보는데, 송출이 끊겼다 이어지면 번호는 이어져도
     * 재생 시각이 건너뛴다 — 그대로 주문하면 일꾼이 가운데가 빈 영상을 조용히 만든다(PR #188 1판 codex P1). 정상 조각도
     * 시각이 딱 맞지는 않는다(실측 2026-09-13 방송: 4,000ms 조각의 시작 간격 4,110ms) — 그 흔들림은 통과시키고 1초 넘는 구멍만 거절한다.
     */
    static final long MAX_PLAYBACK_GAP_MS = 1_000;

    private final ObjectProvider<RenderQueueClient> queue;
    private final RenderProperties properties;
    private final BroadcastAccessGuard guard;
    private final BroadcastRepository broadcasts;
    private final RecipeRepository recipes;
    private final StreamSegmentReader segments;
    private final ClipInserter inserter;
    private final ClipRepository clips;
    private final RenderJobRepository jobs;
    private final RenderPublisher publisher;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    RenderRequestService(ObjectProvider<RenderQueueClient> queue, RenderProperties properties,
                         BroadcastAccessGuard guard, BroadcastRepository broadcasts, RecipeRepository recipes,
                         StreamSegmentReader segments, ClipInserter inserter, ClipRepository clips,
                         RenderJobRepository jobs, RenderPublisher publisher, TransactionTemplate transactions,
                         ObjectMapper mapper) {
        this.queue = queue;
        this.properties = properties;
        this.guard = guard;
        this.broadcasts = broadcasts;
        this.recipes = recipes;
        this.segments = segments;
        this.inserter = inserter;
        this.clips = clips;
        this.jobs = jobs;
        this.publisher = publisher;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    /** 주문 결과. {@code created}가 거짓이면 같은 편집본 같은 판이 이미 진행 중이라 그것을 돌려준 것이다(200 대 201). */
    public record Requested(boolean created, ClipSnapshot clip) {
    }

    /**
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException 방송이 없거나 볼 자격이 없다 (404)
     * @throws RecipeNotFoundException 그 방송에 그 편집본이 없다 (404)
     * @throws RenderUnavailableException 주문줄이 꺼져 있다 (503)
     * @throws RecipeNotRenderableException 구간이 없는 템플릿이다 (400 {@code cut})
     * @throws SourceNotReadyException 구간의 조각이 아직 다 안 올라왔다 (409)
     * @throws MessageTooLargeException 주문서가 상한을 넘는다 (422)
     */
    public Requested request(String requesterSubject, String streamId, long recipeId) {
        guard.requireViewable(requesterSubject, streamId);
        Recipe recipe = recipes.findByIdAndStreamId(recipeId, streamId)
                .orElseThrow(() -> new RecipeNotFoundException(recipeId));
        RenderQueueClient client = queue.getIfAvailable();
        if (client == null) {
            throw new RenderUnavailableException();
        }
        if (recipe.getCutInAtMs() == null) {
            throw new RecipeNotRenderableException("cut");
        }

        // 조각이 「연속 uploaded」로 구간을 다 덮어야 주문한다. 조립기의 판정 그대로다(POK-117) — 축만 재생 축이다.
        List<SegmentSource> overlapping = segments.findOverlappingByPlaybackTime(
                streamId, recipe.getCutInAtMs(), recipe.getCutOutAtMs());
        SegmentWindow window = SegmentWindowAssembler.assemble(
                overlapping.stream().map(SegmentSource::asRow).toList(), recipe.getCutInAtMs(), recipe.getCutOutAtMs());
        if (!window.complete()) {
            throw new SourceNotReadyException();
        }
        List<SegmentSource> taken = overlapping.stream()
                .filter(s -> window.segments().stream().anyMatch(r -> r.seq() == s.seq())).toList();
        if (hasPlaybackGap(taken)) {
            throw new SourceNotReadyException();
        }

        RecipeDocument document = RecipeDocument.fromStored(mapper, recipe);
        String trackManifest = broadcasts.findByStreamId(streamId).map(b -> b.getTrackManifest()).orElse(null);

        // 선점 + 주문 기록이 한 트랜잭션. 선점에 졌으면 아무것도 안 쓰고 진행 중인 것을 돌려준다.
        Requested requested = transactions.execute(status -> {
            OptionalLong inserted = inserter.insertIfNoOpen(streamId, recipe.getId(), recipe.getRecipeVersion(),
                    requesterSubject);
            if (inserted.isEmpty()) {
                Clip open = clips.findOpen(recipe.getId(), recipe.getRecipeVersion()).orElseThrow();
                log.info("clip.render.duplicate_request streamId={} recipeId={} recipeVersion={} clipId={}",
                        streamId, recipe.getId(), recipe.getRecipeVersion(), open.getId());
                return new Requested(false, snapshot(open));
            }
            long clipId = inserted.getAsLong();
            UUID jobId = UUID.randomUUID();
            String payload = RenderEnvelope.build(mapper, jobId, clipId, streamId, recipe.getRecipeVersion(),
                    document, taken, properties.segmentBucket(), properties.outputBucket(), trackManifest,
                    Instant.now());
            if (payload.getBytes(StandardCharsets.UTF_8).length > properties.maxMessageBytes()) {
                // 트랜잭션이 통째로 되감긴다 — 선점한 줄도 사라진다.
                throw new MessageTooLargeException();
            }
            jobs.save(RenderJob.queued(jobId, clipId, payload));
            Clip clip = clips.findById(clipId).orElseThrow();
            return new Requested(true, snapshot(clip));
        });

        if (requested.created()) {
            // 커밋 뒤에 싣는다. 실패해도 예외를 올리지 않는다 — 줄은 이미 있고 outbox가 다시 보낸다.
            publisher.publishNow(requested.clip().id());
        }
        return requested;
    }

    /** 이웃 조각의 재생 시각이 {@link #MAX_PLAYBACK_GAP_MS}보다 벌어진 자리가 있으면 true. 번호가 이어져도 시각이 건너뛴 방송이다. */
    static boolean hasPlaybackGap(List<SegmentSource> taken) {
        for (int i = 1; i < taken.size(); i++) {
            SegmentSource prev = taken.get(i - 1);
            long expectedStart = prev.startAtMs() + prev.durationMs();
            if (taken.get(i).startAtMs() - expectedStart > MAX_PLAYBACK_GAP_MS) {
                return true;
            }
        }
        return false;
    }

    /** @throws ClipNotFoundException 그 방송에 그 번호의 영상이 없다 (404) */
    public ClipSnapshot get(String requesterSubject, String streamId, long clipId) {
        guard.requireViewable(requesterSubject, streamId);
        return snapshot(clips.findByIdAndStreamId(clipId, streamId).orElseThrow(() -> new ClipNotFoundException(clipId)));
    }

    ClipSnapshot snapshot(Clip clip) {
        Optional<RenderJob> job = jobs.findByClipId(clip.getId());
        ClipSnapshot.Progress progress = job.map(j -> new ClipSnapshot.Progress(
                j.getProgressPercent(), j.getProgressStage(), j.getAttemptOrdinal(), j.getId())).orElse(null);
        return new ClipSnapshot(clip.getId(), clip.getStreamId(), clip.getRecipeId(), clip.getRecipeVersion(),
                clip.getRequestedBy(), clip.getStatus().dbValue(), progress,
                clip.getOutputs() == null ? null : mapper.readTree(clip.getOutputs()),
                clip.getErrorCode() == null ? null : new ClipSnapshot.Error(clip.getErrorCode(), clip.getErrorMessage()),
                clip.getCreatedAt(), clip.getUpdatedAt());
    }
}
