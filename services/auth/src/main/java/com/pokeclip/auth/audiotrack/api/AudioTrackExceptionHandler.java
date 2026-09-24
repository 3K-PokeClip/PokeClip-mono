package com.pokeclip.auth.audiotrack.api;

import com.pokeclip.auth.audiotrack.AudioTrackException;
import com.pokeclip.auth.audiotrack.AudioTrackFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * {@code DelegationExceptionHandler}와 같은 모양 — 이 컨트롤러 하나만 지목한다. 전역으로 두면
 * 아래 본문 파싱 실패 갈래가 남의 창구 400까지 가로챈다.
 */
@RestControllerAdvice(assignableTypes = AudioTrackLabelController.class)
public class AudioTrackExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioTrackExceptionHandler.class);

    @ExceptionHandler(AudioTrackException.class)
    public ResponseEntity<Map<String, String>> handle(AudioTrackException e) {
        log.info("auth.audio_tracks.failed reason={}", e.getFailure());
        return ResponseEntity.status(statusOf(e.getFailure())).body(Map.of("reason", e.getFailure().name()));
    }

    /** 본문이 JSON이 아니거나 {@code labels}가 배열이 아니다 — 칸 수 오류와 같은 400, 같은 봉투. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("reason", AudioTrackFailure.LABELS_SIZE.name()));
    }

    private static HttpStatus statusOf(AudioTrackFailure failure) {
        return switch (failure) {
            case LABELS_SIZE, LABEL_TOO_LONG, LABEL_INVALID -> HttpStatus.BAD_REQUEST;
            case STREAMER_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
    }
}
