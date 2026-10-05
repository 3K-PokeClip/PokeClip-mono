package com.pokeclip.clip.thumbnail.api;

import com.pokeclip.clip.thumbnail.ThumbnailKind;
import com.pokeclip.clip.thumbnail.ThumbnailRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 사진 일꾼의 보고 문(POK-277). {@code /internal/**}이라 {@code X-Internal-Token}으로만 들어온다.
 *
 * <p>보고에 키가 없다. 키는 clip이 대상으로 정한다({@link ThumbnailKind#keyOf}): 일꾼이 아무 키나 적어 남의 파일을 사진으로 내보내는
 * 길이 없다. 그래서 대상 번호의 모양만 본다. 방송 번호는 계약9의 글자 집합, 카드·영상 번호는 숫자다.
 *
 * <p>답: 200 {@code {"saved": true|false}}(false는 더 늦은 장면이 이미 있어 안 덮었다는 뜻이고 실패가 아니다) · 400 모양이 틀렸다.
 * 일꾼은 200이면 메시지를 지운다.
 */
@RestController
public class ThumbnailReportController {

    private static final Pattern STREAM_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,19}");

    private final ThumbnailRepository thumbnails;
    private final Clock clock;

    ThumbnailReportController(ThumbnailRepository thumbnails) {
        this.thumbnails = thumbnails;
        this.clock = Clock.systemUTC();
    }

    public record ReportBody(String kind, String targetId, String capturedAt) {
    }

    @PostMapping("/internal/thumbnails")
    public ResponseEntity<Map<String, Object>> report(@RequestBody ReportBody body) {
        Optional<ThumbnailKind> kind = ThumbnailKind.fromValue(body.kind());
        if (kind.isEmpty()) {
            return invalid("kind");
        }
        Pattern shape = kind.get() == ThumbnailKind.LIVE ? STREAM_ID : NUMBER;
        if (body.targetId() == null || !shape.matcher(body.targetId()).matches()) {
            return invalid("targetId");
        }
        Instant capturedAt;
        try {
            capturedAt = Instant.parse(body.capturedAt());
        } catch (NullPointerException | DateTimeParseException e) {
            return invalid("capturedAt");
        }
        boolean saved = thumbnails.saveCaptured(kind.get(), body.targetId(), capturedAt, clock.instant());
        return ResponseEntity.ok(Map.of("saved", saved));
    }

    private static ResponseEntity<Map<String, Object>> invalid(String field) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_thumbnail_report", "field", field));
    }
}
