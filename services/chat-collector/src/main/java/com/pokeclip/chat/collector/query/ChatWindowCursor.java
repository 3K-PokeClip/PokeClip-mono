package com.pokeclip.chat.collector.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 다음 장의 시작점. 모양은 <b>{@code base64url("h:<ms>:<c|d>:<id>")}</b>이다.
 *
 * <p>앞의 {@code h}는 <b>우리 모양이라는 표식</b>이다 — 없으면 다른 창구의 커서를 넣어도
 * 숫자만 맞으면 통과한다. clip은 이 문자열을 <b>그대로 나르기만</b> 한다(계획 검증 F12).
 *
 * <p><b>base64로 감싸는 이유는 숨기려는 것이 아니다</b>(내용은 누구나 푼다) — 부르는 쪽이
 * 안을 뜯어 계산하지 않게 하려는 것이다. 안에 든 것은 표 PK와 표 시각이라 모양이 바뀔 수 있다.
 */
public final class ChatWindowCursor {

    private static final String TAG = "h";

    private ChatWindowCursor() {
    }

    /**
     * @param timeMillis <b>표 축</b>의 시각(보정을 더한 값)이다. 화면 축을 넣으면 다음 장이
     *                   보정값만큼 어긋난다
     * @param kind       {@code c}(채팅) 또는 {@code d}(후원). 이 한 글자가 {@code afterFor}의
     *                   네 갈래를 가른다
     */
    public static String encode(long timeMillis, String kind, long id) {
        String raw = TAG + ":" + timeMillis + ":" + kind + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return 비었으면 {@code null}(첫 장). <b>남의 모양을 조용히 첫 장으로 접지 않는다</b> —
     *         접으면 페이징이 어긋난 자리에서 같은 장을 영원히 다시 주는 것이 정상처럼 보인다
     * @throws InvalidCursorException 우리 모양이 아니다
     */
    public static Cursor decodeOrFirstPage(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("커서가 base64url이 아니다");
        }
        String[] parts = raw.split(":");
        if (parts.length != 4 || !TAG.equals(parts[0])
                || !("c".equals(parts[2]) || "d".equals(parts[2]))) {
            throw new InvalidCursorException("커서 모양이 우리 것이 아니다");
        }
        try {
            return new Cursor(Long.parseLong(parts[1]), parts[2], Long.parseLong(parts[3]));
        } catch (NumberFormatException e) {
            throw new InvalidCursorException("커서 안의 숫자를 읽을 수 없다");
        }
    }

    /**
     * 앞 장의 마지막 항목. 정렬 열쇠 셋이 그대로 들어 있다 — 셋 중 하나라도 빠지면
     * 같은 시각의 뒷줄이 통째로 빠지거나 재출력된다.
     */
    public record Cursor(long timeMillis, String kind, long id) {
    }
}
