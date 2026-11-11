package com.pokeclip.clip.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 1번 장부({@code public.stream_segments} · {@code public.stream_sessions})에 회차 · 판이 있는 행을 심는 시험 도우미(POK-196).
 *
 * <p>시각은 {@link #BASE}에서 잰 ms로 받는다 — 설계 예의 숫자(「PDT 100000 · 위치 0」)를 그대로 옮겨 적으려는 것이다.
 * 벽시각은 운영처럼 µs까지 심을 수 있다({@link Row#wallMicros}).
 */
public final class LedgerSeed {

    /** 시드 시각의 0. */
    public static final Instant BASE = Instant.parse("2026-10-23T00:00:00Z");

    private final JdbcTemplate jdbc;

    public LedgerSeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@link #BASE} + ms. */
    public static Instant at(long ms) {
        return BASE.plusMillis(ms);
    }

    /** 세션 행 하나. {@code roundId}가 null이면 옛 회차(계승 사슬로 묶인다). */
    public void session(String sessionId, String ingestKey, String roundId, String inheritsSession) {
        jdbc.update("""
                        INSERT INTO public.stream_sessions (session_id, stream_id, started_at, round_id, inherits_session)
                        VALUES (?, ?, ?, ?, ?)""",
                sessionId, ingestKey, Timestamp.from(BASE), roundId, inheritsSession);
    }

    /** 세션을 끝내고 회차를 닫는다(회차 완결 술어 시험). */
    public void endSession(String sessionId, boolean roundClosed) {
        jdbc.update("UPDATE public.stream_sessions SET state = 'ended', round_closed_at = ? WHERE session_id = ?",
                roundClosed ? Timestamp.from(BASE) : null, sessionId);
    }

    /** 장부 행 하나를 짓기 시작한다. 기본: 길이 4000 · 판 NULL · 올라감 · 이어짐. */
    public Row row(String ingestKey, long seq) {
        return new Row(ingestKey, seq);
    }

    /** 짓는 중인 장부 행. {@link #insert()}로 넣는다. */
    public final class Row {
        private final String ingestKey;
        private final long seq;
        private long ptsMs;
        private long wallMicros;
        private Long pdtMs;
        private int durationMs = 4000;
        private String sessionId;
        private Integer timingVersion;
        private boolean discontinuity;
        private String uploadState = "uploaded";

        private Row(String ingestKey, long seq) {
            this.ingestKey = ingestKey;
            this.seq = seq;
        }

        public Row pts(long ms) {
            this.ptsMs = ms;
            return this;
        }

        /** 벽시각(파일명 시각) — {@link #BASE}에서 잰 ms. */
        public Row wall(long ms) {
            this.wallMicros = Math.multiplyExact(ms, 1000L);
            return this;
        }

        /** 벽시각을 µs까지(운영 판 NULL 행은 µs다). */
        public Row wallMicros(long micros) {
            this.wallMicros = micros;
            return this;
        }

        public Row pdt(long ms) {
            this.pdtMs = ms;
            return this;
        }

        /** 벽시각과 PDT가 같은 행(회차 첫 행 · 래칫이 안 앞선 행). */
        public Row wallAndPdt(long ms) {
            return wall(ms).pdt(ms);
        }

        public Row duration(int ms) {
            this.durationMs = ms;
            return this;
        }

        /** 이 세션에 귀속(null이면 비귀속). */
        public Row session(String id) {
            this.sessionId = id;
            return this;
        }

        public Row v2() {
            this.timingVersion = 2;
            return this;
        }

        public Row discontinuity() {
            this.discontinuity = true;
            return this;
        }

        public Row pending() {
            this.uploadState = "pending";
            return this;
        }

        public void insert() {
            Timestamp wall = Timestamp.from(BASE.plusNanos(Math.multiplyExact(wallMicros, 1000L)));
            Timestamp pdt = pdtMs == null ? null : Timestamp.from(at(pdtMs));
            jdbc.update("""
                            INSERT INTO public.stream_segments
                                   (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms, s3_key,
                                    upload_state, is_discontinuity, session_id, timing_version)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    ingestKey, seq, ptsMs, wall, pdt, durationMs, "seg/" + ingestKey + "/" + seq,
                    uploadState, discontinuity, sessionId, timingVersion == null ? null : timingVersion.shortValue());
        }
    }
}
