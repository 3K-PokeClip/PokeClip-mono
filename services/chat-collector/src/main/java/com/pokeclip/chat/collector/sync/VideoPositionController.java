package com.pokeclip.chat.collector.sync;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 채팅 시각 하나를 그 방송 영상 안의 위치로 바꿔 주는 창구(POK-92).
 *
 * <p>{@code GET /internal/streams/{streamId}/video-position?messageTime=&lt;값&gt;&channelId=&lt;선택&gt;}
 *
 * <p>문은 {@code /internal/*}의 내부 토큰 필터({@code status/InternalApiConfiguration})가 잠근다 —
 * 경로만 맞추면 새 창구도 자동으로 잠긴다. <b>이 서버의 두 번째 HTTP 컨트롤러다.</b>
 *
 * <h2>응답</h2>
 * 200 본문의 {@code positionMs}는 <b>영상 전체 기준 절대 위치</b>이고 {@code segmentSeq}는
 * <b>참고값</b>이다 — 둘을 「이 조각 파일 안에서 seek할 오프셋」 쌍으로 쓰면 경계에서 어긋난다.
 * 근거는 {@link VideoPosition} javadoc에 있다(여기 복사하지 않는다 — 한쪽만 낡는다).
 * {@code appliedOffsetMs}는 판정과 무관하게 늘 실린다.
 *
 * <p><b>표가 없거나 DB가 죽으면 500을 그대로 낸다.</b> 삼켜서 {@code not_yet_indexed} 같은
 * 그럴듯한 답을 만들지 않는다 — 「설정 장애」와 「조각이 아직 안 들어옴」은 완전히 다른 상태이고,
 * 접으면 부르는 쪽이 영영 안 올 것을 영원히 다시 묻는다(수집 상태 창구의 같은 결정과 한 방향이다).
 */
@RestController
public class VideoPositionController {

    /**
     * 400 본문에 싣는 형식 안내. <b>「UTC」라고 쓰지 않는다</b> — {@link Instant#parse}는
     * {@code 2026-08-24T09:00:01+09:00} 같은 오프셋 표기도 받고 <b>그때 결과가 옳다</b>.
     * 「UTC만 된다」고 적으면 되는 것을 안 된다고 알려 주는 셈이다.
     */
    private static final String FORMATS = "epoch ms(1787529601000) 또는 ISO-8601(2026-08-24T12:00:00Z)";

    private final VideoPositionCalculator calculator;

    public VideoPositionController(VideoPositionCalculator calculator) {
        this.calculator = calculator;
    }

    /**
     * @param messageTime {@code required=false}로 받는다 — 기본값이면 스프링이 자기 본문으로 400을
     *                    내고 위 형식 안내가 안 실린다. 형식이 둘인 창구라 그 안내가 곧 진단이다
     * @param channelId   보정값을 고르는 열쇠. 없으면 기본 보정값이 쓰인다
     */
    @GetMapping("/internal/streams/{streamId}/video-position")
    public ResponseEntity<?> get(@PathVariable String streamId,
                                 @RequestParam(value = "messageTime", required = false) String messageTime,
                                 @RequestParam(value = "channelId", required = false) String channelId) {
        if (messageTime == null || messageTime.isBlank()) {
            return ResponseEntity.badRequest().body(new Error("messageTime이 필요하다. " + FORMATS));
        }
        Instant at;
        try {
            at = parse(messageTime);
        } catch (DateTimeParseException | NumberFormatException e) {
            // 받은 값을 본문에 되돌려 싣지 않는다 — 반사된 값이 그대로 로그·화면에 흐른다.
            return ResponseEntity.badRequest().body(new Error("messageTime을 읽을 수 없다. " + FORMATS));
        }
        VideoPosition position = calculator.locate(streamId, channelId, at);
        return ResponseEntity.ok(new Body(streamId, position.state().wireName(),
                position.positionMs(), position.segmentSeq(), position.appliedOffsetMs()));
    }

    /**
     * 숫자로만 이뤄졌으면 epoch ms다 — <b>치지직이 주는 형식이 그것이고</b> 카드가 기준으로
     * 못박아 뒀다. ISO-8601은 어느 자리든 숫자가 아닌 글자({@code -}·{@code T}·{@code Z})가
     * 있으므로 두 형식이 겹치지 않는다.
     */
    private static Instant parse(String raw) {
        return isDigitsOnly(raw) ? Instant.ofEpochMilli(Long.parseLong(raw)) : Instant.parse(raw);
    }

    /** ASCII 숫자만 본다. {@code Character.isDigit}은 다른 문자권 숫자까지 참이라 안 쓴다. */
    private static boolean isDigitsOnly(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 창구의 답 한 장. 필드 이름은 clip과의 약속이다.
     *
     * @param positionMs 판정이 {@code converted}가 아니면 {@code null}이다 — 0을 실으면
     *                   「0초 지점」이라는 <b>그럴듯하게 틀린 답</b>이 된다
     * @param segmentSeq 같은 이유로 {@code converted}일 때만 값이 있다(참고값)
     */
    record Body(String streamId, String state, Long positionMs, Long segmentSeq, long appliedOffsetMs) {
    }

    /** 400 본문. <b>우리가 정한다</b> — 스프링 기본 본문은 어떤 형식이 되는지를 안 알려 준다. */
    record Error(String error) {
    }
}
