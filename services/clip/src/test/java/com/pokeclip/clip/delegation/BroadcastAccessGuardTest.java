package com.pokeclip.clip.delegation;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「이 회원이 이 방송을 볼 수 있나」의 유일한 자리. 사람 문 다섯이 나눠 쓴다.
 *
 * <p><b>여기 갈래의 절반은 「무엇을 던지나」가 아니라 「무엇을 <i>먼저</i> 하나」를 잰다</b> —
 * 방송 조회가 auth보다 앞이어야 없는 방송에 auth를 안 두드리고, 그래야 auth의 {@code NONE}
 * 카운터가 안 더러워진다(README auth 절).
 *
 * <p><b>가짜 auth는 Mockito가 아니라 진짜로 듣는 소켓이다</b>({@link IntegrationTestSupport#AUTH}).
 * 판정기를 통째로 가짜로 갈면 「판정이 붙기 전과 정확히 같은 것」을 재게 되고, 여기서 재려는
 * 것 중 하나가 <b>주소 덮어쓰기가 실제로 걸렸는가</b>이기 때문이다. 창구를 안 걸어 두면
 * 초기 응답이 503이라 통과가 아니라 거절을 받는다.
 */
class BroadcastAccessGuardTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** 방송의 스트리머. {@link TestIds#STREAMER}와 같은 값이라 픽스처 규칙이 이어진다. */
    private static final String 스트리머 = TestIds.STREAMER;

    /** 요청자. 스트리머와 <b>다른 값</b>이어야 인자 순서와 「본인 통과로 새는가」가 재어진다. */
    private static final String 요청자 = "3";

    /** 로그에 <b>안 실려야 하는</b> 값. 눈에 띄게 지어 다른 로그와 우연히 안 겹치게 했다. */
    private static final String 숫자가_아닌_스트리머 = "streamer-NOT-A-NUMBER-4e2d";
    private static final String 숫자가_아닌_주체 = "subject-NOT-A-NUMBER-8b6c";

    private final BroadcastAccessGuard guard;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    BroadcastAccessGuardTest(BroadcastAccessGuard guard, BroadcastRepository broadcasts,
                             JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.guard = guard;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        방송을_넣는다("s-1", 스트리머);
    }

    // ── 통과하는 둘 ───────────────────────────────────────────────

    @Test
    void 주인이면_통과한다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");

        assertThatNoException().isThrownBy(() -> guard.requireViewable(스트리머, "s-1"));
    }

    /**
     * <b>{@code OWNER}와 {@code EDITOR}를 가르지 않는다</b>(PRD 결정). 편집자가 카드를 집어
     * 편집하고 숨기는 것이 그 사람의 본업이다.
     */
    @Test
    void 편집자도_통과한다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"EDITOR\"}");

        assertThatNoException().isThrownBy(() -> guard.requireViewable(요청자, "s-1"));
    }

    // ── 거절하는 넷 ───────────────────────────────────────────────

    @Test
    void 남남이면_볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");

        assertThatThrownBy(() -> guard.requireViewable("9", "s-1"))
                .isInstanceOfSatisfying(AccessErrors.NotViewableException.class,
                        e -> assertThat(e.reason()).isEqualTo("relation_none"));
    }

    /**
     * <b>없는 방송과 자격 없음이 같은 예외 타입이다.</b> 갈리면 방송 이름을 넣어 보는 것만으로
     * 실재를 알 수 있다. 갈리는 것은 {@code reason}뿐이고 그것은 로그로만 간다.
     *
     * <p>그리고 <b>auth를 안 부른다</b> — 없는 방송에는 물어볼 스트리머 번호가 없다.
     * 부르면 남의 방송 번호를 훑는 것만으로 auth에 요청이 쌓이고, 그때 나가는 번호는
     * 아무 근거도 없는 값이다.
     */
    @Test
    void 없는_방송도_볼_수_없다_그리고_auth를_안_부른다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");

        assertThatThrownBy(() -> guard.requireViewable(스트리머, "s-없는방송"))
                .isInstanceOfSatisfying(AccessErrors.NotViewableException.class,
                        e -> assertThat(e.reason()).isEqualTo("broadcast_not_found"));

        assertThat(AUTH.callCount())
                .as("창구를 OWNER로 열어 뒀는데도 0이어야 한다 — 통과가 아니라 「안 물었다」를 잰다")
                .isZero();
    }

    /**
     * 404로 접지 않는다. 화면이 「없는 방송」이라고 단정하면 auth가 살아난 뒤에도 편집자는
     * 다시 시도하지 않는다.
     */
    @Test
    void 못_물으면_판정_불가다() {
        AUTH.respondWith(RESOLVE, 500, "");

        assertThatThrownBy(() -> guard.requireViewable(스트리머, "s-1"))
                .isInstanceOf(AccessErrors.AuthUnavailableException.class);

        assertThat(AUTH.callCount())
                .as("정확히 한 번 — 0이면 아예 안 물은 것이고 2 이상이면 재시도다")
                .isEqualTo(1);
    }

    /**
     * 🔴 <b>창구를 안 걸어 둔 시험은 통과가 아니라 거절을 받아야 한다.</b>
     *
     * <p>{@link IntegrationTestSupport#AUTH}는 JVM 전역이라 초기 응답을 <b>503</b>으로 뒀고
     * 두 군데 주석이 그것을 「이 카드에서 가장 중요한 안전장치」라고 적어 뒀다. 그런데
     * <b>그 문장을 지키는 갈래가 하나도 없었다</b>(감사 1라운드) — 기본값을
     * {@code 200 {"relation":"OWNER"}}로 바꿔도 345건이 전부 초록이었다.
     *
     * <p>지금은 안 아프다({@code AUTH}를 쓰는 클래스가 이 하나뿐이고 아홉 갈래가 전부 자기
     * 답을 명시한다). <b>태스크 6·7·8이 기존 시험 수십 건을 이 위에 얹는 순간</b>이 위험하다 —
     * 답을 안 건 시험 하나가 조용히 판정을 통과하고, 그 시험은 「판정이 붙기 전과 정확히
     * 같은 것」을 재게 된다. 스킬 문항 2가 이름 붙인 실패가 그것이다.
     *
     * <p>재는 것은 <b>「거절이 기본」</b>이지 「503이 기본」이 아니다 — 기본을 {@code 200 ""}로
     * 바꿔도 본문을 못 읽어 거절이 되므로 그 변경은 이 갈래를 안 깬다. 그래도 옳다:
     * 지켜야 하는 불변식은 <b>조용히 통과하지 않는 것</b>이다.
     */
    @Test
    void 창구를_안_걸어_두면_통과가_아니라_거절이다() {
        assertThatThrownBy(() -> guard.requireViewable(스트리머, "s-1"))
                .as("기본값이 OWNER류로 바뀌면 답을 안 건 시험이 판정을 통째로 안 재게 된다")
                .isInstanceOf(AccessErrors.AuthUnavailableException.class);

        assertThat(AUTH.callCount())
                .as("0이면 창구까지 가지도 않은 것이라 기본값을 잰 것이 아니다")
                .isPositive();
    }

    // ── 식별자가 숫자가 아닐 때 — 조용한 장애라 로그가 유일한 발견 수단 ────────

    /**
     * 주인이 자기 방송을 못 보는데 화면에는 <b>「없는 방송」</b>이라고 나온다. 응답으로는
     * 영영 구분이 안 되므로 ERROR 로그가 유일한 발견 수단이다.
     */
    @Test
    void 스트리머_번호가_숫자가_아니면_볼_수_없고_ERROR가_남는다() {
        방송을_넣는다("s-깨진", 숫자가_아닌_스트리머);
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");

        try (LogCaptor logs = new LogCaptor()) {
            assertThatThrownBy(() -> guard.requireViewable(스트리머, "s-깨진"))
                    .isInstanceOfSatisfying(AccessErrors.NotViewableException.class,
                            e -> assertThat(e.reason()).isEqualTo("streamer_id_not_numeric"));

            assertThat(logs.levelOf("clip.access.identity_not_numeric")).isEqualTo(Level.ERROR);
            assertThat(logs.messages())
                    .anyMatch(m -> m.contains("reason=streamer_id_not_numeric")
                            && m.contains("streamId=s-깨진"));
            assertThat(logs.messages()).as("값 자체는 안 찍는다 — 큐로 받은 값이라 개행이 섞일 수 있다")
                    .noneMatch(m -> m.contains(숫자가_아닌_스트리머));
        }

        assertThat(AUTH.callCount()).as("바꿀 수 없는 번호로 auth를 두드리면 안 된다").isZero();
    }

    /**
     * 우리가 발급·검증한 토큰이라 드문 갈래다. <b>갈래가 죽었는지가 아니라 살아 있는지를</b>
     * 그물이 지킨다 — 코드에 갈래가 있으면 잰다.
     */
    @Test
    void 주체가_숫자가_아니면_볼_수_없고_ERROR가_남는다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");

        try (LogCaptor logs = new LogCaptor()) {
            assertThatThrownBy(() -> guard.requireViewable(숫자가_아닌_주체, "s-1"))
                    .isInstanceOfSatisfying(AccessErrors.NotViewableException.class,
                            e -> assertThat(e.reason()).isEqualTo("subject_not_numeric"));

            assertThat(logs.levelOf("clip.access.identity_not_numeric")).isEqualTo(Level.ERROR);
            assertThat(logs.messages())
                    .anyMatch(m -> m.contains("reason=subject_not_numeric") && m.contains("streamId=s-1"));
            assertThat(logs.messages()).as("값 자체는 안 찍는다")
                    .noneMatch(m -> m.contains(숫자가_아닌_주체));
        }

        assertThat(AUTH.callCount()).isZero();
    }

    // ── 계약: 인자 순서 ───────────────────────────────────────────

    /**
     * 🔴 뒤집으면 <b>스트리머가 자기 방송을 못 보고</b> 증상은 「권한 없음」으로 나온다 —
     * 두 번호가 같은 타입({@code long})이라 컴파일러가 안 잡는다.
     *
     * <p>요청자와 스트리머를 <b>다른 값</b>으로 두는 것이 이 갈래의 전부다. 같은 값이면
     * 뒤집어도 같은 본문이 나가 아무것도 안 잰다.
     */
    @Test
    void 인자_순서가_요청자_스트리머다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");

        guard.requireViewable(요청자, "s-1");

        assertThat(AUTH.callCount()).isEqualTo(1);
        assertThat(AUTH.lastBody())
                .isEqualTo("{\"userId\":" + 요청자 + ",\"streamerUserId\":" + 스트리머 + "}");
    }

    // ── 계약: 엔티티를 안 올린다 ──────────────────────────────────

    /**
     * 🔴 <b>판정기는 스트리머 번호만 스칼라로 뽑는다 — 엔티티를 올리면 안 된다.</b>
     *
     * <p>부르는 쪽({@code JumpCardStreamController.open})은 같은 트랜잭션 안에서 방송 상태를
     * <b>나중에 다시</b> 읽어 「그 사이에 끝났는가」를 본다. 판정기가 앞에서 엔티티를 올려 두면
     * 그 재조회가 JPQL을 던지고도 <b>1차 캐시의 낡은 인스턴스</b>를 돌려준다(JPA가 DB에서 읽은
     * 값을 버린다). 계획 검증이 재현했다 — {@code StreamOpenWindowTest}가 빨간불이었다.
     *
     * <p>여기서는 그 회귀를 <b>판정기만 놓고</b> 잰다. 트랜잭션을 열어 판정을 부르고, 같은
     * 연결로 값을 바꾼 뒤(네이티브 SQL은 1차 캐시를 안 지난다) JPQL로 다시 읽는다.
     * 판정기가 엔티티를 올렸다면 여기서 <b>바꾸기 전 값</b>이 나온다.
     *
     * <p>{@code StreamOpenWindowTest}에 기대지 않는 이유 — 그 시험은 태스크 7이 판정을 붙인
     * 뒤에야 이 경로를 지난다. 여기 그물이 없으면 <b>다섯 태스크 뒤에</b> 발견된다.
     */
    @Test
    void 판정_뒤에도_방송_엔티티가_영속성_컨텍스트에_안_올라온다() {
        방송을_넣는다("s-pc", 스트리머);
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");

        transactions.executeWithoutResult(status -> {
            guard.requireViewable(스트리머, "s-pc");

            jdbc.update("UPDATE broadcasts SET streamer_id = ? WHERE stream_id = ?", "8", "s-pc");

            assertThat(broadcasts.findByStreamId("s-pc").orElseThrow().getStreamerId())
                    .as("낡은 값이 나오면 판정기가 엔티티를 올린 것이다 — 통로의 재조회가 같이 깨진다")
                    .isEqualTo("8");
        });
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 기한이 NULL이다 — 「아직 안 끝나 기한이 없다」(V203 주석). */
    private void 방송을_넣는다(String streamId, String streamerId) {
        jdbc.update("""
                        INSERT INTO broadcasts
                            (stream_id, streamer_id, status, started_at, ended_at, last_sequence, vod_expires_at)
                        VALUES (?, ?, 'live', ?, NULL, 1, NULL)""",
                streamId, streamerId,
                OffsetDateTime.ofInstant(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));
    }
}
