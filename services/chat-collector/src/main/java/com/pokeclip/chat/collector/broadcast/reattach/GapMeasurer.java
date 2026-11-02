package com.pokeclip.chat.collector.broadcast.reattach;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * 그 방송에서 우리가 <b>확실히 받은 마지막 순간</b>부터 지금까지가 얼마인지 잰다.
 *
 * <p>DB 접근은 이 서버의 다른 곳과 같은 {@link JdbcTemplate}이다({@code EndedStreamStore}·
 * {@code SegmentLedger} 선례).
 */
@Component
public class GapMeasurer {

    /**
     * <b>부분 색인 {@code idx_chat_messages_stream_received}(V302)를 탄다</b> —
     * 선두 칸이 {@code stream_id}이고 둘째가 {@code received_at}이라 PostgreSQL이
     * {@code MAX()}를 {@code Index Only Scan Backward + Limit 1}로 바꾼다.
     * 못 타면 채팅 표를 통째로 훑고, 방송이 많으면 재부착이 통째로 느려진다.
     * {@code GapMeasurerTest.조회가_색인을_탄다}가 {@code EXPLAIN}으로 그것을 잰다.
     */
    /**
     * <b>package-private인 이유는 검사가 이 문자열 자체를 {@code EXPLAIN} 하기 위해서다.</b>
     * 검사가 SQL을 손으로 베껴 적으면 <b>사본만 색인을 타는지 재고 운영 질의는 아무도 안 본다</b> —
     * 결함 주입 N(색인을 못 타게 바꿈)이 실제로 초록이었다. POK-218이 「리터럴 EXPLAIN은 앱이
     * 던지는 것과 다를 수 있다」로 데인 자리와 같은 뿌리다.
     */
    static final String LAST_RECEIVED =
            "SELECT MAX(received_at) FROM chat_messages WHERE stream_id = ?";

    private final JdbcTemplate jdbc;

    public GapMeasurer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param broadcastStartedAt clip이 준 방송 시작 시각. clip이 {@code null}을 준 줄은
     *                           부르는 쪽이 {@link Instant#EPOCH}로 바꿔 넘긴다
     * @return 기준·기준 시각·경과 밀리초. 기준을 모르면 {@link Gap.Basis#UNKNOWN}이고 {@code -1}이다
     */
    public Gap measure(String streamId, Instant broadcastStartedAt, Instant now) {
        Timestamp last = jdbc.queryForObject(LAST_RECEIVED, Timestamp.class, streamId);
        if (last != null) {
            return gapFrom(Gap.Basis.LAST_CHAT, last.toInstant(), now);
        }
        if (Instant.EPOCH.equals(broadcastStartedAt)) {
            // 시작 시각을 못 받은 방송이다. 1970년부터 재면 56년이 찍혀 로그가 거짓말을 한다.
            return new Gap(Gap.Basis.UNKNOWN, null, -1L);
        }
        return gapFrom(Gap.Basis.BROADCAST_START, broadcastStartedAt, now);
    }

    /** 음수를 안 만든다 — 시계가 흔들리거나 채팅 시각이 미래면 0이 정직하다. */
    private static Gap gapFrom(Gap.Basis basis, Instant since, Instant now) {
        return new Gap(basis, since, Math.max(0L, Duration.between(since, now).toMillis()));
    }
}
