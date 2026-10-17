package com.pokeclip.chat.collector.broadcast;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 방송 번호 → 물리 키(POK-233). 계약9가 편지의 {@code streamId}를 방송마다 새 회차 번호로 바꾸고, 조각 장부·녹화 경로의 이름은
 * {@code ingestStreamId}로 따로 싣는다. 영상 시점 조회({@code VideoPositionCalculator})가 장부를 물리 키로 찾으려고 여기서 꺼낸다.
 *
 * <p><b>DB 표에 둔다</b>(메모리가 아니다) — 재시작해도 남아야 방송 도중 들어오는 조회가 계속 맞는다. 편지는 SQS에 남아 있다가 반드시
 * 이 판정기를 지나므로 재부착 쪽에서 따로 채우지 않는다.
 */
@Component
public class IngestKeyStore {

    /** 이미 있으면 안 바꾼다. 한 회차의 편지 둘은 같은 경로를 싣고(계약9), 다르면 발행 쪽 결함이라 먼저 적은 키를 지킨다 */
    private static final String INSERT = """
            INSERT INTO chat_ingest_keys (stream_id, ingest_stream_id) VALUES (?, ?)
            ON CONFLICT (stream_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public IngestKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void remember(String streamId, String ingestStreamId) {
        jdbc.update(INSERT, streamId, ingestStreamId);
    }

    /** 장부를 찾는 키. 적힌 줄이 없으면 방송 번호 그대로다(물리 키를 안 실은 옛 편지는 방송 번호가 곧 물리 키다) */
    public String keyOf(String streamId) {
        List<String> found = jdbc.queryForList(
                "SELECT ingest_stream_id FROM chat_ingest_keys WHERE stream_id = ?", String.class, streamId);
        return found.isEmpty() ? streamId : found.get(0);
    }
}
