package com.pokeclip.auth.audiotrack;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 회원당 0~6행. 이름을 안 적은 트랙은 행이 없다.
 *
 * <p>JPA 엔티티 대신 SQL 둘이다 — 복합키 여섯 줄을 갈아 끼우는 일이라 「지우고 넣는다」가 가장 짧고,
 * 읽는 쪽이 6칸 배열 하나를 원한다({@code RetentionCleaner}·{@code PairingAttemptRecorder}가 같은 이유로 JDBC다).
 */
@Repository
public class AudioTrackLabelRepository {

    public static final int TRACK_COUNT = 6;

    private final JdbcTemplate jdbc;

    AudioTrackLabelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 길이 6 고정. 이름이 없는 칸은 {@code null}. */
    public List<String> find(long userId) {
        String[] labels = new String[TRACK_COUNT];
        jdbc.query("SELECT track_no, label FROM audio_track_labels WHERE user_id = ?",
                rs -> {
                    labels[rs.getInt("track_no") - 1] = rs.getString("label");
                },
                userId);
        return Arrays.asList(labels);
    }

    /**
     * 여섯 칸을 통째로 갈아 끼운다. 같은 회원의 두 요청이 겹치면 <b>뒤에 커밋한 쪽이 이긴다</b> —
     * 설정 화면 하나에서 저장을 누르는 일이라 그것이 맞는 답이다. 부르는 쪽이 트랜잭션을 연다.
     *
     * <p>🔴 <b>먼저 회원 단위 어드바이저리 락을 잡는다.</b> 「지우고 넣는다」만으로는 겹침이 직렬화되지 않는다 —
     * 첫 저장 둘이 겹치면 DELETE가 둘 다 0행이라 서로를 안 막고, 같은 트랙을 넣으면 한쪽이 PK 위반(500),
     * 다른 트랙을 넣으면 <b>둘의 칸이 섞인 채</b> 커밋된다(PR #184 codex). 락은 잡을 행이 아직 없을 때
     * 쓰는 도구고({@code PairingAttemptRecorder}가 선례), 트랜잭션이 끝나면 저절로 풀린다.
     * 회원 행 락({@code findByIdForUpdate})을 안 쓰는 이유는 그 락을 토큰 회전·키 재발급·연동·탈퇴가 공유해
     * 잠금 순서가 생기기 때문이다 — 이 키는 이 표만 쓴다.
     *
     * @param labels 길이 6. {@code null}이면 그 칸을 지운다
     */
    public void replace(long userId, List<String> labels, Instant now) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, "audio_track_labels:" + userId);
        jdbc.update("DELETE FROM audio_track_labels WHERE user_id = ?", userId);
        for (int i = 0; i < TRACK_COUNT; i++) {
            String label = labels.get(i);
            if (label != null) {
                jdbc.update("INSERT INTO audio_track_labels (user_id, track_no, label, updated_at) VALUES (?, ?, ?, ?)",
                        userId, i + 1, label, Timestamp.from(now));
            }
        }
    }

    /**
     * 탈퇴 회수용. 회원 행은 익명화만 되고 남으므로 표의 {@code ON DELETE CASCADE}는 영영 안 돈다 —
     * 여기서 지워야 탈퇴한 사람이 적은 글자가 안 남는다. 부르는 쪽(탈퇴 트랜잭션)이 회원 행 락을 쥔 채 부른다.
     */
    public int deleteAllOfUser(long userId) {
        return jdbc.update("DELETE FROM audio_track_labels WHERE user_id = ?", userId);
    }
}
