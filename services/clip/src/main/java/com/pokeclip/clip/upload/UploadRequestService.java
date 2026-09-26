package com.pokeclip.clip.upload;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.render.Clip;
import com.pokeclip.clip.render.ClipRepository;
import com.pokeclip.clip.render.ClipStatus;
import com.pokeclip.clip.render.RenderErrors.ClipNotFoundException;
import com.pokeclip.clip.render.RenderErrors.ClipNotRenderedException;
import com.pokeclip.clip.render.RenderProperties;
import com.pokeclip.clip.upload.UploadErrors.InvalidUploadRequestException;
import com.pokeclip.clip.upload.UploadErrors.UploadUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 「이 영상을 유튜브에 올려 줘」를 받아 업로드 줄을 쓰고 주문줄에 싣는다(POK-220).
 *
 * <p><b>순서: 자격 → 영상 → 켜짐 → 완성 → 본문 → 선점(표) → 발행(큐).</b> 자격이 맨 앞인 이유는 다른 문과 같다.
 * 승인 게이트는 없다(2026-08-30 결정): 편집자·스트리머 둘 다 자격만 있으면 올린다.
 *
 * <p><b>어느 채널에 올리나 = 방송의 스트리머</b>(ADR-010 Path A). 주문한 사람(편집자일 수 있다)의 채널이 아니다.
 * 토큰은 주문서에 안 싣는다: 일꾼이 올리기 직전에 auth 창구에 묻는다(주문서는 로그·실패 큐에 남는다).
 */
@Service
public class UploadRequestService {

    private static final Logger log = LoggerFactory.getLogger(UploadRequestService.class);

    /** 유튜브 제목 한도(글자). 넘기면 유튜브가 400으로 거절하는데 그때는 이미 주문이 줄에 있다: 여기서 먼저 막는다. */
    static final int MAX_TITLE_CHARS = 100;
    /** 유튜브 설명 한도(UTF-8 바이트). */
    static final int MAX_DESCRIPTION_BYTES = 5000;

    private final BroadcastAccessGuard guard;
    private final ClipRepository clips;
    private final BroadcastRepository broadcasts;
    private final ClipUploadRepository uploads;
    private final UploadInserter inserter;
    private final UploadPublisher publisher;
    private final ObjectProvider<UploadQueueClient> queue;
    private final RenderProperties render;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    UploadRequestService(BroadcastAccessGuard guard, ClipRepository clips, BroadcastRepository broadcasts,
                         ClipUploadRepository uploads, UploadInserter inserter, UploadPublisher publisher,
                         ObjectProvider<UploadQueueClient> queue, RenderProperties render,
                         TransactionTemplate transactions, ObjectMapper mapper) {
        this.guard = guard;
        this.clips = clips;
        this.broadcasts = broadcasts;
        this.uploads = uploads;
        this.inserter = inserter;
        this.publisher = publisher;
        this.queue = queue;
        this.render = render;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    /** 주문 결과. {@code created}가 거짓이면 같은 영상 같은 벌의 살아 있는 업로드를 돌려준 것이다(200 대 201). */
    public record Requested(boolean created, UploadSnapshot upload) {
    }

    /**
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException 방송이 없거나 볼 자격이 없다 (404)
     * @throws ClipNotFoundException 그 방송에 그 번호의 영상이 없다 (404)
     * @throws UploadUnavailableException 주문줄이 꺼져 있다 (503)
     * @throws ClipNotRenderedException 영상이 아직 완성되지 않았다 (409)
     * @throws InvalidUploadRequestException 제목·설명·벌 번호가 규칙에 안 맞는다 (400)
     */
    public Requested request(String requesterSubject, String streamId, long clipId, UploadRequest body) {
        guard.requireViewable(requesterSubject, streamId);
        Clip clip = clips.findByIdAndStreamId(clipId, streamId).orElseThrow(() -> new ClipNotFoundException(clipId));
        if (queue.getIfAvailable() == null) {
            throw new UploadUnavailableException();
        }
        if (clip.getStatus() != ClipStatus.RENDERED || clip.getOutputs() == null) {
            throw new ClipNotRenderedException(clipId);
        }
        String title = title(body == null ? null : body.title());
        String description = description(body == null ? null : body.description());
        Video video = pickVideo(mapper.readTree(clip.getOutputs()), body == null ? null : body.outputId());
        // 방송은 영상이 있으니 반드시 있다(FK). 없으면 우리 버그라 500이 맞다.
        String channelOwner = broadcasts.findByStreamId(streamId).orElseThrow().getStreamerId();

        Requested requested = transactions.execute(status -> {
            OptionalLong inserted = inserter.insertIfNoActive(clipId, video.outputId(), requesterSubject, channelOwner,
                    title, description);
            if (inserted.isEmpty()) {
                // 이미 올렸거나 올리는 중이거나 결과 불명이다: 새로 만들지 않고 그것을 돌려준다.
                ClipUpload active = uploads.findActive(clipId, video.outputId()).orElseThrow();
                log.info("clip.upload.duplicate_request clipId={} outputId={} uploadId={} status={}",
                        clipId, video.outputId(), active.getId(), active.getStatus().dbValue());
                return new Requested(false, UploadSnapshot.of(active));
            }
            long uploadId = inserted.getAsLong();
            inserter.fillPayload(uploadId, payload(uploadId, clip, channelOwner, video, title, description));
            return new Requested(true, UploadSnapshot.of(uploads.findById(uploadId).orElseThrow()));
        });

        if (requested.created()) {
            // 커밋 뒤에 싣는다. 실패해도 예외를 올리지 않는다: 줄은 이미 있고 outbox가 다시 보낸다.
            publisher.publishNow(requested.upload().id());
            log.info("clip.upload.requested uploadId={} clipId={} outputId={} requestedBy={}",
                    requested.upload().id(), clipId, video.outputId(), requesterSubject);
        }
        return requested;
    }

    /** 앞뒤 공백을 걷고 1~100자, {@code <}·{@code >} 금지(유튜브 규칙). */
    static String title(String raw) {
        String title = raw == null ? "" : raw.strip();
        if (title.isEmpty() || title.codePointCount(0, title.length()) > MAX_TITLE_CHARS || hasAngle(title)) {
            throw new InvalidUploadRequestException("title");
        }
        return title;
    }

    static String description(String raw) {
        String description = raw == null ? "" : raw;
        if (description.getBytes(StandardCharsets.UTF_8).length > MAX_DESCRIPTION_BYTES || hasAngle(description)) {
            throw new InvalidUploadRequestException("description");
        }
        return description;
    }

    private static boolean hasAngle(String text) {
        return text.indexOf('<') >= 0 || text.indexOf('>') >= 0;
    }

    /** 올릴 영상 파일. 일꾼이 보고한 산출물(계약1 result) 중 {@code kind=video}. */
    record Video(String outputId, String s3Key) {
    }

    /** 벌 번호를 안 줬으면 영상 파일이 하나일 때만 그것을 고른다: 여럿이면 무엇을 올릴지 우리가 정하지 않는다. */
    static Video pickVideo(JsonNode outputs, String wanted) {
        List<Video> videos = new ArrayList<>();
        for (JsonNode output : outputs) {
            if ("video".equals(output.path("kind").asString(null))) {
                videos.add(new Video(output.path("outputId").asString(), output.path("s3Key").asString()));
            }
        }
        if (wanted == null || wanted.isBlank()) {
            if (videos.size() != 1) {
                throw new InvalidUploadRequestException("outputId");
            }
            return videos.getFirst();
        }
        return videos.stream().filter(v -> v.outputId().equals(wanted)).findFirst()
                .orElseThrow(() -> new InvalidUploadRequestException("outputId"));
    }

    private String payload(long uploadId, Clip clip, String channelOwner, Video video, String title, String description) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("jobType", "UPLOAD");
        root.put("uploadId", String.valueOf(uploadId));
        root.put("clipId", String.valueOf(clip.getId()));
        root.put("streamId", clip.getStreamId());
        // auth 회원 번호(문자열). 일꾼이 이 번호로 유튜브 토큰을 묻는다.
        root.put("channelOwnerUserId", channelOwner);
        ObjectNode source = root.putObject("source");
        source.put("bucket", render.outputBucket());
        source.put("s3Key", video.s3Key());
        source.put("outputId", video.outputId());
        ObjectNode meta = root.putObject("video");
        meta.put("title", title);
        meta.put("description", description);
        // ADR-010: 기본 비공개. 스트리머가 스튜디오에서 확인하고 공개로 바꾼다.
        meta.put("privacyStatus", "private");
        root.put("requestedAt", Instant.now().toString());
        return mapper.writeValueAsString(root);
    }

    /** 영상 하나의 가장 최근 업로드. 영상 조회·보관함이 쓴다. */
    public List<UploadSnapshot> latestFor(List<Long> clipIds) {
        if (clipIds.isEmpty()) {
            return List.of();
        }
        return uploads.findLatestByClipIdIn(clipIds).stream().map(UploadSnapshot::of).toList();
    }
}
