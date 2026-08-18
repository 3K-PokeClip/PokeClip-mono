package com.pokeclip.auth;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    SchemaMigrationTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 자기_자신_초대는_DB가_막는다가 심는 users 행을 이 클래스가 직접 거둔다.
     * DelegationTestSupport의 정리로는 안 덮인다 — 이 클래스는 그걸 상속하지 않는다.
     *
     * <p>남겨 두면 <b>실패가 엉뚱한 곳에서 터진다.</b> CHECK 제약이 무너져 자식 행까지
     * 남는 상태를 만들어 보니 전체 267건 중 66건이 죽었고, 죽은 클래스는
     * SecretLeakTest·AuthControllerTest·Chzzk* 등 이 표와 무관한 것들뿐이라
     * 범인을 하나도 가리키지 않았다(transaction-auditor 라운드 1 · 직접 재현).
     */
    @AfterEach
    void 심은_행을_거둔다() {
        jdbc.update("DELETE FROM editor_invitations WHERE streamer_id IN "
                + "(SELECT id FROM users WHERE google_sub = 'sub-self-invite-check')");
        jdbc.update("DELETE FROM users WHERE google_sub = 'sub-self-invite-check'");
    }

    @Test
    void Flyway가_users와_refresh_tokens_테이블을_만든다() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("users", "refresh_tokens");
    }

    @Test
    void Flyway가_스트림키와_페어링_테이블을_만든다() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "secrets", "stream_keys", "pairing_codes", "pairing_exchange_attempts");
    }

    /**
     * "계정당 키 하나"를 애플리케이션이 아니라 DB가 막는다. 이 인덱스가 사라지면
     * 동시 발급 테스트만으로는 못 잡는다 — 경합이 안 나면 통과하기 때문이다.
     * 부분 인덱스(WHERE revoked_at IS NULL)라는 것까지 못박는다. 조건이 빠지면
     * 재발급이 두 번째부터 유니크 위반으로 실패한다.
     */
    @Test
    void 살아있는_스트림키에_계정당_하나_제약이_걸려_있다() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_stream_keys_alive_user'",
                String.class);

        assertThat(definition)
                .contains("UNIQUE")
                .contains("user_id")
                .contains("revoked_at IS NULL");
    }

    /** 코드 원문·IP 원문을 담을 컬럼이 아예 없어야 한다. */
    @Test
    void 페어링_표에는_원문_컬럼이_없다() {
        var pairingColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'pairing_codes'",
                String.class);
        var attemptColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'pairing_exchange_attempts'",
                String.class);

        assertThat(pairingColumns).contains("code_hash").doesNotContain("code");
        assertThat(attemptColumns).contains("client_ip_hash").doesNotContain("client_ip");
    }

    @Test
    void Flyway_이력_테이블_이름이_모듈별로_분리돼_있다() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("flyway_schema_history_auth");
        assertThat(tables).doesNotContain("flyway_schema_history");
    }

    @Test
    void google_sub에_UNIQUE_제약이_걸려_있다() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'users'
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name = 'google_sub'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void Flyway가_치지직_연동_표를_만든다() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("chzzk_channel_links");
    }

    /** 다른 계정 중복은 앱이 아니라 DB가 막는다. 부분 인덱스라는 것까지 못박는다. */
    @Test
    void 살아있는_연동에_채널당_하나_계정당_하나_제약이_걸려_있다() {
        String byChannel = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_chzzk_links_alive_channel'",
                String.class);
        String byUser = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_chzzk_links_alive_user'",
                String.class);

        assertThat(byChannel).contains("UNIQUE").contains("channel_id").contains("revoked_at IS NULL");
        assertThat(byUser).contains("UNIQUE").contains("user_id").contains("revoked_at IS NULL");
    }

    /** 토큰 원문·refresh 만료 추정 컬럼이 아예 없어야 한다. */
    @Test
    void 치지직_연동_표에는_토큰_원문_컬럼이_없다() {
        var columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'chzzk_channel_links'",
                String.class);

        assertThat(columns).contains("access_token_ref", "refresh_token_ref", "scope", "last_refreshed_at")
                .doesNotContain("access_token", "refresh_token", "refresh_expires_at");
    }

    /**
     * GET 상태·resolve NOT_LINKED는 닫힌 행을 포함한 "최신 행"(user_id, created_at DESC)을 본다 —
     * 살아있는 행만 거는 부분 인덱스로는 못 탄다. 이 인덱스가 사라지면 계정당 행이 쌓일수록 그 둘이 순차 스캔이 된다.
     */
    @Test
    void 치지직_연동_표에_회원별_최신_행_인덱스가_있다() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_chzzk_links_user_created'", String.class);
        assertThat(definition).contains("user_id").contains("created_at").doesNotContain("WHERE");
    }

    @Test
    void Flyway가_초대와_위임_테이블을_만든다() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("editor_invitations", "editor_delegations");
    }

    /**
     * 살아있는 초대는 한 쌍당 하나. 부분 인덱스(WHERE status='PENDING')라는 것까지 못박는다 —
     * 조건이 빠지면 거절·취소된 이력이 남아 있는 상대에게 재초대가 유니크 위반으로 실패한다.
     */
    @Test
    void 살아있는_초대에_한_쌍당_하나_제약이_걸려_있다() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_invitations_pending_pair'",
                String.class);

        assertThat(definition)
                .contains("UNIQUE")
                .contains("streamer_id")
                .contains("invitee_id")
                .contains("PENDING");
    }

    /** 해제 후 재초대·재수락하면 새 행이 쌓여야 하므로 살아있는 것만 하나로 묶는다. */
    @Test
    void 살아있는_위임에_한_쌍당_하나_제약이_걸려_있다() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_delegations_alive_pair'",
                String.class);

        assertThat(definition)
                .contains("UNIQUE")
                .contains("streamer_id")
                .contains("editor_id")
                .contains("revoked_at IS NULL");
    }

    /** 이메일로 계정을 정확히 하나 찾는다는 전제가 DB에 박혀 있어야 한다. */
    @Test
    void users_email에_유일_제약이_걸려_있다() {
        var definitions = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'users'", String.class);

        assertThat(definitions).anyMatch(d -> d.contains("UNIQUE") && d.contains("email"));
    }

    /**
     * 자기 자신 초대는 앱에서도 막지만 DB가 최후 방어선이다.
     *
     * <p>행을 여기서 직접 심는다. {@code SELECT ... FROM users LIMIT 1}로 쓰면 users가 비었을 때
     * INSERT가 0행으로 조용히 끝나 <b>예외가 안 나고 테스트가 거짓 실패한다</b> — 이 클래스는
     * 다른 테스트가 계정을 만들어 뒀는지에 기대면 안 된다(실행 순서는 보장되지 않는다).
     */
    @Test
    void 자기_자신_초대는_DB가_막는다() {
        Long userId = jdbc.queryForObject(
                "INSERT INTO users (google_sub, email, name, created_at, updated_at) "
                        + "VALUES ('sub-self-invite-check', 'self-invite-check@example.com', "
                        + "'자기초대검증', now(), now()) RETURNING id",
                Long.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO editor_invitations "
                        + "(streamer_id, invitee_id, status, expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, 'PENDING', now() + interval '7 days', now(), now())",
                userId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
