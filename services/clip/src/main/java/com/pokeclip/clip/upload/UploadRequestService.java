package com.pokeclip.clip.upload;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.render.Clip;
import com.pokeclip.clip.render.ClipRepository;
import com.pokeclip.clip.render.ClipStatus;
import com.pokeclip.clip.render.RenderErrors.ClipNotFoundException;
import com.pokeclip.clip.render.RenderErrors.ClipNotRenderedException;
import com.pokeclip.clip.upload.UploadErrors.AlreadyUploadedException;
import com.pokeclip.clip.upload.UploadErrors.InvalidUploadRequestException;
import com.pokeclip.clip.upload.UploadErrors.NothingToRetryException;
import com.pokeclip.clip.upload.UploadErrors.UploadUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 「이 영상을 유튜브에 올려 줘」를 받아 업로드 줄을 쓰고 주문줄에 싣는다(POK-220).
 *
 * <p><b>순서: 자격 → 영상 → 켜짐 → 완성 → 본문 → 선점(표, 판 단위 중복 포함) → 커밋 뒤 발행(큐).</b> 자격이 맨 앞인 이유는 다른 문과 같다.
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
    private final UploadAutoStarter starter;
    private final ObjectProvider<UploadQueueClient> queue;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    UploadRequestService(BroadcastAccessGuard guard, ClipRepository clips, BroadcastRepository broadcasts,
                         ClipUploadRepository uploads, UploadAutoStarter starter, ObjectProvider<UploadQueueClient> queue,
                         TransactionTemplate transactions, ObjectMapper mapper) {
        this.guard = guard;
        this.clips = clips;
        this.broadcasts = broadcasts;
        this.uploads = uploads;
        this.starter = starter;
        this.queue = queue;
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
     * @throws InvalidUploadRequestException 제목·설명·태그·공개 범위·벌 번호가 규칙에 안 맞는다 (400)
     * @throws AlreadyUploadedException 같은 편집본 같은 판의 다른 영상·다른 벌이 이미 올라갔거나 올라가는 중이다 (409, POK-291)
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
        // 옛 문(보관함 「업로드」, 의도 없는 영상)은 썸네일을 안 받는다(POK-291). 나머지 칸은 「영상 만들기」와 같은 규칙이다.
        UploadInfo info = new UploadInfo(
                title(body == null ? null : body.title()),
                description(body == null ? null : body.description()),
                UploadInfoParser.tags(body == null ? null : body.tags()),
                UploadInfoParser.privacy(body == null ? null : body.privacyStatus()),
                body != null && Boolean.TRUE.equals(body.madeForKids()),
                UploadInfo.Thumbnail.none());
        Video video = pickVideo(mapper.readTree(clip.getOutputs()), body == null ? null : body.outputId());
        // 방송은 영상이 있으니 반드시 있다(FK). 없으면 우리 버그라 500이 맞다.
        String channelOwner = broadcasts.findByStreamId(streamId).orElseThrow().getStreamerId();

        Requested requested = transactions.execute(status -> {
            UploadAutoStarter.Started started = starter.enqueue(clip, video, requesterSubject, channelOwner, info);
            if (started.created()) {
                return new Requested(true, UploadSnapshot.of(uploads.findById(started.uploadId()).orElseThrow()));
            }
            ClipUpload active = uploads.findById(started.uploadId()).orElseThrow();
            if (active.getClipId() != clipId || !active.getOutputId().equals(video.outputId())) {
                // 같은 판의 다른 영상(재렌더)이나 다른 벌이 이미 채널에 있거나 가는 중이다. 같은 영상이 둘 뜨는 길이다(POK-291).
                throw new AlreadyUploadedException(active.getId());
            }
            // 이미 올렸거나 올리는 중이거나 결과 불명이다: 새로 만들지 않고 그것을 돌려준다.
            log.info("clip.upload.duplicate_request clipId={} outputId={} uploadId={} status={}",
                    clipId, video.outputId(), active.getId(), active.getStatus().dbValue());
            return new Requested(false, UploadSnapshot.of(active));
        });
        if (requested.created()) {
            // 싣기는 커밋 뒤 훅이 했다(UploadAutoStarter.enqueue).
            log.info("clip.upload.requested uploadId={} clipId={} outputId={} requestedBy={}",
                    requested.upload().id(), clipId, video.outputId(), requesterSubject);
        }
        return requested;
    }

    /**
     * 실패한 업로드를 <b>저장된 정보 그대로</b> 다시 올린다(POK-291, 창 없이). 최신 줄의 칸(벌·제목·설명·태그·공개 범위·아동용·썸네일)을
     * 새 줄로 옮긴다. {@code checking}(결과 불명)은 대상이 아니다: 채널에 이미 있을 수 있어 다시 올리면 둘이 뜬다.
     *
     * @throws NothingToRetryException 이 영상은 한 번도 안 올렸다 (409)
     * @throws AlreadyUploadedException 같은 판의 다른 영상이 이미 올라갔거나 올라가는 중이다 (409)
     */
    public Requested retry(String requesterSubject, String streamId, long clipId) {
        guard.requireViewable(requesterSubject, streamId);
        Clip clip = clips.findByIdAndStreamId(clipId, streamId).orElseThrow(() -> new ClipNotFoundException(clipId));
        if (queue.getIfAvailable() == null) {
            throw new UploadUnavailableException();
        }
        Requested requested = transactions.execute(status -> {
            ClipUpload latest = uploads.findFirstByClipIdOrderByIdDesc(clipId)
                    .orElseThrow(() -> new NothingToRetryException(clipId));
            if (latest.getStatus() != UploadStatus.FAILED) {
                // 살아 있다(올리는 중·올림·확인 필요): 그것을 돌려준다. 연타한 두 번째 누름도 여기로 온다.
                return new Requested(false, UploadSnapshot.of(latest));
            }
            Video video = pickVideo(mapper.readTree(clip.getOutputs()), latest.getOutputId());
            UploadAutoStarter.Started started = starter.enqueue(clip, video, requesterSubject, latest.getChannelOwner(),
                    latest.info());
            if (!started.created()) {
                ClipUpload active = uploads.findById(started.uploadId()).orElseThrow();
                if (active.getClipId() == clipId) {
                    // 연타한 두 다시 시도가 겹쳤다: 앞 누름이 판 잠금 사이에 만든 줄이다. 그것을 돌려준다(위 「살아 있으면」과 같다).
                    return new Requested(false, UploadSnapshot.of(active));
                }
                throw new AlreadyUploadedException(active.getId());
            }
            return new Requested(true, UploadSnapshot.of(uploads.findById(started.uploadId()).orElseThrow()));
        });
        if (requested.created()) {
            log.info("clip.upload.retried uploadId={} clipId={} requestedBy={}", requested.upload().id(), clipId,
                    requesterSubject);
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

    /** 영상 하나의 가장 최근 업로드. 영상 조회·보관함이 쓴다. */
    public List<UploadSnapshot> latestFor(List<Long> clipIds) {
        if (clipIds.isEmpty()) {
            return List.of();
        }
        return uploads.findLatestByClipIdIn(clipIds).stream().map(UploadSnapshot::of).toList();
    }
}
