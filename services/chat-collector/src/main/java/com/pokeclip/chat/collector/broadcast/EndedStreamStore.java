package com.pokeclip.chat.collector.broadcast;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 끝난 방송 메모를 읽고 쓰고 치운다. 이 서버의 DB 접근 방식은 JPA가 아니라
 * {@link JdbcTemplate}이다({@code persist/ChatPersister}와 같은 모양).
 */
@Component
public class EndedStreamStore {

    /**
     * <b>{@code GREATEST}를 쓰지 않는다.</b> 아래 {@code WHERE}가 이미 「더 큰 값일 때만」을
     * 강제하므로 {@code GREATEST}가 고를 상황이 없다 — 죽은 코드다(계획 검증 S2 실측).
     *
     * <p><b>충돌 시 {@code created_at}을 갱신하지 않는다. 의도다.</b> 같은 방송에 ENDED가
     * 여러 번 와도(재전송·순서 뒤집힘) 메모의 수명은 <b>첫</b> ENDED로부터 24시간이다.
     * 이 칸의 뜻이 「메모를 남긴 시각」이지 「마지막으로 본 시각」이 아니기 때문이다.
     * 버그가 아니므로 다시 조사하지 마라(계획 검증 S3).
     */
    private static final String UPSERT = """
            INSERT INTO chat_ended_streams (stream_id, last_sequence, ended_at, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (stream_id) DO UPDATE
               SET last_sequence = EXCLUDED.last_sequence,
                   ended_at      = EXCLUDED.ended_at
             WHERE EXCLUDED.last_sequence > chat_ended_streams.last_sequence
            """;

    private static final String SELECT = """
            SELECT stream_id, last_sequence, ended_at, created_at
              FROM chat_ended_streams
             WHERE stream_id = ?
            """;

    private final JdbcTemplate jdbc;

    public EndedStreamStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 이 호출이 표를 바꿨는가. 낮거나 <b>같은</b> 번호가 늦게 오면 false다.
     *
     * <p><b>🔴 이 반환값으로 「처리했다/안 했다」를 가르지 마라</b>(계획 검증 S4).
     * SQS는 at-least-once라 <b>같은 ENDED 편지가 두 번 오는 것이 정상</b>이고, 그때 이 값은
     * false다. 편지 판정에서 false를 실패로 읽으면 정상 중복이 재시도 대상으로 분류된다.
     * 실측: 새 줄 1 · 더 높은 번호 1 · 더 낮은 번호 0 · <b>같은 번호 0</b>.
     */
    public boolean remember(String streamId, long sequence, Instant endedAt) {
        return jdbc.update(UPSERT, streamId, sequence,
                Timestamp.from(endedAt), Timestamp.from(Instant.now())) > 0;
    }

    /** stream_id가 PK라 결과는 0행 아니면 1행이다. */
    public Optional<EndedStream> find(String streamId) {
        List<EndedStream> found = jdbc.query(SELECT, EndedStreamStore::toMemo, streamId);
        return found.stream().findFirst();
    }

    public int sweepOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM chat_ended_streams WHERE created_at < ?",
                Timestamp.from(cutoff));
    }

    private static EndedStream toMemo(ResultSet row, int rowNum) throws SQLException {
        return new EndedStream(
                row.getString("stream_id"),
                row.getLong("last_sequence"),
                row.getTimestamp("ended_at").toInstant(),
                row.getTimestamp("created_at").toInstant());
    }
}
