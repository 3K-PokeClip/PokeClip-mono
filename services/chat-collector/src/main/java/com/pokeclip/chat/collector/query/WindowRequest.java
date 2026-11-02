package com.pokeclip.chat.collector.query;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 물어본 시각 범위. <b>{@code [from, to)}</b>이고 <b>화면 축</b>이다 — 되감기 재생축의
 * 절대시각이지 표에 찍힌 시각이 아니다. 표를 찾을 때 여기에 보정값을 더한다
 * ({@link ChatWindowQuery} 머리의 축 표).
 *
 * <p>형식 그물은 {@code VideoPositionController}의 것을 그대로 옮겼다 — 같은 서버의 같은
 * 종류의 입력이라 두 창구가 다른 값을 받으면 부르는 쪽이 창구마다 다시 배워야 한다.
 * 옮긴 것이지 공유한 것이 아니다: 그쪽은 시각 <b>하나</b>를 받고 사유 문자열도 다르다.
 */
public record WindowRequest(Instant from, Instant to) {

    /**
     * 받아 주는 시각의 범위. {@code long}에는 들어가는데 PostgreSQL {@code timestamptz}에는
     * 안 들어가는 구간을 그대로 던지면 <b>500</b>이 되는데, 이 창구에서 500은
     * 「DB가 죽었다」로 계약된 신호다 — 부르는 쪽 입력 오류를 그 신호에 실으면 없는 장애를 쫓게 만든다.
     */
    private static final Instant EARLIEST = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2200-01-01T00:00:00Z");

    /**
     * 커서도 같은 범위를 쓴다({@link ChatWindowCursor}). <b>값을 복제하지 않고 여기서
     * 가져간다</b> — 같은 표의 같은 칸을 가리키는 값이 창구마다 다른 범위를 갖는 것이
     * 이상하고, 복제하면 한쪽만 고쳐져 낡는다.
     */
    static final long EARLIEST_MILLIS = EARLIEST.toEpochMilli();

    static final long LATEST_MILLIS = LATEST.toEpochMilli();

    /**
     * @param max 이 값을 넘는 범위는 {@code too_wide}다. 상한이 없으면 방송 8시간치 채팅이
     *            한 질의로 나가 창구가 통째로 멈춘다
     * @throws InvalidWindowException 사유는 {@code missing}·{@code unreadable}·
     *                                {@code out_of_range}·{@code inverted}·{@code too_wide}
     */
    public static WindowRequest parse(String from, String to, Duration max) {
        Instant lo = parseOne(from);
        Instant hi = parseOne(to);
        if (!hi.isAfter(lo)) {
            // to == from도 거절한다. 빈 범위는 「0건」과 「잘못 물었다」가 구분이 안 된다.
            throw new InvalidWindowException("inverted");
        }
        if (Duration.between(lo, hi).compareTo(max) > 0) {
            throw new InvalidWindowException("too_wide");
        }
        return new WindowRequest(lo, hi);
    }

    /**
     * 시각 <b>하나</b>짜리 입력도 같은 그물을 쓴다({@code broadcast-info}의 {@code since}).
     * 창구마다 다른 값을 받으면 부르는 쪽이 창구마다 다시 배운다 — 이 클래스가 옮겨 온 이유와 같다.
     */
    public static Instant parseAt(String raw) {
        return parseOne(raw);
    }

    private static Instant parseOne(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidWindowException("missing");
        }
        Instant at;
        try {
            at = isDigitsOnly(raw) ? Instant.ofEpochMilli(Long.parseLong(raw)) : Instant.parse(raw);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new InvalidWindowException("unreadable");
        }
        if (at.isBefore(EARLIEST) || at.isAfter(LATEST)) {
            throw new InvalidWindowException("out_of_range");
        }
        return at;
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
}
