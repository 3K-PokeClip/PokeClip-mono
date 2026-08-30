package com.pokeclip.auth.withdrawal;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.streamkey.StreamKeyMaterial;
import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴가 <b>송출 자격</b>을 회수하는지 잰다(PRD D4·성공 기준 14).
 *
 * <p>🔴 <b>이 카드에서 가장 위험한 자리다.</b> 페어링 교환은 로그인이 필요 없어서
 * (코드 자체가 자격증명이다) 전면 차단 필터가 <b>못 막는다.</b> 그리고
 * {@code PairingCodeService.exchange}가 {@code StreamKeyService.ensureKey}를 부르는데
 * <b>{@code ensureKey}는 살아있는 키가 없으면 새로 만든다.</b>
 *
 * <pre>
 * 탈퇴 → 키 폐기됨 → 살아있던 코드로 교환 → ensureKey가 「키가 없네」로 읽고 새 키 발급
 *      → 탈퇴자 명의로 다시 송출 가능
 * </pre>
 *
 * <p>🔴 <b>그래서 「교환이 거절된다」로 끝내면 안 된다.</b> 거절만 재면 위 경로를 <b>한 번도 안 밟고</b>
 * 초록이 난다 — 키 폐기가 없으면 교환이 그냥 옛 키를 돌려주기 때문이다. <b>「살아있는 키가 0건」</b>까지
 * 세어야 {@code ensureKey} 갈래가 잡힌다.
 *
 * <p><b>표를 셀 때 {@code JdbcTemplate}을 쓴다</b> — {@code WithdrawalTest}와 같은 이유다.
 * 리포지토리로 읽으면 영속성 컨텍스트가 메모리에 있는 객체를 돌려줄 수 있다.
 */
class WithdrawalStreamKeyTest extends WithdrawalTestSupport {

    private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";
    private static final String EXCHANGE = "/api/stream-keys/pairing-codes/exchange";

    private final StreamKeyService streamKeyService;
    /** 락 대기 갈래만 창구를 안 거친다 — 락을 쥔 채 부르는 것이 요점이라 MockMvc를 낄 자리가 없다. */
    private final WithdrawalService withdrawalService;

    WithdrawalStreamKeyTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                            JdbcTemplate jdbc, StreamKeyService streamKeyService,
                            WithdrawalService withdrawalService) {
        super(mockMvc, userService, tokenService, jdbc);
        this.streamKeyService = streamKeyService;
        this.withdrawalService = withdrawalService;
    }

    /**
     * Media가 물어보는 자리에서 <b>사유가 갈려야 한다</b> — {@code REVOKED}는 「이 키는 죽었다」이고
     * {@code NOT_FOUND}는 「그런 키를 모른다」다. 행을 지우면 뒤엣것이 되고, 그러면 사고 조사에서
     * 「폐기했다」와 「원래 없었다」가 구분되지 않는다.
     */
    @Test
    void 탈퇴하면_그_스트림키가_REVOKED가_된다() throws Exception {
        User user = newUser();
        StreamKeyMaterial material = streamKeyService.ensureKey(user.getId());

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/internal/stream-keys/resolve")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"streamid\":\"" + material.streamId().toSrtFormat() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("REVOKED"));
        assertThat(aliveKeys(user)).isZero();
        assertThat(allKeys(user))
                .as("행을 지우면 REVOKED와 NOT_FOUND가 구분되지 않는다")
                .isEqualTo(1);
    }

    /**
     * 🔴 <b>이 시험이 이 태스크의 이유다.</b> 교환 경로에는 로그인이 없어 전면 차단이 못 막는다.
     *
     * <p>단언이 <b>둘</b>인 이유: 거절(409)만 재면 <b>키 폐기가 빠져도 초록</b>이고(옛 키를 그대로
     * 돌려주므로 교환이 성공하지 않는 것처럼 보이지 않는다), 살아있는 키 0건만 재면 <b>코드 회수가
     * 빠져도 초록</b>일 수 있다. 둘을 같이 재야 {@code ensureKey}가 새 키를 만드는 갈래가 잡힌다.
     *
     * <p>409({@code ALREADY_USED})지 404가 아닌 것도 요지다 — <b>지우지 않고 쓴 것으로 표시</b>하므로
     * 시도한 쪽이 받는 답이 사실에 가깝고, rate limit 기록과도 어긋나지 않는다.
     */
    @Test
    void 탈퇴_뒤_살아있던_코드로_교환하면_거절되고_새_키도_안_생긴다() throws Exception {
        User user = newUser();
        String code = issueCode(user);

        assertThat(aliveKeys(user))
                .as("발급이 안 됐으면 아래 「0건」은 처음부터 참이라 아무것도 안 잰다")
                .isEqualTo(1);

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());

        // 로그인 없이 부른다 — 플러그인은 로그인하지 않는다. 헤더를 실으면 전면 차단 필터가
        // 먼저 401을 내므로 「코드가 죽어서 막힌 것」인지 구분되지 않는다.
        exchange(code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_ALREADY_USED"));

        assertThat(aliveKeys(user))
                .as("🔴 교환은 거절됐는데 살아있는 키가 생겼다 — ensureKey가 「키가 없네」로 읽고 새로 발급했다")
                .isZero();
    }

    /**
     * <b>만료된 코드는 건드리지 않는다</b> — 이미 못 쓰는 것을 「방금 썼다」로 만들면 그 시각이
     * 거짓이 되고, 사고 조사에서 탈퇴 시각과 교환 시각이 붙어 보인다.
     *
     * <p>같은 시험에서 <b>살아있는 코드는 소비된다</b>를 함께 잰다. 그것이 없으면 회수 코드를
     * 통째로 지워도 이 시험은 초록이다(만료 코드는 원래 {@code used_at}이 비어 있다).
     */
    @Test
    void 만료된_코드는_건드리지_않고_살아있는_코드만_소비한다() throws Exception {
        User user = newUser();
        issueCode(user);
        long expired = lastCodeId(user);
        jdbc.update("UPDATE pairing_codes SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), expired);
        issueCode(user);
        long alive = lastCodeId(user);

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());

        assertThat(usedAtOf(alive))
                .as("살아있던 코드가 안 닫혔다 — 이 갈래가 초록이면 아래 단언은 자동으로 참이다")
                .isNotNull();
        assertThat(usedAtOf(expired))
                .as("만료된 코드까지 소비했다 — 「방금 썼다」가 거짓 기록이 된다")
                .isNull();
    }

    /**
     * 🔴 <b>「살아있다」의 기준 시각이 락 앞에서 잡히면 이 약속이 깨진다</b>(로컬 리뷰 라운드 1 R1-1).
     *
     * <p>위 시험은 탈퇴 <b>전부터</b> 만료돼 있던 코드를 본다. 여기는 <b>요청이 시작될 때는 살아 있었고
     * 락을 얻는 순간에는 죽어 있는</b> 코드다 — {@code consumeAliveOfUser}가 {@code expiresAt > :now}로
     * 고르므로, {@code now}가 락 대기만큼 과거이면 그 코드가 「살아있다」로 걸려 <b>「방금 썼다」가 찍힌다.</b>
     *
     * <p>운영에서 그 대기를 만드는 것은 {@code ChzzkTokenRefresher}가 같은 회원 행 락을 쥔 채
     * 치지직 HTTP(최대 7초)를 기다리는 자리다(auth/CLAUDE.md 「알려진 구멍」 10). 페어링 코드 수명이
     * 분 단위라 7초는 좁지만, <b>코드가 만료되기 직전에 탈퇴하면</b> 그 창에 그대로 들어간다.
     *
     * <p>락을 1.5초 쥐고 코드를 0.6초 뒤에 만료시킨다 — 고치기 전에는 {@code now}가 만료 전이라
     * 결정적으로 빨간불이고, 고친 뒤에는 만료 뒤라 결정적으로 초록이다.
     */
    @Test
    void 락을_기다리는_사이_만료된_코드는_소비_표시하지_않는다() throws Exception {
        User user = newUser();
        issueCode(user);
        long code = lastCodeId(user);
        jdbc.update("UPDATE pairing_codes SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plusMillis(600)), code);

        회원_행을_잠근_채(user.getId(), Duration.ofMillis(1500),
                () -> withdrawalService.withdraw(user.getId()));

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Timestamp.class, user.getId()))
                .as("탈퇴가 롤백됐다 — 아래 단언이 아무것도 안 잰다")
                .isNotNull();
        assertThat(usedAtOf(code))
                .as("🔴 락을 기다리는 사이 만료된 코드에 「방금 썼다」가 찍혔다 — 시각을 락 앞에서 잡고 있다")
                .isNull();
    }

    /**
     * 🔴 <b>남의 발급물은 한 톨도 안 건드린다.</b> 회수 쿼리 둘이 회원 범위를 잃으면 <b>탈퇴 한 건이
     * 전 회원의 송출을 끊고 전 회원의 코드를 닫는다</b> — 응답은 204고 탈퇴자 쪽 단언은 전부 초록이라
     * <b>조용하다.</b>
     *
     * <p>이 그물이 없으면 실제로 안 잡힌다(주입 실측 2026-08-31): {@code consumeAliveOfUser}·
     * {@code revokeAlive} 양쪽에서 {@code userId} 조건을 지웠을 때 <b>659건 전부 초록</b>이었다.
     * {@code revokeAlive} 쪽은 재발급이 쓰던 <b>이 카드 이전부터 있던 구멍</b>이다 — 탈퇴가 그 쿼리를
     * 두 번째 소비자로 들이면서 대가가 「내 키 하나」에서 「전 회원의 키」로 커졌다.
     *
     * <p>마지막 갈래로 <b>남의 코드가 아직 교환된다</b>까지 잰다. 표만 보면 {@code used_at}이 비어 있는
     * 것이 「안 건드렸다」인지 「원래 그렇다」인지 갈리지 않는데, 교환이 실제로 200이면 그 코드가
     * <b>기능으로도 살아 있다</b>가 확정된다.
     */
    @Test
    void 남의_스트림키와_코드는_안_건드린다() throws Exception {
        User withdrawing = newUser();
        issueCode(withdrawing);
        User bystander = newUser();
        String bystanderCode = issueCode(bystander);

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(withdrawing)))
                .andExpect(status().isNoContent());

        assertThat(aliveKeys(bystander))
                .as("🔴 남의 살아있는 키가 폐기됐다 — revokeAlive가 회원 범위를 잃었다")
                .isEqualTo(1);
        assertThat(usedAtOf(lastCodeId(bystander)))
                .as("🔴 남의 페어링 코드가 닫혔다 — consumeAliveOfUser가 회원 범위를 잃었다")
                .isNull();
        // 표만 보면 「안 건드렸다」와 「원래 그렇다」가 안 갈린다. 실제로 교환이 되는지까지 본다.
        exchange(bystanderCode).andExpect(status().isOk());
    }

    /** 발급물이 하나도 없는 회원이 대부분이다. 회수가 그 갈래에서 터지면 탈퇴 자체가 막힌다. */
    @Test
    void 스트림키도_코드도_없는_회원이_탈퇴해도_실패하지_않는다() throws Exception {
        User user = newUser();

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());

        assertThat(aliveKeys(user)).isZero();
    }

    /**
     * 🔴 <b>PRD 성공 기준 14. 회원 행 락을 잡는 유일한 이유가 이것이다.</b>
     *
     * <p>탈퇴가 락을 안 잡으면 이렇게 갈린다 — 탈퇴의 {@code revokeAlive}가 문장 시작 시점 스냅샷으로
     * 옛 키를 겨누고 그 행 잠금에 걸려 기다리는 사이, 재발급이 <b>새 키를 만들고 커밋</b>한다.
     * 깨어난 UPDATE는 옛 키가 이미 폐기된 것을 보고 <b>0행</b>을 돌려주고, 새 키는 그 문장의 스냅샷
     * 밖이라 손도 못 댄다 → <b>탈퇴한 계정에 살아있는 키가 남는다.</b>
     *
     * <p>재는 것이 셋인 이유: ①은 「자격이 남았나」, ②는 「이 회원 몫 비밀값이 정확히 하나인가」,
     * ③은 「주인 없는 비밀값이 생겼나」다.
     *
     * <p>🔴 <b>②가 없으면 ③은 아무것도 안 잰다</b>(주입 H 실측). 재발급이 옛 비밀값 삭제를 통째로
     * 건너뛰어도 <b>폐기된 키 행이 그것을 계속 가리키고 있어</b> 「주인 없음」에 안 걸린다 —
     * 쌓이는 것은 고아가 아니라 <b>주인이 죽은</b> 비밀값이다. 둘은 다른 사고다:
     * ②는 「안 지웠다」, ③은 「엉뚱한 것을 지웠다」({@code StreamKeyRotateTest}가 잡는 그 갈래).
     *
     * <p>②가 <b>1</b>인 이유: 재발급은 매번 새 비밀값을 만들고 <b>직전 것을 커밋 뒤에 지운다.</b>
     * 탈퇴가 먼저 이기면 처음 것 하나가 남고, 재발급이 R번 이기면 마지막 것 하나가 남는다 —
     * 어느 순서든 하나다. (탈퇴는 비밀값을 아직 안 지운다. 그것은 태스크 7이다.)
     * ③을 <b>차이로</b> 재는 것은 다른 시험 클래스가 남긴 것을 우리 탓으로 세지 않기 위해서다.
     *
     * <p>{@code StreamKeyRotateTest.동시에_재발급해도_secret이_고아로_남지_않는다}와 같은 모양이다 —
     * submit → countDown → get 순서를 지킨다. {@code invokeAll}은 전부 끝날 때까지 블록하는데
     * 작업들이 {@code start.await()}에 걸려 있어 countDown이 뒤면 데드락이다.
     */
    @Test
    void 탈퇴와_재발급이_동시에_와도_살아있는_키가_안_남는다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());
        String bearer = bearer(user);
        int threads = 8;
        int orphansBefore = orphanStreamKeySecrets();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Integer>> jobs = IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        start.await();
                        // 0번만 탈퇴다. 나머지는 재발급으로 같은 락을 두드린다 —
                        // 하나뿐이면 겹치는 창이 좁아 락을 지워도 초록이 나기 쉽다.
                        var request = i == 0
                                ? delete("/api/auth/me")
                                : post("/api/stream-keys/rotate");
                        return mockMvc.perform(request.header("Authorization", bearer))
                                .andReturn().getResponse().getStatus();
                    })
                    .toList();

            List<Future<Integer>> futures = jobs.stream().map(pool::submit).toList();
            start.countDown();
            List<Integer> statuses = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            assertThat(statuses)
                    .as("탈퇴가 실패했다 — 아래 「0건」이 탈퇴 때문인지 알 수 없어진다")
                    .contains(204);
            assertThat(aliveKeys(user))
                    .as("🔴 탈퇴한 계정에 살아있는 키가 남았다. 재발급이 탈퇴의 폐기 문장을 앞질렀다")
                    .isZero();
            assertThat(secretsOf(user))
                    .as("이 회원 몫 비밀값이 하나가 아니다 — 재발급이 직전 것을 안 지웠다")
                    .isEqualTo(1);
            assertThat(orphanStreamKeySecrets() - orphansBefore)
                    .as("주인 없는 비밀값이 늘었다 — 폐기한 키와 삭제한 ref가 서로 다른 키다")
                    .isZero();
        }
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private int aliveKeys(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM stream_keys WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, user.getId());
    }

    private int allKeys(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM stream_keys WHERE user_id = ?", Integer.class, user.getId());
    }

    /** 이 회원의 스트림키 행이 가리키는 비밀값 수. 폐기된 행도 센다 — 「안 지웠다」가 거기서 보인다. */
    private int secretsOf(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM secrets s "
                        + "JOIN stream_keys k ON k.passphrase_ref = s.ref "
                        + "WHERE k.user_id = ?", Integer.class, user.getId());
    }

    /**
     * {@code secrets}에는 회원 칸이 없어 그 회원 몫만 셀 수 없다. 그래서 <b>스트림키 몫 전체</b>에서
     * 「가리키는 행이 없는 것」을 세고 호출부가 <b>차이</b>로 읽는다.
     * {@code streamkey:} 접두어로 좁히는 이유는 치지직·유튜브 토큰이 같은 표를 쓰는데
     * 그쪽 참조 칸은 {@code stream_keys}에 없어 <b>전부 고아로 세어지기</b> 때문이다.
     */
    private int orphanStreamKeySecrets() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM secrets s
                WHERE s.ref LIKE 'streamkey:%'
                  AND NOT EXISTS (SELECT 1 FROM stream_keys k WHERE k.passphrase_ref = s.ref)
                """, Integer.class);
    }

    private Timestamp usedAtOf(long codeId) {
        return jdbc.queryForObject(
                "SELECT used_at FROM pairing_codes WHERE id = ?", Timestamp.class, codeId);
    }

    private long lastCodeId(User user) {
        return jdbc.queryForObject(
                "SELECT max(id) FROM pairing_codes WHERE user_id = ?", Long.class, user.getId());
    }

    private ResultActions exchange(String code) throws Exception {
        return mockMvc.perform(post(EXCHANGE)
                .with(request -> {
                    // 시험마다 다른 값이어야 한다. 이 계층은 pairing_exchange_attempts를 안 거두므로
                    // 같은 값을 쓰면 앞 시험이 남긴 시도가 뒤 시험을 429로 밀어낸다.
                    request.setRemoteAddr("10.171." + UUID.randomUUID());
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    /** 발급 창구가 {@code ensureKey}의 유일한 입구다 — 코드와 스트림키가 한 번에 생긴다. */
    private String issueCode(User user) throws Exception {
        String body = mockMvc.perform(post("/api/stream-keys/pairing-codes")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }
}
