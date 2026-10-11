package com.pokeclip.clip.upload;

import com.pokeclip.clip.upload.UploadErrors.InvalidUploadRequestException;
import com.pokeclip.clip.upload.UploadErrors.UploadNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 업로드 일꾼이 부르는 문 셋의 판정(POK-220). 일꾼은 DB에 안 붙고 이 문으로만 상태를 바꾼다(workers/README.md 규칙).
 *
 * <p><b>🔴 불변식: 영상 바이트는 이 표에 적힌 이어 올리기 주소 하나로만 간다.</b> 유튜브는 한 주소가 바이트를 다 받았을 때만 영상을
 * 만든다. 일꾼 둘이 같은 주문을 동시에 잡아 각자 주소를 받아도 {@link #session}이 먼저 적힌 하나만 남기고 둘 다 그것을 쓰게
 * 하므로 영상은 많아야 하나다. 응답을 못 받았거나 일꾼이 죽었다 다시 돌면 새 주소를 만들지 않고 이 주소에 「어디까지 받았나」를 묻는다.
 *
 * <p>🔴 <b>주소가 적힌 뒤에는 「실패」로 닫지 않는다</b>(PR #198 codex P1). 같은 주소로 두 일꾼이 올리는 중 하나가 실패를 보고해
 * 자리가 비면, 그 사이 다시 주문한 업로드와 늦게 끝난 쪽의 영상이 둘 뜬다. 그래서 그 실패 보고는 {@code checking}으로 받고, 늦게 온
 * 올림 보고는 {@code checking}을 이긴다(정보가 더 많다). 자리는 끝내 안 빈다. 주소를 받기 전의 실패만 실패다.
 *
 * <p>문 셋: {@code start}(잡기) → {@code session}(주소 기록) → {@code result}(끝). 전부 줄을 잠그고 판정한다.
 */
@Service
public class UploadReportService {

    private static final Logger log = LoggerFactory.getLogger(UploadReportService.class);

    /** 유튜브 영상 번호 모양(지금은 11자). 여유를 둔다: 길이가 바뀌어도 성공을 거절하면 안 된다. */
    static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{6,32}");
    static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,32}");
    static final int MAX_SESSION_URI = 2048;
    static final int MAX_ERROR_MESSAGE = 512;
    private static final java.util.Set<String> THUMBNAIL_OUTCOMES = java.util.Set.of("SET", "FAILED", "NONE");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+");

    private final ClipUploadRepository uploads;
    private final TransactionTemplate transactions;

    UploadReportService(ClipUploadRepository uploads, TransactionTemplate transactions) {
        this.uploads = uploads;
        this.transactions = transactions;
    }

    /** 응답 한 벌(상태 코드 + 본문). */
    public record Reply(int status, Map<String, Object> body) {
    }

    /**
     * 일꾼이 주문을 잡았다. 끝난(올림·실패·확인 중) 주문이면 {@code proceed:false}: 같은 쪽지가 두 번 온 것이다.
     * 이미 적힌 주소가 있으면 돌려준다: 일꾼은 새로 만들지 말고 그 주소에 먼저 물어야 한다.
     */
    public Reply start(long uploadId) {
        return transactions.execute(tx -> {
            ClipUpload upload = uploads.findByIdForUpdate(uploadId).orElseThrow(() -> new UploadNotFoundException(uploadId));
            Map<String, Object> body = new LinkedHashMap<>();
            if (upload.getStatus().settled()) {
                body.put("proceed", false);
                body.put("status", upload.getStatus().dbValue());
                return new Reply(200, body);
            }
            upload.started();
            body.put("proceed", true);
            body.put("status", upload.getStatus().dbValue());
            body.put("attempt", upload.getAttemptOrdinal());
            body.put("sessionUri", upload.getSessionUri());
            log.info("clip.upload.started uploadId={} attempt={} resuming={}",
                    uploadId, upload.getAttemptOrdinal(), upload.getSessionUri() != null);
            return new Reply(200, body);
        });
    }

    /**
     * 일꾼이 받은 이어 올리기 주소를 적는다. 먼저 적힌 것이 있으면 그것을 돌려준다: 일꾼은 자기 주소를 버리고 이것을 쓴다
     * (버린 주소는 바이트를 안 받았으니 영상이 안 생긴다).
     */
    public Reply session(long uploadId, String sessionUri) {
        if (sessionUri == null || sessionUri.length() > MAX_SESSION_URI
                || !(sessionUri.startsWith("https://") || sessionUri.startsWith("http://"))) {
            throw new InvalidUploadRequestException("sessionUri");
        }
        return transactions.execute(tx -> {
            ClipUpload upload = uploads.findByIdForUpdate(uploadId).orElseThrow(() -> new UploadNotFoundException(uploadId));
            if (upload.getStatus() != UploadStatus.UPLOADING) {
                return conflict(upload.getStatus().settled() ? "TERMINAL" : "NOT_STARTED");
            }
            boolean first = upload.getSessionUri() == null;
            String kept = upload.recordSession(sessionUri);
            // 주소는 안 찍는다: 그 값이 곧 올리기 권한이다.
            log.info("clip.upload.session uploadId={} recorded={}", uploadId, first);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionUri", kept);
            return new Reply(200, body);
        });
    }

    /** 썸네일 결과 없이 온 끝 보고(옛 일꾼). */
    public Reply result(long uploadId, String outcome, String videoId, String errorCode, String errorMessage) {
        return result(uploadId, outcome, videoId, errorCode, errorMessage, null, null);
    }

    /**
     * 일꾼의 끝 보고. {@code outcome}은 {@code UPLOADED}·{@code FAILED}·{@code CHECKING}. 같은 끝 보고가 다시 오면 200.
     *
     * <p>썸네일 칸 둘(POK-291)은 영상이 올라가는 전이(올리는 중 → 올림 · 확인 중 → 올림) <b>둘 다</b>에서 적는다. 🔴 <b>모양이 틀려도
     * 400으로 거절하지 않는다</b>: 거절하면 일꾼이 같은 보고를 되풀이하다 실패 큐로 가고, 정리기가 영상이 있는데도 확인 중으로 닫는다.
     * 모르는 값은 「칸 없음」으로 다룬다. 같은 끝 보고의 재전송은 처음 값이 이긴다(손대지 않는다).
     *
     * @param thumbnailOutcome   {@code SET}·{@code FAILED}·{@code NONE}. 그 밖·{@code null}은 칸 없음
     * @param thumbnailErrorCode {@code [A-Z0-9_]{1,32}}. 모양이 틀리면 버린다
     */
    public Reply result(long uploadId, String outcome, String videoId, String errorCode, String errorMessage,
                        String thumbnailOutcome, String thumbnailErrorCode) {
        UploadStatus target = switch (outcome == null ? "" : outcome) {
            case "UPLOADED" -> UploadStatus.UPLOADED;
            case "FAILED" -> UploadStatus.FAILED;
            case "CHECKING" -> UploadStatus.CHECKING;
            default -> throw new InvalidUploadRequestException("outcome");
        };
        if (target == UploadStatus.UPLOADED && (videoId == null || !VIDEO_ID.matcher(videoId).matches())) {
            throw new InvalidUploadRequestException("videoId");
        }
        if (target != UploadStatus.UPLOADED && (errorCode == null || !ERROR_CODE.matcher(errorCode).matches())) {
            throw new InvalidUploadRequestException("errorCode");
        }
        String message = redact(errorMessage);
        // Set.of는 null을 물으면 예외를 던진다(400이 아니라 500). 칸 없음을 먼저 가른다.
        String thumbOutcome = thumbnailOutcome != null && THUMBNAIL_OUTCOMES.contains(thumbnailOutcome) ? thumbnailOutcome : null;
        String thumbCode = thumbnailErrorCode != null && ERROR_CODE.matcher(thumbnailErrorCode).matches()
                ? thumbnailErrorCode : null;

        return transactions.execute(tx -> {
            ClipUpload upload = uploads.findByIdForUpdate(uploadId).orElseThrow(() -> new UploadNotFoundException(uploadId));
            UploadStatus current = upload.getStatus();
            // 주소가 있으면 실패 보고는 확인 중으로 받는다(클래스 주석). 판정 전에 바꿔야 같은 보고의 재전송이 200이다(PR #198 codex 2판).
            UploadStatus effective = target == UploadStatus.FAILED && upload.getSessionUri() != null
                    ? UploadStatus.CHECKING : target;
            if (current == UploadStatus.CHECKING && effective == UploadStatus.UPLOADED) {
                // 확인 중이던 것에 늦은 올림 보고가 왔다: 채널에 영상이 있다는 확정이다.
                upload.uploaded(videoId);
                upload.thumbnailReported(thumbOutcome, thumbCode);
                log.info("clip.upload.checking_resolved uploadId={}", uploadId);
                return ok(upload);
            }
            if (current.settled()) {
                boolean same = current == effective
                        && (effective != UploadStatus.UPLOADED || videoId.equals(upload.getYoutubeVideoId()));
                return same ? ok(upload) : conflict("TERMINAL");
            }
            if (current != UploadStatus.UPLOADING) {
                return conflict("NOT_STARTED");
            }
            if (effective != target) {
                // 다른 일꾼이 같은 주소로 아직 올리는 중일 수 있다. 자리를 비우지 않는다.
                log.warn("clip.upload.failed_with_session_held uploadId={} code={}", uploadId, errorCode);
            }
            switch (effective) {
                case UPLOADED -> {
                    upload.uploaded(videoId);
                    upload.thumbnailReported(thumbOutcome, thumbCode);
                }
                case FAILED -> upload.failed(errorCode, message);
                default -> upload.checking(errorCode, message);
            }
            // 요청이 아니라 실제로 저장된 상태를 찍는다. 확인 중을 실패로 세면 사람이 볼 건을 놓친다(PR #198 codex 2판).
            log.info("clip.upload.settled uploadId={} status={} code={}", uploadId, upload.getStatus().dbValue(), errorCode);
            return ok(upload);
        });
    }

    /** 이어 올리기 주소는 그 자체가 올리기 권한이다. 오류 문장에 섞여 와도 화면으로 안 나가게 주소 모양 글자를 지운다. */
    static String redact(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        String cleaned = URL.matcher(errorMessage).replaceAll("[주소 지움]");
        return cleaned.substring(0, Math.min(cleaned.length(), MAX_ERROR_MESSAGE));
    }

    private static Reply ok(ClipUpload upload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", upload.getStatus().dbValue());
        return new Reply(200, body);
    }

    private static Reply conflict(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return new Reply(409, body);
    }
}
