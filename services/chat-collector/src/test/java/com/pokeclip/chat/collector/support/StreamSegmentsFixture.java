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
     */
    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS stream_segments (
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
                PRIMARY KEY (stream_id, seq)
            );

            CREATE UNIQUE INDEX IF NOT EXISTS stream_segments_local_path_uq
                ON stream_segments (stream_id, local_path);
            """;

    private static final String INSERT = """
            INSERT INTO stream_segments
                   (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, is_discontinuity)
            VALUES (?, ?, ?, ?, ?, ?, ?)
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
        jdbc.update("DELETE FROM stream_segments WHERE stream_id = ?", streamId);
    }

    /**
     * 「표가 아예 없는 프로세스」를 만든다. 부르는 검사는 <b>반드시</b> 끝나고
     * {@link #ensureTable}로 되살린다 — 안 그러면 뒤에 도는 검사가 전부 깨진다.
     */
    public static void dropTable(JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE IF EXISTS stream_segments");
    }

    /**
     * 조각 한 개. {@code s3_key}는 NOT NULL이라 자리만 채운다 — 이 카드는 그 칸도
     * {@code upload_state}도 읽지 않는다.
     */
    public static void insert(JdbcTemplate jdbc, String streamId, long seq, long startPtsMs,
                              Instant startWallUtc, int durationMs, boolean discontinuity) {
        jdbc.update(INSERT, streamId, seq, startPtsMs, Timestamp.from(startWallUtc),
                durationMs, "test/seg_" + seq, discontinuity);
    }
}
