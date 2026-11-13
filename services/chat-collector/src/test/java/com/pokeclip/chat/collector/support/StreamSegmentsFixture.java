package com.pokeclip.chat.collector.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 조각 장부(`stream_segments`)를 검사용으로 세운다.
 *
 * <p><b>이 표는 우리 것이 아니다.</b> 소유는 1번(Media)이고 운영에서는
 * {@code segment-indexer}가 부팅할 때 만든다(저장소 루트 {@code docker-compose.yml},
 * {@code ENSURE_SCHEMA=true}). chat-collector의 Flyway는 이 표를 만들지도 지우지도 않는다 —
 * 그래서 검사가 직접 세워야 한다.
 *
 * <p><b>🔴 Flyway 밖이라 스프링 컨텍스트가 갈려도 표는 하나다.</b> 검사 클래스 셋이
 * 같은 Testcontainers 컨테이너를 공유하므로 방송 번호가 겹치면 PK{@code (stream_id, seq)}
 * 충돌·행 오염이 나고 <b>단독으로는 통과하고 전체에서만 터진다</b>. 검사 클래스마다
 * 방송 번호 접두를 다르게 쓰고({@code ledger-}/{@code calc-}/{@code api-})
 * {@code @BeforeEach}에서 {@link #ensureTable}과 자기 방송 {@link #clear}를 부른다.
 */
public final class StreamSegmentsFixture {

    /**
     * <b>{@code media/internal/index/ddl.go:32-50}의 사본이다 — 글자 그대로 옮겼다.</b>
     * 정본은 저쪽이고 여기는 검사용 복제다. <b>{@code ddl.go}가 바뀌면 여기도 바꾼다</b> —
     * 안 맞으면 검사는 초록인데 운영 표에서만 깨진다(칸 이름·NOT NULL·기본값 어느 것이든).
     *
     * <p>{@code local_path}는 nullable이고 PostgreSQL 기본이 NULLS DISTINCT라, 아래
     * {@link #insert}가 그 칸을 비워 둔 채 한 방송에 여러 조각을 넣어도 UNIQUE에 안 걸린다.
     *
     * <p><b>끝의 두 칸({@code playback_pdt} · {@code timing_version})은 저쪽에서 뒤에 더한 칸이다</b>
     * ({@code ddl.go}의 6-1 (a) 블록 · PDT 판 2 칸 블록 — 둘 다 {@code ALTER TABLE ... ADD COLUMN}).
     * 채팅 판별이 읽는 칸만 옮겼다(계약 세그먼트인덱스 6-6-9). 나머지 {@code timing_*} 칸 · CHECK · 트리거는
     * 생산자 기록이라 이 카드가 읽지 않는다(6-6-10 I33). 부분 색인은 여기서 만들지 않는다 — 색인이 필요한
     * 시험({@code SegmentLedgerIndexTest})이 스스로 만든다.
     */
    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS public.stream_segments (
                stream_id        text        NOT NULL,
                seq              bigint      NOT NULL,
                start_pts_ms     bigint      NOT NULL,
                start_wall_utc   timestamptz NOT NULL,
                duration_ms      int         NOT NULL,
                s3_key           text        NOT NULL,
                local_path       text,
                upload_state     text        NOT NULL DEFAULT 'pending',
                uploaded_at      timestamptz,
                bytes            bigint,
                is_discontinuity boolean     NOT NULL DEFAULT false,
                playback_pdt     timestamptz,
                timing_version   smallint,
                PRIMARY KEY (stream_id, seq)
            );

            CREATE UNIQUE INDEX IF NOT EXISTS stream_segments_local_path_uq
                ON public.stream_segments (stream_id, local_path);
            """;

    private static final String INSERT = """
            INSERT INTO public.stream_segments
                   (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, is_discontinuity,
                    playback_pdt, timing_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private StreamSegmentsFixture() {
    }

    /** 없으면 만든다. 이미 있으면 아무것도 안 한다 — 검사마다 불러도 싸다. */
    public static void ensureTable(JdbcTemplate jdbc) {
        jdbc.execute(DDL);
    }

    /**
     * 그 방송의 행만 지운다. <b>표를 통째로 비우지 않는다</b> — 같은 컨테이너에서
     * 다른 검사 클래스의 행이 같이 살아 있다.
     */
    public static void clear(JdbcTemplate jdbc, String streamId) {
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ?", streamId);
    }

    /**
     * 「표가 아예 없는 프로세스」를 만든다. 부르는 검사는 <b>반드시</b> 끝나고
     * {@link #ensureTable}로 되살린다 — 안 그러면 뒤에 도는 검사가 전부 깨진다.
     */
    public static void dropTable(JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE IF EXISTS public.stream_segments");
    }

    /**
     * 조각 한 개. {@code s3_key}는 NOT NULL이라 자리만 채운다 — 이 카드는 그 칸도
     * {@code upload_state}도 읽지 않는다.
     */
    public static void insert(JdbcTemplate jdbc, String streamId, long seq, long startPtsMs,
                              Instant startWallUtc, int durationMs, boolean discontinuity) {
        insert(jdbc, streamId, seq, startPtsMs, startWallUtc, null, null, durationMs, discontinuity);
    }

    /**
     * 판 2 조각 한 개. 판 2 행은 {@code playback_pdt}가 늘 있다(생산자 CHECK {@code stream_segments_timing_v2_chk}).
     * 파일명 시각({@code start_wall_utc})은 파일 머리라 PDT보다 앞설 수 있다 — 그 차가 판 2 보정의 이유다.
     */
    public static void insertV2(JdbcTemplate jdbc, String streamId, long seq, long startPtsMs,
                                Instant startWallUtc, Instant playbackPdt, int durationMs, boolean discontinuity) {
        insert(jdbc, streamId, seq, startPtsMs, startWallUtc, playbackPdt, 2, durationMs, discontinuity);
    }

    /** 칸을 모두 주는 꼴. 판 NULL 행에 {@code playback_pdt}를 채워 「옛 행의 PDT는 안 읽는다」 를 잴 때 쓴다. */
    public static void insert(JdbcTemplate jdbc, String streamId, long seq, long startPtsMs, Instant startWallUtc,
                              Instant playbackPdt, Integer timingVersion, int durationMs, boolean discontinuity) {
        jdbc.update(INSERT, streamId, seq, startPtsMs, Timestamp.from(startWallUtc),
                durationMs, "test/seg_" + seq, discontinuity,
                playbackPdt == null ? null : Timestamp.from(playbackPdt), timingVersion);
    }
}
