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

    /** 일꾼의 끝 보고. {@code outcome}은 {@code UPLOADED}·{@code FAILED}·{@code CHECKING}. 같은 끝 보고가 다시 오면 200. */
    public Reply result(long uploadId, String outcome, String videoId, String errorCode, String errorMessage) {
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
        String message = errorMessage == null ? null
                : errorMessage.substring(0, Math.min(errorMessage.length(), MAX_ERROR_MESSAGE));

        return transactions.execute(tx -> {
            ClipUpload upload = uploads.findByIdForUpdate(uploadId).orElseThrow(() -> new UploadNotFoundException(uploadId));
            UploadStatus current = upload.getStatus();
            if (current.settled()) {
                boolean same = current == target
                        && (target != UploadStatus.UPLOADED || videoId.equals(upload.getYoutubeVideoId()));
                return same ? ok(upload) : conflict("TERMINAL");
            }
            if (current != UploadStatus.UPLOADING) {
                return conflict("NOT_STARTED");
            }
            switch (target) {
                case UPLOADED -> upload.uploaded(videoId);
                case FAILED -> {
                    if (upload.getSessionUri() != null) {
                        // 일꾼이 「주소는 있었지만 끝나지 않은 것을 확인했다」고 판단한 경우다. 드러나게 남긴다.
                        log.warn("clip.upload.failed_with_session uploadId={} code={}", uploadId, errorCode);
                    }
                    upload.failed(errorCode, message);
                }
                default -> upload.checking(errorCode, message);
            }
            log.info("clip.upload.settled uploadId={} status={} code={}", uploadId, target.dbValue(), errorCode);
            return ok(upload);
        });
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
