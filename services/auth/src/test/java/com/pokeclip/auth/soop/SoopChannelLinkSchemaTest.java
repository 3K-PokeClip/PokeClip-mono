package com.pokeclip.auth.soop;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V116 표의 제약과 저장소 쿼리를 실제 DB에서 잰다.
 *
 * <p>닫기는 엔티티를 고쳐서 하지 않고 {@link SoopChannelLinkRepository#closeAndPurgeAlive} 한 문장으로만 한다
 * (계획 검증 C1: IDENTITY 키라 재연동의 INSERT가 dirty 행 flush보다 먼저 나가 유니크에 걸리고, 탈퇴의
 * clearAutomatically 쿼리는 flush 전 변경을 지운다). 그래서 닫힌 행의 상태 파생도 여기서 다시 읽어 잰다.
 */
class SoopChannelLinkSchemaTest extends IntegrationTestSupport {

    private static final String SUB_PREFIX = "sub-soop-schema-";
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private final JdbcTemplate jdbc;
    private final SoopChannelLinkRepository repository;
    private final TransactionTemplate tx;

    SoopChannelLinkSchemaTest(JdbcTemplate jdbc, SoopChannelLinkRepository repository,
                              PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** FK: soop_channel_links는 users의 자식이다. 남기면 다른 클래스의 users 정리가 터진다. */
    @AfterEach
    void 심은_행을_거둔다() {
        jdbc.update("DELETE FROM soop_channel_links WHERE user_id IN "
                + "(SELECT id FROM users WHERE google_sub LIKE ?)", SUB_PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE google_sub LIKE ?", SUB_PREFIX + "%");
    }

    private Long 회원을_심는다() {
        String id = UUID.randomUUID().toString();
        return jdbc.queryForObject(
                "INSERT INTO users (google_sub, email, name, created_at, updated_at) "
                        + "VALUES (?, ?, 'SOOP표검증', now(), now()) RETURNING id",
                Long.class, SUB_PREFIX + id, id + "@example.com");
    }

    /** JDBC로 직접 넣는다. revokedAt이 null이면 살아 있는 행. */
    private void 행을_넣는다(Long userId, String soopUserId, String nickname, Instant revokedAt, String reason) {
        jdbc.update("INSERT INTO soop_channel_links (user_id, soop_user_id, nickname, access_token_ref, "
                        + "refresh_token_ref, access_expires_at, last_refreshed_at, revoked_at, revoke_reason, created_at) "
                        + "VALUES (?, ?, ?, 'soop:a', 'soop:r', ?, ?, ?, ?, ?)",
                userId, soopUserId, nickname, Timestamp.from(NOW.plus(Duration.ofHours(8))), Timestamp.from(NOW),
                revokedAt == null ? null : Timestamp.from(revokedAt), reason, Timestamp.from(NOW));
    }

    private SoopChannelLink 저장한다(Long userId, String soopUserId, Instant expiresAt, Instant lastRefreshedAt) {
        SoopChannelLink link = SoopChannelLink.of(userId, soopUserId, "닉네임", null, "soop:a-" + userId,
                "soop:r-" + userId, expiresAt, lastRefreshedAt);
        return repository.saveAndFlush(link);
    }

    // --- 제약 (JdbcTemplate 직접 INSERT) ---

    @Test
    void 살아있는_행에_SOOP_아이디가_없으면_CHECK가_막는다() {
        Long userId = 회원을_심는다();

        assertThatThrownBy(() -> 행을_넣는다(userId, null, "닉네임", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_soop_links_alive_has_identity");
    }

    @Test
    void 살아있는_행에_닉네임이_없으면_CHECK가_막는다() {
        Long userId = 회원을_심는다();

        assertThatThrownBy(() -> 행을_넣는다(userId, "soop-a", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_soop_links_alive_has_identity");
    }

    @Test
    void 같은_SOOP_아이디로_살아있는_행_둘은_유니크가_막는다() {
        Long a = 회원을_심는다();
        Long b = 회원을_심는다();
        행을_넣는다(a, "soop-same", "닉네임", null, null);

        assertThatThrownBy(() -> 행을_넣는다(b, "soop-same", "닉네임", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_soop_links_alive_soop_user");
    }

    @Test
    void 같은_회원의_살아있는_행_둘은_유니크가_막는다() {
        Long userId = 회원을_심는다();
        행을_넣는다(userId, "soop-one", "닉네임", null, null);

        assertThatThrownBy(() -> 행을_넣는다(userId, "soop-two", "닉네임", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_soop_links_alive_user");
    }

    /** 닫힌 행은 두 칸을 비울 수 있고, 닫힌 행끼리·닫힌 행과 산 행은 유니크에 안 걸린다(부분 인덱스). */
    @Test
    void 닫힌_행은_아이디_닉네임이_비어도_되고_유니크에_안_걸린다() {
        Long userId = 회원을_심는다();
        행을_넣는다(userId, null, null, NOW, "USER_UNLINKED");
        행을_넣는다(userId, null, null, NOW, "WITHDRAWN");
        행을_넣는다(userId, "soop-alive", "닉네임", null, null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM soop_channel_links WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(3);
    }

    // --- 닫기: 한 문장에 닫기 + 두 칸 NULL, 살아 있는 행에만 ---

    @Test
    void closeAndPurgeAlive는_한_문장에_닫고_아이디_닉네임을_비운다() {
        Long userId = 회원을_심는다();
        저장한다(userId, "soop-close", NOW.plus(Duration.ofHours(8)), NOW);
        Instant closedAt = NOW.plus(Duration.ofMinutes(1));

        Integer rows = tx.execute(s -> repository.closeAndPurgeAlive(userId, closedAt, RevokeReason.USER_UNLINKED));

        assertThat(rows).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT soop_user_id, nickname, revoked_at, revoke_reason, access_token_ref, refresh_token_ref "
                        + "FROM soop_channel_links WHERE user_id = ?", userId);
        assertThat(row.get("soop_user_id")).isNull();
        assertThat(row.get("nickname")).isNull();
        assertThat(((Timestamp) row.get("revoked_at")).toInstant()).isEqualTo(closedAt);
        assertThat(row.get("revoke_reason")).isEqualTo("USER_UNLINKED");
        // ref 둘은 남는다: 커밋 뒤 정리가 secrets를 지울 때 쓸 수 있게 호출자가 UPDATE 전에 읽어 두지만, 행에서도 안 지운다
        assertThat(row.get("access_token_ref")).isEqualTo("soop:a-" + userId);
        assertThat(row.get("refresh_token_ref")).isEqualTo("soop:r-" + userId);
    }

    /** 이미 닫힌 옛 행의 닫은 시각·사유를 덮어쓰면 「언제 왜 끊겼나」가 거짓이 된다. */
    @Test
    void closeAndPurgeAlive는_이미_닫힌_행을_건드리지_않는다() {
        Long userId = 회원을_심는다();
        Instant oldClose = NOW.minus(Duration.ofDays(1));
        행을_넣는다(userId, null, null, oldClose, "REFRESH_REJECTED");
        저장한다(userId, "soop-alive", NOW.plus(Duration.ofHours(8)), NOW);

        Integer rows = tx.execute(s -> repository.closeAndPurgeAlive(userId, NOW, RevokeReason.WITHDRAWN));

        assertThat(rows).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM soop_channel_links "
                        + "WHERE user_id = ? AND revoke_reason = 'REFRESH_REJECTED' AND revoked_at = ?",
                Integer.class, userId, Timestamp.from(oldClose))).isEqualTo(1);
    }

    @Test
    void closeAndPurgeAlive는_남의_행을_건드리지_않는다() {
        Long me = 회원을_심는다();
        Long other = 회원을_심는다();
        저장한다(me, "soop-me", NOW.plus(Duration.ofHours(8)), NOW);
        저장한다(other, "soop-other", NOW.plus(Duration.ofHours(8)), NOW);

        tx.execute(s -> repository.closeAndPurgeAlive(me, NOW, RevokeReason.USER_UNLINKED));

        assertThat(jdbc.queryForObject("SELECT soop_user_id FROM soop_channel_links WHERE user_id = ?",
                String.class, other)).isEqualTo("soop-other");
    }

    @Test
    void 닫을_행이_없으면_0을_돌려준다() {
        Long userId = 회원을_심는다();

        Integer rows = tx.execute(s -> repository.closeAndPurgeAlive(userId, NOW, RevokeReason.USER_UNLINKED));

        assertThat(rows).isZero();
    }

    /**
     * 재연동이 하는 순서 그대로: 같은 트랜잭션에서 닫고 바로 새 행을 넣는다. 닫기가 flush되지 않은 채 INSERT가
     * 먼저 나가면 같은 SOOP 아이디·같은 회원 유니크에 걸린다(C1).
     */
    @Test
    void 같은_트랜잭션에서_닫고_같은_아이디로_새_행을_넣을_수_있다() {
        Long userId = 회원을_심는다();
        저장한다(userId, "soop-relink", NOW.plus(Duration.ofHours(8)), NOW);

        tx.executeWithoutResult(s -> {
            repository.findByUserIdAndRevokedAtIsNull(userId).orElseThrow(); // 영속성 컨텍스트에 옛 행을 올려 둔다
            repository.closeAndPurgeAlive(userId, NOW, RevokeReason.USER_UNLINKED);
            저장한다(userId, "soop-relink", NOW.plus(Duration.ofHours(16)), NOW.plus(Duration.ofSeconds(1)));
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM soop_channel_links WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM soop_channel_links WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(2);
    }

    /**
     * 같은 트랜잭션에서 닫은 뒤 다시 읽으면 닫힌 모습이어야 한다. 1차 캐시가 옛 엔티티를 쥐고 있으면
     * 「살아 있고 아이디도 있는」 행이 돌아온다(clearAutomatically가 막는 자리).
     */
    @Test
    void 같은_트랜잭션에서_닫은_뒤_다시_읽으면_닫힌_행이다() {
        Long userId = 회원을_심는다();
        저장한다(userId, "soop-cache", NOW.plus(Duration.ofHours(8)), NOW);

        SoopChannelLink reread = tx.execute(s -> {
            repository.findByUserIdAndRevokedAtIsNull(userId).orElseThrow();
            repository.closeAndPurgeAlive(userId, NOW, RevokeReason.USER_UNLINKED);
            return repository.findFirstByUserIdOrderByCreatedAtDesc(userId).orElseThrow();
        });

        assertThat(reread.getRevokedAt()).isNotNull();
        assertThat(reread.getSoopUserId()).isNull();
        assertThat(reread.status(NOW)).isEqualTo(LinkStatus.UNLINKED);
    }

    /**
     * 닫기 전에 같은 트랜잭션에서 고친 값이 닫기의 clear에 지워지지 않는다. 결과만 잰다: {@code flushAutomatically}를
     * 꺼도 초록이다(Hibernate의 AUTO flush가 같은 표를 건드리는 JPQL UPDATE 앞에서 이미 flush한다, 주입으로 확인).
     * 그 설정은 치지직과 같은 겹 방어로 둔다.
     */
    @Test
    void 닫기_전에_고친_값이_닫기에_지워지지_않는다() {
        Long userId = 회원을_심는다();
        저장한다(userId, "soop-flush", NOW.plus(Duration.ofHours(8)), NOW);
        Instant refreshedExpiry = NOW.plus(Duration.ofHours(30));

        tx.executeWithoutResult(s -> {
            repository.findByUserIdAndRevokedAtIsNull(userId).orElseThrow()
                    .refreshed(refreshedExpiry, "chat", NOW.plus(Duration.ofMinutes(1)));
            repository.closeAndPurgeAlive(userId, NOW.plus(Duration.ofMinutes(2)), RevokeReason.REFRESH_REJECTED);
        });

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT access_expires_at, scope, revoked_at FROM soop_channel_links WHERE user_id = ?", userId);
        assertThat(((Timestamp) row.get("access_expires_at")).toInstant()).isEqualTo(refreshedExpiry);
        assertThat(row.get("scope")).isEqualTo("chat");
        assertThat(row.get("revoked_at")).isNotNull();
    }

    // --- 닫힌 행의 상태 파생 (DB에서 다시 읽는다) ---

    @Test
    void 갱신_거절로_닫히면_BROKEN() {
        assertThat(닫고_최신_행을_읽는다(RevokeReason.REFRESH_REJECTED).status(NOW)).isEqualTo(LinkStatus.BROKEN);
    }

    @Test
    void 사용자가_해제하면_UNLINKED() {
        assertThat(닫고_최신_행을_읽는다(RevokeReason.USER_UNLINKED).status(NOW)).isEqualTo(LinkStatus.UNLINKED);
    }

    @Test
    void 탈퇴로_닫히면_UNLINKED() {
        assertThat(닫고_최신_행을_읽는다(RevokeReason.WITHDRAWN).status(NOW)).isEqualTo(LinkStatus.UNLINKED);
    }

    private SoopChannelLink 닫고_최신_행을_읽는다(RevokeReason reason) {
        Long userId = 회원을_심는다();
        저장한다(userId, "soop-" + reason, NOW.plus(Duration.ofHours(8)), NOW);
        tx.execute(s -> repository.closeAndPurgeAlive(userId, NOW, reason));
        SoopChannelLink link = repository.findFirstByUserIdOrderByCreatedAtDesc(userId).orElseThrow();
        assertThat(link.getRevokeReason()).isEqualTo(reason);
        assertThat(link.getSoopUserId()).isNull();
        assertThat(link.getNickname()).isNull();
        return link;
    }

    // --- 조회 ---

    @Test
    void 살아있는_행은_회원과_SOOP_아이디로_찾고_닫힌_행은_안_찾힌다() {
        Long userId = 회원을_심는다();
        저장한다(userId, "soop-find", NOW.plus(Duration.ofHours(8)), NOW);

        assertThat(repository.findByUserIdAndRevokedAtIsNull(userId)).isPresent();
        assertThat(repository.findBySoopUserIdAndRevokedAtIsNull("soop-find")).get()
                .extracting(SoopChannelLink::getUserId).isEqualTo(userId);

        tx.execute(s -> repository.closeAndPurgeAlive(userId, NOW, RevokeReason.USER_UNLINKED));

        assertThat(repository.findByUserIdAndRevokedAtIsNull(userId)).isEmpty();
        assertThat(repository.findBySoopUserIdAndRevokedAtIsNull("soop-find")).isEmpty();
    }

    @Test
    void 최신_행은_닫힌_행도_포함해_created_at이_가장_늦은_것() {
        Long userId = 회원을_심는다();
        저장한다(userId, "soop-old", NOW.plus(Duration.ofHours(8)), NOW);
        tx.execute(s -> repository.closeAndPurgeAlive(userId, NOW, RevokeReason.USER_UNLINKED));
        저장한다(userId, "soop-new", NOW.plus(Duration.ofHours(8)), NOW.plus(Duration.ofSeconds(1)));

        assertThat(repository.findFirstByUserIdOrderByCreatedAtDesc(userId)).get()
                .extracting(SoopChannelLink::getSoopUserId).isEqualTo("soop-new");
    }

    /**
     * 후보 = 살아 있는 행 중 만료 임박이거나 마지막 확인이 오래된 것. 순서는 마지막 확인이 오래된 것부터라
     * 상한으로 잘려도 오래 안 본 회원부터 본다.
     */
    @Test
    void 갱신_후보는_만료_임박_또는_확인이_오래된_살아있는_행이고_오래된_확인부터() {
        Instant expiringBefore = NOW.plus(Duration.ofHours(1));
        Instant checkedBefore = NOW.minus(Duration.ofHours(24));
        Long expiring = 회원을_심는다();
        Long stale = 회원을_심는다();
        Long fresh = 회원을_심는다();
        Long closed = 회원을_심는다();
        저장한다(expiring, "soop-exp", NOW.plus(Duration.ofMinutes(30)), NOW.minus(Duration.ofHours(2)));
        저장한다(stale, "soop-stale", NOW.plus(Duration.ofHours(7)), NOW.minus(Duration.ofHours(25)));
        저장한다(fresh, "soop-fresh", NOW.plus(Duration.ofHours(7)), NOW.minus(Duration.ofHours(1)));
        저장한다(closed, "soop-closed", NOW.plus(Duration.ofMinutes(10)), NOW.minus(Duration.ofHours(30)));
        tx.execute(s -> repository.closeAndPurgeAlive(closed, NOW, RevokeReason.USER_UNLINKED));

        assertThat(repository.findIdsExpiringOrStale(expiringBefore, checkedBefore, PageRequest.of(0, 10)))
                .containsExactly(stale, expiring);
        assertThat(repository.findIdsExpiringOrStale(expiringBefore, checkedBefore, PageRequest.of(0, 1)))
                .containsExactly(stale);
    }
}
