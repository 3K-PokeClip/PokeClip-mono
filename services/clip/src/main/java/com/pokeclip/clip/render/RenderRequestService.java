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
import com.pokeclip.clip.upload.ClipUploadRepository;
import com.pokeclip.clip.upload.UploadAutoStarter;
import com.pokeclip.clip.upload.UploadErrors;
import com.pokeclip.clip.upload.UploadErrors.AlreadyUploadedException;
import com.pokeclip.clip.upload.UploadErrors.InvalidUploadRequestException;
import com.pokeclip.clip.upload.UploadErrors.UploadUnavailableException;
import com.pokeclip.clip.upload.UploadInfo;
import com.pokeclip.clip.upload.UploadInfoParser;
import com.pokeclip.clip.upload.UploadQueueClient;
import com.pokeclip.clip.upload.UploadRequestBrief;
import com.pokeclip.clip.upload.UploadRequestStore;
import com.pokeclip.clip.upload.UploadSnapshot;
import com.pokeclip.clip.upload.UploadThumbnailStore;
import com.pokeclip.clip.upload.YoutubeLinkStatusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
    private final ClipUploadRepository uploads;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    // ── POK-291: 「영상 만들기」가 유튜브 업로드까지 받는다 ──
    private final ObjectProvider<UploadQueueClient> uploadQueue;
    private final ObjectProvider<UploadThumbnailStore> thumbnailStore;
    private final UploadRequestStore requests;
    private final UploadAutoStarter starter;
    private final YoutubeLinkStatusClient linkStatus;

    RenderRequestService(ObjectProvider<RenderQueueClient> queue, RenderProperties properties,
                         BroadcastAccessGuard guard, BroadcastRepository broadcasts, RecipeRepository recipes,
                         StreamSegmentReader segments, ClipInserter inserter, ClipRepository clips,
                         RenderJobRepository jobs, RenderPublisher publisher, ClipUploadRepository uploads,
                         TransactionTemplate transactions, ObjectMapper mapper,
                         ObjectProvider<UploadQueueClient> uploadQueue, ObjectProvider<UploadThumbnailStore> thumbnailStore,
                         UploadRequestStore requests, UploadAutoStarter starter, YoutubeLinkStatusClient linkStatus) {
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
        this.uploads = uploads;
        this.transactions = transactions;
        this.mapper = mapper;
        this.uploadQueue = uploadQueue;
        this.thumbnailStore = thumbnailStore;
        this.requests = requests;
        this.starter = starter;
        this.linkStatus = linkStatus;
    }

    /** 주문 결과. {@code created}가 거짓이면 같은 편집본 같은 판이 이미 진행 중이라 그것을 돌려준 것이다(200 대 201). */
    public record Requested(boolean created, ClipSnapshot clip) {
    }

    /** 본문 없는 주문(렌더만). */
    public Requested request(String requesterSubject, String streamId, long recipeId) {
        return request(requesterSubject, streamId, recipeId, null, null, false);
    }

    /**
     * 「영상 만들기」(POK-291). 본문에 {@code upload}가 없으면 렌더만 한다(지금까지와 같다). 있으면 순서가 계약이다.
     * ① 자격·편집본(404) ② 업로드 줄 꺼짐(503) ③ 본문 검사(400·415) ④ 유튜브 연결 미리 확인(409, auth 장애면 그냥 간다)
     * ⑤ 같은 판 중복(409) ⑥ 그림을 창고에(503) ⑦ 갈래: (a) 같은 판이 만드는 중이면 의도만 덮어쓰고 그 영상(200)
     * · (b) 같은 판 최신 영상이 완성이면 다시 안 만들고 그 영상에 바로 업로드 줄(200) · (c) 그 밖은 렌더 주문 + 의도(201).
     * 의도는 갈래와 같은 트랜잭션에 쓴다: 렌더만 남고 정보가 사라지는 틈이 없다.
     *
     * <p>⑥은 트랜잭션 밖이다. 뒤 갈래 처리가 거절되거나 롤백되면 그 그림을 최선 노력으로 지운다(POK-291 로컬 리뷰 1라운드: 안 지우면
     * 그 사이 탈퇴 정리가 이미 끝난 경우 영영 남는다). 지우기도 실패하면 스트리머 접두사라 다음 탈퇴 정리가 덮는다.
     *
     * @param requestJson JSON 본문({@code {"upload": …}}) 또는 multipart의 {@code request} 파트. 없으면 {@code null}
     * @param thumbnail   multipart의 {@code thumbnail} 파트. 없으면 {@code null}
     * @param multipart   multipart로 왔나. 그때는 {@code upload}가 반드시 있어야 한다
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException 방송이 없거나 볼 자격이 없다 (404)
     * @throws RecipeNotFoundException 그 방송에 그 편집본이 없다 (404)
     * @throws UploadUnavailableException 업로드 줄이 꺼져 있다 (503)
     * @throws InvalidUploadRequestException 업로드 정보가 규칙에 안 맞는다 (400 {@code field})
     * @throws UploadErrors.UnsupportedImageException 그림이 JPEG·PNG가 아니다 (415)
     * @throws UploadErrors.YoutubeNotLinkedException 스트리머의 유튜브 채널이 연결돼 있지 않다 (409)
     * @throws AlreadyUploadedException 같은 판이 이미 올라갔거나 올라가는 중이다 (409)
     * @throws UploadErrors.ThumbnailStoreUnavailableException 그림을 창고에 못 뒀다 (503)
     * @throws RenderUnavailableException 렌더 주문줄이 꺼져 있다 (503, 갈래 c)
     * @throws RecipeNotRenderableException 구간이 없는 템플릿이다 (400 {@code cut}, 갈래 c)
     * @throws SourceNotReadyException 구간의 조각이 아직 다 안 올라왔다 (409, 갈래 c)
     * @throws MessageTooLargeException 주문서가 상한을 넘는다 (422, 갈래 c)
     */
    public Requested request(String requesterSubject, String streamId, long recipeId, String requestJson,
                             MultipartFile thumbnail, boolean multipart) {
        guard.requireViewable(requesterSubject, streamId);
        Recipe recipe = recipes.findByIdAndStreamId(recipeId, streamId)
                .orElseThrow(() -> new RecipeNotFoundException(recipeId));
        JsonNode upload = uploadNode(requestJson, multipart);
        if (upload == null) {
            if (thumbnail != null) {
                throw new InvalidUploadRequestException("thumbnail");
            }
            return render(requesterSubject, streamId, recipe, null);
        }

        if (uploadQueue.getIfAvailable() == null) {
            // 렌더 전에 막는다. 렌더만 하고 업로드를 못 하면 사람은 「올렸다」고 믿는다.
            throw new UploadUnavailableException();
        }
        Long cutLength = recipe.getCutInAtMs() == null ? null : recipe.getCutOutAtMs() - recipe.getCutInAtMs();
        UploadInfo info = UploadInfoParser.parse(upload, cutLength, thumbnail != null);
        String contentType = thumbnail == null ? null : UploadThumbnailStore.contentTypeOf(head(thumbnail));
        String streamerId = broadcasts.findStreamerIdByStreamId(streamId).orElseThrow();
        requireLinkedUnlessUnknown(streamerId);
        uploads.findActiveForVersion(recipe.getId(), recipe.getRecipeVersion()).ifPresent(active -> {
            throw new AlreadyUploadedException(active.getId());
        });
        String storedKey = null;
        if (thumbnail != null) {
            storedKey = storeThumbnail(streamerId, contentType, thumbnail);
            info = info.withThumbnail(UploadInfo.Thumbnail.file(storedKey, contentType));
        }

        UploadIntent intent = new UploadIntent(requesterSubject, info);
        try {
            Optional<Clip> latest = clips.findLatestOfVersion(recipe.getId(), recipe.getRecipeVersion());
            if (latest.isPresent() && latest.get().getStatus() != ClipStatus.FAILED) {
                Requested existing = transactions.execute(status -> joinExisting(streamId, recipe, intent).orElse(null));
                if (existing != null) {
                    return existing;
                }
            }
            return render(requesterSubject, streamId, recipe, intent);
        } catch (RuntimeException e) {
            if (storedKey != null) {
                discardThumbnail(storedKey);
            }
            throw e;
        }
    }

    /** 거절·롤백된 주문의 그림을 지운다. 실패는 로그만: 원래 오류가 사람에게 갈 답이다. */
    private void discardThumbnail(String key) {
        try {
            thumbnailStore.getObject().delete(key);
        } catch (RuntimeException e) {
            log.warn("clip.upload.thumbnail_cleanup_failed reason={}", e.getClass().getSimpleName());
        }
    }

    /** 의도 한 벌: 누가 눌렀나 + 고른 정보. */
    private record UploadIntent(String requestedBy, UploadInfo info) {
    }

    /**
     * 갈래 (a)·(b). 트랜잭션 안에서 다시 본다: 밖에서 본 상태는 그 사이에 바뀔 수 있다. 둘 다 아니게 됐으면 빈 값을 돌려
     * 갈래 (c)로 넘긴다(이 트랜잭션은 아무것도 안 쓴다).
     *
     * <p>🔴 (a)는 만드는 중인 영상 줄을 <b>잠근 뒤에</b> 의도를 쓴다(POK-291 로컬 리뷰 1라운드). 렌더 성공 보고는 영상 줄을 잠근 뒤 의도를
     * {@code FOR UPDATE}로 읽는데, 아직 커밋 안 된 의도는 그 읽기에 안 보이고 기다리게 하지도 않는다. 안 잠그면 성공 쪽은 「의도 없음」으로,
     * 이쪽은 「만드는 중」으로 끝나 완성 + 의도 + 업로드 없음이 남는다(응답은 「다 만들어지면 올린다」). 잠그면 둘이 줄을 선다: 성공이 먼저면
     * 그 뒤 완성으로 보여 (b)로, 이쪽이 먼저면 성공이 이 의도를 읽는다. 실패로 끝났으면 (c)다.
     *
     * <p>잠금 순서: 방송 줄({@code FOR KEY SHARE}) → 영상 줄 → 의도 줄. 탈퇴 정리({@code broadcasts → render_jobs → clips})와 같은 방향이다.
     */
    private Optional<Requested> joinExisting(String streamId, Recipe recipe, UploadIntent intent) {
        requests.lockBroadcast(streamId);
        Optional<Long> open = clips.lockOpenId(recipe.getId(), recipe.getRecipeVersion());
        if (open.isPresent()) {
            // (a) 만드는 중: 의도만 덮어쓴다. 성공 보고가 이 커밋 뒤에 최신 의도를 읽어 올린다.
            requests.upsert(streamId, recipe.getId(), recipe.getRecipeVersion(), intent.requestedBy(), intent.info());
            log.info("clip.render.upload_intent_on_open clipId={} recipeId={} recipeVersion={}",
                    open.get(), recipe.getId(), recipe.getRecipeVersion());
            return Optional.of(new Requested(false, snapshot(clips.findById(open.get()).orElseThrow())));
        }
        Optional<Clip> latest = clips.findLatestOfVersion(recipe.getId(), recipe.getRecipeVersion());
        if (latest.isEmpty() || latest.get().getStatus() != ClipStatus.RENDERED) {
            return Optional.empty();
        }
        // (b) 이미 완성: 다시 안 만든다. 렌더 성공 때와 같은 메서드로 업로드 줄을 만든다(같은 뿌리 한 자리).
        requests.upsert(streamId, recipe.getId(), recipe.getRecipeVersion(), intent.requestedBy(), intent.info());
        Clip rendered = latest.get();
        UploadAutoStarter.Started started = starter.startFromRequest(rendered);
        if (!started.created()) {
            // 판 잠금 안에서 본 중복이다(밖의 ⑤ 뒤에 끼어든 요청). 예외로 의도 덮어쓰기까지 되감는다.
            throw new AlreadyUploadedException(started.uploadId());
        }
        log.info("clip.render.upload_on_rendered clipId={} uploadId={}", rendered.getId(), started.uploadId());
        return Optional.of(new Requested(false, snapshot(rendered)));
    }

    /**
     * 렌더 주문(지금까지의 길). 순서: 주문줄 켜짐(503) → 구간(400) → 조각 준비(409) → 선점 + 주문 기록(+ 의도)이 한 트랜잭션 → 커밋 뒤 발행.
     *
     * @param intent 「렌더 뒤 업로드」 의도. 없으면 {@code null}(렌더만)
     */
    private Requested render(String requesterSubject, String streamId, Recipe recipe, UploadIntent intent) {
        RenderQueueClient client = queue.getIfAvailable();
        if (client == null) {
            throw new RenderUnavailableException();
        }
        if (recipe.getCutInAtMs() == null) {
            throw new RecipeNotRenderableException("cut");
        }

        // 조각이 「연속 uploaded」로 구간을 다 덮어야 주문한다. 조립기의 판정 그대로다(POK-117) — 축만 재생 축이다.
        // 조각 장부는 물리 키로 찾는다(POK-233). 자격 판정을 지났으니 줄은 있다
        String ingestKey = broadcasts.findIngestKeyByStreamId(streamId).orElse(streamId);
        List<SegmentSource> overlapping = segments.findOverlappingByPlaybackTime(
                ingestKey, recipe.getCutInAtMs(), recipe.getCutOutAtMs());
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

        // 선점 + 주문 기록(+ 의도)이 한 트랜잭션. 선점에 졌으면 진행 중인 것을 돌려준다. 의도가 있으면 갈래 (a)·(b)와 같은 길
        // (joinExisting)로 간다: 막던 영상 줄을 잠그고 다시 본다. 의도는 선점 <b>뒤에</b> 쓴다: 먼저 쓰면 의도 줄 → 영상 줄 순서로 잠가
        // 성공 보고(영상 줄 → 의도 줄)와 교착한다.
        Requested requested = transactions.execute(status -> {
            if (intent != null) {
                requests.lockBroadcast(streamId);
            }
            OptionalLong inserted = inserter.insertIfNoOpen(streamId, recipe.getId(), recipe.getRecipeVersion(),
                    requesterSubject);
            if (inserted.isEmpty() && intent != null) {
                Optional<Requested> joined = joinExisting(streamId, recipe, intent);
                if (joined.isPresent()) {
                    return joined.get();
                }
                // 막던 영상이 그 사이 실패로 끝났다: 갈래 (c) 그대로 한 번 더 선점한다. 또 지면 그 사이 선점한 영상에 붙는다.
                inserted = inserter.insertIfNoOpen(streamId, recipe.getId(), recipe.getRecipeVersion(), requesterSubject);
                if (inserted.isEmpty()) {
                    return joinExisting(streamId, recipe, intent).orElseThrow();
                }
            }
            if (inserted.isEmpty()) {
                Clip open = clips.findOpen(recipe.getId(), recipe.getRecipeVersion()).orElseThrow();
                log.info("clip.render.duplicate_request streamId={} recipeId={} recipeVersion={} clipId={}",
                        streamId, recipe.getId(), recipe.getRecipeVersion(), open.getId());
                return new Requested(false, snapshot(open));
            }
            long clipId = inserted.getAsLong();
            if (intent != null) {
                requests.upsert(streamId, recipe.getId(), recipe.getRecipeVersion(), intent.requestedBy(), intent.info());
            }
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

    /** 본문에서 {@code upload} 칸을 꺼낸다. 없거나 {@code null}이면 렌더만이다. multipart인데 없으면 400이다. */
    private JsonNode uploadNode(String requestJson, boolean multipart) {
        if (requestJson == null || requestJson.isBlank()) {
            if (multipart) {
                throw new InvalidUploadRequestException("upload");
            }
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(requestJson);
        } catch (JacksonException e) {
            throw new InvalidUploadRequestException("upload");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidUploadRequestException("upload");
        }
        JsonNode upload = root.get("upload");
        if (upload == null || upload.isNull()) {
            if (multipart) {
                throw new InvalidUploadRequestException("upload");
            }
            return null;
        }
        return upload;
    }

    /**
     * auth가 「연결 안 됨」이라고 <b>확실히</b> 답했을 때만 막는다. 못 물으면 그냥 간다(렌더를 auth 장애로 막지 않는다).
     * 회원 번호가 숫자가 아니면(옛 자료) 물을 수 없으니 역시 그냥 간다.
     */
    private void requireLinkedUnlessUnknown(String streamerId) {
        long userId;
        try {
            userId = Long.parseLong(streamerId);
        } catch (NumberFormatException e) {
            log.warn("clip.upload.link_check_skipped cause=streamer_not_numeric");
            return;
        }
        YoutubeLinkStatusClient.Status status = linkStatus.status(userId);
        if (status.notLinked()) {
            throw new UploadErrors.YoutubeNotLinkedException(status.reason());
        }
    }

    private static byte[] head(MultipartFile thumbnail) {
        try (InputStream in = thumbnail.getInputStream()) {
            return in.readNBytes(8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String storeThumbnail(String streamerId, String contentType, MultipartFile thumbnail) {
        UploadThumbnailStore store = thumbnailStore.getIfAvailable();
        if (store == null) {
            // 업로드 줄이 켜졌으면 함께 생기는 빈이다. 없으면 설정이 갈린 것이라 창고 장애로 접는다.
            throw new UploadErrors.ThumbnailStoreUnavailableException(new IllegalStateException("no store"));
        }
        try (InputStream in = thumbnail.getInputStream()) {
            return store.put(streamerId, contentType, in, thumbnail.getSize());
        } catch (IOException e) {
            throw new UploadErrors.ThumbnailStoreUnavailableException(e);
        }
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
        UploadRequestStore.Key key = new UploadRequestStore.Key(clip.getRecipeId(), clip.getRecipeVersion());
        return snapshot(clip, jobs.findByClipId(clip.getId()),
                uploads.findFirstByClipIdOrderByIdDesc(clip.getId()).map(UploadSnapshot::of),
                Optional.ofNullable(requests.briefs(List.of(key)).get(key)));
    }

    /**
     * 영상 한 벌을 응답 모양으로. <b>보관함(POK-243)도 이것을 쓴다</b> — 같은 영상이 주문 문과 보관함에서 다른 모양으로
     * 나가면 화면이 두 벌로 처리한다. 주문은 목록이 한 번에 읽어 넘긴다(줄마다 묻지 않으려고).
     */
    public ClipSnapshot snapshot(Clip clip, Optional<RenderJob> job, Optional<UploadSnapshot> upload,
                                 Optional<UploadRequestBrief> uploadRequest) {
        ClipSnapshot.Progress progress = job.map(j -> new ClipSnapshot.Progress(
                j.getProgressPercent(), j.getProgressStage(), j.getAttemptOrdinal(), j.getId())).orElse(null);
        return new ClipSnapshot(clip.getId(), clip.getStreamId(), clip.getRecipeId(), clip.getRecipeVersion(),
                clip.getRequestedBy(), clip.getStatus().dbValue(), progress,
                clip.getOutputs() == null ? null : mapper.readTree(clip.getOutputs()),
                clip.getErrorCode() == null ? null : new ClipSnapshot.Error(clip.getErrorCode(), clip.getErrorMessage()),
                clip.getCreatedAt(), clip.getUpdatedAt(), upload.orElse(null), uploadRequest.orElse(null));
    }
}
