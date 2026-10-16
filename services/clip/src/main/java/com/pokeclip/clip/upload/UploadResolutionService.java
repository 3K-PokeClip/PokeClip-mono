package com.pokeclip.clip.upload;

import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.render.ClipRepository;
import com.pokeclip.clip.render.RenderErrors.ClipNotFoundException;
import com.pokeclip.clip.upload.UploadErrors.InvalidUploadRequestException;
import com.pokeclip.clip.upload.UploadErrors.ResolutionTooEarlyException;
import com.pokeclip.clip.upload.UploadErrors.UploadNotCheckingException;
import com.pokeclip.clip.upload.UploadErrors.UploadNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 「확인 필요」(checking) 업로드를 사람이 푼다(POK-333). 일꾼은 올라갔는지 모를 때 다시 올리지 않고 멈춘다(채널에 같은 영상이 둘 뜰 수
 * 있다). 그 줄은 판 하나를 묶어 다시 시도 · 같은 판 영상 만들기를 막으므로, 사람이 채널을 보고 결과를 적는 문이 있어야 풀린다.
 *
 * <p><b>순서: 자격 → 영상 → 본문 → 줄 잠금 → 판정.</b> 자격은 다른 업로드 문과 같은 판정이다: 편집자도 푼다(업로드 승인 게이트 없음,
 * 2026-08-31 결정). 판정은 줄을 잠그고 하므로 두 사람이 동시에 눌러도 한 번만 반영된다.
 *
 * <p>같은 결과를 다시 적으면 200으로 그대로 돌려준다(연타). 다른 결과로 덮지는 못한다(409 {@code upload_not_checking}).
 */
@Service
public class UploadResolutionService {

    private static final Logger log = LoggerFactory.getLogger(UploadResolutionService.class);

    /**
     * 「안 올라갔다」를 받기까지 마지막 일꾼 활동에서 기다리는 시간. <b>마지막 일꾼 활동 = 마지막 heartbeat 또는 상태 변경</b>
     * ({@code updated_at}): 일꾼은 처리하는 동안 1분마다 heartbeat를 보낸다({@link UploadReportService#heartbeat}). 그래서 한 일꾼이
     * 아직 같은 이어 올리기 주소로 보내는 동안에는 이 시간이 안 찬다. 일꾼이 죽은 뒤에도 쪽지는 15분(숨김 900초) 뒤 다시 보이고, 다시
     * 잡은 일꾼은 다시 시도 지연을 거쳐 그 주소에 「어디까지 받았나」를 묻는다. 숨김 두 바퀴 = 30분이면 한 번 더 잡힌 일꾼까지 끝난다.
     */
    static final Duration NOT_UPLOADED_AFTER = Duration.ofMinutes(30);

    private final BroadcastAccessGuard guard;
    private final ClipRepository clips;
    private final ClipUploadRepository uploads;
    private final TransactionTemplate transactions;

    UploadResolutionService(BroadcastAccessGuard guard, ClipRepository clips, ClipUploadRepository uploads,
                            TransactionTemplate transactions) {
        this.guard = guard;
        this.clips = clips;
        this.uploads = uploads;
        this.transactions = transactions;
    }

    /**
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException 방송이 없거나 볼 자격이 없다 (404)
     * @throws ClipNotFoundException 그 방송에 그 영상이 없다 (404)
     * @throws UploadNotFoundException 그 영상에 그 업로드가 없다 (404)
     * @throws InvalidUploadRequestException 결과 · 영상 번호가 규칙에 안 맞는다 (400)
     * @throws UploadNotCheckingException 확인 필요가 아니고 같은 결과의 재전송도 아니다 (409)
     * @throws ResolutionTooEarlyException 「안 올라갔다」인데 마지막 일꾼 활동에서 30분이 안 지났다 (409)
     */
    public UploadSnapshot resolve(String requesterSubject, String streamId, long clipId, long uploadId,
                                  UploadResolution body) {
        guard.requireViewable(requesterSubject, streamId);
        clips.findByIdAndStreamId(clipId, streamId).orElseThrow(() -> new ClipNotFoundException(clipId));
        String result = body == null ? null : body.result();
        boolean uploaded = "uploaded".equals(result);
        if (!uploaded && !"not_uploaded".equals(result)) {
            throw new InvalidUploadRequestException("result");
        }
        String videoId = uploaded ? videoIdFrom(body.videoId()) : null;

        return transactions.execute(tx -> {
            ClipUpload upload = uploads.findByIdForUpdate(uploadId)
                    .filter(u -> u.getClipId() == clipId)
                    .orElseThrow(() -> new UploadNotFoundException(uploadId));
            UploadStatus current = upload.getStatus();
            if (current != UploadStatus.CHECKING) {
                if (sameResolution(upload, uploaded, videoId)) {
                    // 연타 · 두 사람이 같은 결과를 적었다: 앞 반영을 그대로 돌려준다(푼 사람 · 시각은 처음 것).
                    return UploadSnapshot.of(upload);
                }
                throw new UploadNotCheckingException(current.dbValue());
            }
            if (uploaded) {
                upload.resolvedUploaded(videoId, requesterSubject);
            } else {
                Instant allowedAt = upload.getUpdatedAt().plus(NOT_UPLOADED_AFTER);
                if (Instant.now().isBefore(allowedAt)) {
                    throw new ResolutionTooEarlyException(allowedAt);
                }
                upload.resolvedNotUploaded(requesterSubject);
            }
            log.info("clip.upload.resolved uploadId={} clipId={} result={} by={}", uploadId, clipId, result,
                    requesterSubject);
            return UploadSnapshot.of(upload);
        });
    }

    /** 사람이 이미 같은 결과로 푼 줄인가. 일꾼이 끝낸 줄(푼 사람 없음)은 아니다: 그것은 「확인 필요가 아니다」로 알린다. */
    private static boolean sameResolution(ClipUpload upload, boolean uploaded, String videoId) {
        if (upload.getResolvedBy() == null) {
            return false;
        }
        return uploaded
                ? upload.getStatus() == UploadStatus.UPLOADED && Objects.equals(upload.getYoutubeVideoId(), videoId)
                : upload.getStatus() == UploadStatus.FAILED
                        && ClipUpload.CONFIRMED_NOT_UPLOADED.equals(upload.getErrorCode());
    }

    /**
     * 영상 번호를 꺼낸다. 번호 그대로이거나, 사람이 채널에서 복사해 오는 주소 넷(youtu.be/번호 · youtube.com/watch?v=번호 ·
     * youtube.com/shorts/번호 · studio.youtube.com/video/번호/...)을 받는다. 번호 모양은 일꾼 보고와 같은 규칙이다.
     */
    static String videoIdFrom(String raw) {
        String value = raw == null ? "" : raw.strip();
        String id = UploadReportService.VIDEO_ID.matcher(value).matches() ? value : fromUrl(value);
        if (id == null || !UploadReportService.VIDEO_ID.matcher(id).matches()) {
            throw new InvalidUploadRequestException("videoId");
        }
        return id;
    }

    private static String fromUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value.contains("://") ? value : "https://" + value);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] parts = path.split("/");
        if (host.equals("youtu.be")) {
            return parts.length > 1 ? parts[1] : null;
        }
        if (host.equals("studio.youtube.com")) {
            return parts.length > 2 && parts[1].equals("video") ? parts[2] : null;
        }
        if (!(host.equals("youtube.com") || host.endsWith(".youtube.com"))) {
            return null;
        }
        if (path.equals("/watch")) {
            return queryParam(uri.getRawQuery(), "v");
        }
        return parts.length > 2 && parts[1].equals("shorts") ? parts[2] : null;
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
