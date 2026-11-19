package com.pokeclip.auth.streamkey.api;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.streamkey.StreamKeyMaterial;
import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PairingCodeExchangeTest extends IntegrationTestSupport {

    private static final String EXCHANGE = "/api/stream-keys/pairing-codes/exchange";

    private final MockMvc mockMvc;
    private final StreamKeyService streamKeyService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    PairingCodeExchangeTest(MockMvc mockMvc, StreamKeyService streamKeyService,
                            UserService userService, UserRepository userRepository,
                            TokenService tokenService, MeterRegistry meterRegistry,
                            JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        this.mockMvc = mockMvc;
        this.streamKeyService = streamKeyService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.meterRegistry = meterRegistry;
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    @BeforeEach
    void setUp() {
        clearChildren();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        clearChildren();
    }

    private void clearChildren() {
        // refresh_tokens도 users의 자식이다(V101:16). tokenService.issue가 행을
        // 만들므로 이것을 빼면 아래 userRepository.deleteAll()이 FK 위반으로 터진다.
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM pairing_exchange_attempts");
        jdbc.update("DELETE FROM pairing_codes");
        jdbc.update("DELETE FROM stream_keys");
        jdbc.update("DELETE FROM secrets");
    }

    @Test
    void 코드를_주면_streamid와_passphrase를_내려준다() throws Exception {
        User user = newUser();
        String code = issueCode(user);
        StreamKeyMaterial material = streamKeyService.findMaterial(user.getId()).orElseThrow();

        exchange(code, "10.0.0.1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streamid").value(material.streamId().toSrtFormat()))
                .andExpect(jsonPath("$.passphrase").value(material.passphrase()));
    }

    /** POK-72: 계정에 키가 이미 있으면 같은 것을 준다. 새로 만들지 않는다. */
    @Test
    void 코드를_두_번_발급해_교환해도_같은_키가_나온다() throws Exception {
        User user = newUser();
        // 새 코드를 받으면 전 코드는 만료되므로(PR #539 2차 리뷰) 받은 차례대로 하나씩 쓴다 — PC 두 대를 붙이는 순서다
        String first = issueCode(user);
        String a = exchange(first, "10.0.0.1").andReturn().getResponse().getContentAsString();
        String second = issueCode(user);
        String b = exchange(second, "10.0.0.2").andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.read(a, "$.passphrase").toString())
                .isEqualTo(JsonPath.read(b, "$.passphrase").toString());
    }

    /**
     * PR #539 2차 리뷰: 살아있는 코드는 회원당 하나다. 전 코드가 살아 있으면 그것을 넣어도 웹이 새 코드가 쓰인 것으로
     * 읽어 새 코드 창이 「연결됐어요」로 바뀐다. 전 코드는 「이미 썼다」(409)가 아니라 만료(410)로 답한다 — 쓴 적이 없다.
     */
    @Test
    void 새_코드를_받으면_전에_받은_코드는_만료된다() throws Exception {
        User user = newUser();
        String first = issueCode(user);
        String second = issueCode(user);

        exchange(first, "10.0.0.1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_EXPIRED"));
        exchange(second, "10.0.0.2").andExpect(status().isOk());
    }

    /** 쓰인 코드는 새 발급이 건드리지 않는다 — 다시 넣으면 여전히 「이미 썼다」(409)다. */
    @Test
    void 새_코드를_받아도_이미_쓴_코드는_409_그대로다() throws Exception {
        User user = newUser();
        String first = issueCode(user);
        exchange(first, "10.0.0.1").andExpect(status().isOk());
        issueCode(user);

        exchange(first, "10.0.0.2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_ALREADY_USED"));
    }

    /**
     * 🔴 연결 시각 기록이 실패해도 교환은 성공이다 — 커밋 뒤에 던지면 코드는 이미 쓰였는데 플러그인은 500을 받아 일회용
     * 코드만 잃는다(2차 리뷰: 이 약속을 재는 테스트가 없었다). 칸에 값을 못 넣게 하는 CHECK를 잠깐 걸어 진짜로 실패시킨다.
     */
    @Test
    void 연결_시각_기록이_실패해도_교환은_200이다() throws Exception {
        User user = newUser();
        String code = issueCode(user);
        jdbc.execute("ALTER TABLE stream_keys ADD CONSTRAINT test_block_last_paired "
                + "CHECK (last_paired_at IS NULL) NOT VALID");
        try {
            exchange(code, "10.0.0.1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.passphrase").exists());
        } finally {
            jdbc.execute("ALTER TABLE stream_keys DROP CONSTRAINT test_block_last_paired");
        }
    }

    /** 커밋 뒤 기록은 순서가 뒤집힐 수 있다 — 늦게 도착한 앞선 시각이 더 늦은 연결 시각을 덮지 않는다(2차 리뷰). */
    @Test
    void 연결_시각은_앞으로만_간다() throws Exception {
        User user = newUser();
        StreamKeyMaterial material = streamKeyService.ensureKey(user.getId());
        Instant later = Instant.parse("2026-11-19T05:00:00Z");
        streamKeyService.markPaired(material, later);

        streamKeyService.markPaired(material, later.minusSeconds(1));

        java.sql.Timestamp at = jdbc.queryForObject(
                "SELECT last_paired_at FROM stream_keys WHERE user_id = ? AND revoked_at IS NULL",
                java.sql.Timestamp.class, user.getId());
        assertThat(at.toInstant()).isEqualTo(later);
    }

    @Test
    void 하이픈과_소문자를_흡수한다() throws Exception {
        String code = issueCode(newUser());

        exchange(code.replace("-", "").toLowerCase(), "10.0.0.1")
                .andExpect(status().isOk());
    }

    /** 일회용. 두 번째는 사유가 갈려야 한다 — 만료와 조치가 다르다. */
    @Test
    void 한_번_쓴_코드는_409다() throws Exception {
        String code = issueCode(newUser());
        exchange(code, "10.0.0.1").andExpect(status().isOk());

        exchange(code, "10.0.0.2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_ALREADY_USED"));
    }

    @Test
    void 만료된_코드는_410이다() throws Exception {
        String code = issueCode(newUser());
        jdbc.update("UPDATE pairing_codes SET expires_at = now() - INTERVAL '1 minute'");

        exchange(code, "10.0.0.1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_EXPIRED"));
    }

    @Test
    void 없는_코드는_404다() throws Exception {
        exchange("ZZZZ-ZZZZ", "10.0.0.1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_NOT_FOUND"));
    }

    /** Crockford 밖 문자는 존재 여부를 볼 것도 없이 없는 코드다. */
    @Test
    void 형식이_틀린_코드도_404다() throws Exception {
        exchange("UUUU-UUUU", "10.0.0.1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_NOT_FOUND"));
    }

    /**
     * POK-72의 핵심 인수 기준. UPDATE ... WHERE used_at IS NULL 한 방이
     * 이것을 보장한다 — 애플리케이션 락 없이 PostgreSQL 행 잠금이 직렬화한다.
     */
    @Test
    void 동시에_교환해도_한_번만_성공한다() throws Exception {
        String code = issueCode(newUser());
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Integer>> jobs = IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        start.await();
                        // IP를 스레드마다 달리해 rate limit이 아니라 코드 경합만 본다.
                        return exchange(code, "10.0.1." + i).andReturn().getResponse().getStatus();
                    })
                    .toList();

            // submit → countDown → get 순서다. invokeAll은 전부 끝날 때까지
            // 블록하는데 작업들이 start.await()에 걸려 있어, countDown이 뒤에
            // 오면 그 자리에서 데드락이다. T3의 동시 발급 테스트와 같은 순서.
            List<Future<Integer>> futures = jobs.stream().map(pool::submit).toList();
            start.countDown();

            List<Integer> statuses = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            assertThat(statuses).filteredOn(s -> s == 200)
                    .as("일회용 코드가 두 번 이상 성공했다")
                    .hasSize(1);
        }
    }

    /**
     * ADR-019: IP당 분당 5회. <b>이 제한이 8자(40bit)를 쓸 수 있게 하는 전제다.</b>
     * 그리고 만료·사용됨을 사유로 구분해 내보내는 결정도 여기에 기대고 있다.
     */
    @Test
    void IP당_분당_5회를_넘으면_429다() throws Exception {
        for (int i = 0; i < 5; i++) {
            exchange("ZZZZ-ZZZZ", "10.0.9.9").andExpect(status().isNotFound());
        }

        exchange("ZZZZ-ZZZZ", "10.0.9.9")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_RATE_LIMITED"));
    }

    @Test
    void 다른_IP의_시도는_내_한도를_깎지_않는다() throws Exception {
        for (int i = 0; i < 5; i++) {
            exchange("ZZZZ-ZZZZ", "10.0.9.9");
        }

        exchange("ZZZZ-ZZZZ", "10.0.9.8").andExpect(status().isNotFound());
    }

    /**
     * IP 원문을 남기지 않는다. 행은 {@code pairing-attempts-keep-for}(1시간) 뒤 청소가 지운다({@code RetentionCleanerAttemptsTest}).
     * V106 파일 안의 「영구 보관」 문장은 체크섬 때문에 못 고친 옛 문장이고 표 COMMENT는 V112가 덮었다.
     */
    @Test
    void 시도_표에_IP_원문이_없다() throws Exception {
        exchange("ZZZZ-ZZZZ", "203.0.113.77");

        String dump = jdbc.queryForObject(
                // COALESCE가 특히 여기서 중요하다. PairingAttemptRecorder의
                // REQUIRES_NEW를 빠뜨리면 exchange 롤백에 시도 행이 딸려가 표가
                // 비는데, 그때 null이 오면 "IP 원문이 남았다"도 "rate limit이
                // 죽었다"도 아닌 엉뚱한 메시지로 실패한다.
                "SELECT COALESCE(string_agg(t::text, ' '), '') FROM pairing_exchange_attempts t",
                String.class);

        assertThat(dump).doesNotContain("203.0.113.77");
    }

    /**
     * 교환 실패를 INFO로 내리면 rate limit 자체가 깨졌는지 볼 눈이 없어진다.
     * 값만 남기고 알람은 걸지 않는다 — 한 IP에서 지속되는 것만 의미가 있고
     * 그 판단은 사람이 한다.
     */
    @Test
    // 이름을 "429가_…"로 시작할 수 없다. 자바 식별자는 숫자로 시작하지 못한다.
    void 지표에_429가_집계된다() throws Exception {
        double before = meterRegistry.counter("pokeclip.pairing.exchange.rate_limited").count();

        for (int i = 0; i < 6; i++) {
            exchange("ZZZZ-ZZZZ", "10.0.7.7");
        }

        assertThat(meterRegistry.counter("pokeclip.pairing.exchange.rate_limited").count())
                .isEqualTo(before + 1);
    }

    /**
     * <b>동시 요청도 한도에 걸려야 한다.</b> 시도 기록이 REQUIRES_NEW라 각 트랜잭션이
     * 자기 INSERT만 보고 세는데, 직렬화가 없으면 서로의 미커밋 행이 안 보여
     * 10건이 전부 {@code count=1}을 읽고 통과한다.
     *
     * <p>이것이 깨지면 ADR-019의 전제가 통째로 무너진다 — 8자(40bit)를 쓸 수 있는
     * 근거가 "10분 만료 + 교환 rate limit"이고, 공격자는 순차가 아니라 <b>동시성을
     * 직접 고른다.</b> 발급 쪽의 "±1은 무의미하다"는 정상 사용자 기준이었다.
     */
    @Test
    void 동시_교환도_IP당_한도를_넘지_못한다() throws Exception {
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Integer>> jobs = IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        start.await();
                        return exchange("ZZZZ-ZZZZ", "10.0.5.5").andReturn().getResponse().getStatus();
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

            // 정확히 5/5여야 한다. 어드바이저리 락이 같은 IP를 직렬화하므로
            // 앞의 5건은 count가 1~5라 통과하고(404), 6번째부터 count > 5라 429다.
            // "5건 이하"로 두면 10건이 전부 429여도 초록이라 아무것도 못 본다 —
            // 이 세션에서 그런 테스트에 두 번 데였다.
            assertThat(statuses).filteredOn(s -> s == 404)
                    .as("동시 요청이 rate limit을 통과했다. 한도가 5인데 그 이상이 코드 조회까지 갔다")
                    .hasSize(5);
            assertThat(statuses).filteredOn(s -> s == 429)
                    .as("한도를 넘은 나머지가 429를 못 받았다")
                    .hasSize(5);
        }
    }

    /**
     * POK-607: 웹이 「코드만 받았다」와 「코드를 OBS에 넣었다」를 가르는 유일한 근거다.
     * 발급만으로 시각이 생기면(첫 단언) 플러그인 없는 회원이 연결된 것으로 보인다.
     */
    @Test
    void 교환에_성공하면_상태_응답에_연결_시각이_실린다() throws Exception {
        User user = newUser();
        String code = issueCode(user);
        keyStatus(user).andExpect(jsonPath("$.lastPairedAt").doesNotExist());

        exchange(code, "10.0.0.1").andExpect(status().isOk());

        keyStatus(user)
                .andExpect(jsonPath("$.issued").value(true))
                .andExpect(jsonPath("$.lastPairedAt").exists());
    }

    /** 거절된 시도는 연결이 아니다. 만료된 코드를 넣은 회원에게 웹이 「연결됐어요」라고 하면 안 된다. */
    @Test
    void 교환에_실패하면_연결_시각이_생기지_않는다() throws Exception {
        User user = newUser();
        String code = issueCode(user);
        jdbc.update("UPDATE pairing_codes SET expires_at = now() - INTERVAL '1 minute'");

        exchange(code, "10.0.0.1").andExpect(status().isGone());

        keyStatus(user).andExpect(jsonPath("$.lastPairedAt").doesNotExist());
    }

    /** UPDATE의 조건에서 회원이 빠지면 한 사람의 교환이 모든 키에 시각을 남긴다. */
    @Test
    void 남의_교환은_내_연결_시각을_만들지_않는다() throws Exception {
        User me = newUser();
        issueCode(me);
        // 이메일이 같으면 둘째 회원이 안 만들어진다(이메일은 계정마다 하나다)
        User other = userService.findOrCreate(
                "sub-" + UUID.randomUUID(), "b@example.com", "남의 계정", null);
        String othersCode = issueCode(other);

        exchange(othersCode, "10.0.0.1").andExpect(status().isOk());

        keyStatus(other).andExpect(jsonPath("$.lastPairedAt").exists());
        keyStatus(me).andExpect(jsonPath("$.lastPairedAt").doesNotExist());
    }

    /**
     * PR #539 리뷰: 교환과 탈퇴가 두 행을 반대 순서로 잠그면 PostgreSQL이 한쪽을 deadlock으로 끊는다.
     * 탈퇴는 키 행(revokeAlive) → 코드 행(consumeAliveOfUser) 순서다. 그 사이에 교환이 들어오게 만든다 —
     * 교환이 코드 행을 쥔 채 키 행을 기다리면(연결 시각을 교환 트랜잭션 안에서 적으면) 아래 「탈퇴」의
     * 코드 행 UPDATE와 서로 기다려 한쪽이 예외로 끝난다. 연결 시각을 커밋 뒤에 적으면 교환은 코드 행을
     * 놓은 뒤 키 행을 기다리므로 둘 다 끝난다.
     */
    @Test
    void 탈퇴가_키_행을_쥔_사이에_교환해도_서로_막지_않는다() throws Exception {
        User user = newUser();
        String code = issueCode(user);
        CountDownLatch keyLocked = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            // 탈퇴(WithdrawalService.withdraw)와 같은 순서: 키 행을 잠그고, 잠시 뒤 코드 행을 잠근다
            Future<Integer> withdrawal = pool.submit(() -> transactionTemplate.execute(tx -> {
                jdbc.update("UPDATE stream_keys SET revoked_at = now() "
                        + "WHERE user_id = ? AND revoked_at IS NULL", user.getId());
                keyLocked.countDown();
                try {
                    // 교환이 코드 행을 잡고 키 행 앞에 설 시간을 준다
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return jdbc.update("UPDATE pairing_codes SET used_at = now() "
                        + "WHERE user_id = ? AND used_at IS NULL AND expires_at > now()", user.getId());
            }));
            keyLocked.await();

            int exchangeStatus = exchange(code, "10.0.0.1").andReturn().getResponse().getStatus();

            // deadlock이면 탈퇴 쪽이 DeadlockLoserDataAccessException으로, 또는 교환이 500으로 끝난다
            assertThat(withdrawal.get()).as("탈퇴가 교환 때문에 끊겼다").isNotNull();
            assertThat(exchangeStatus).as("교환이 탈퇴 때문에 끊겼다").isEqualTo(200);
        }
        // 탈퇴가 폐기한 키에는 「연결했다」가 남지 않는다
        Integer marked = jdbc.queryForObject(
                "SELECT count(*) FROM stream_keys WHERE user_id = ? AND last_paired_at IS NOT NULL",
                Integer.class, user.getId());
        assertThat(marked).isZero();
    }

    @Test
    void 로그인하지_않아도_교환할_수_있다() throws Exception {
        String code = issueCode(newUser());

        // Authorization 헤더 없이 부른다. 플러그인은 로그인하지 않는다.
        exchange(code, "10.0.0.1").andExpect(status().isOk());
    }

    private ResultActions exchange(String code, String clientIp) throws Exception {
        return mockMvc.perform(post(EXCHANGE)
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private ResultActions keyStatus(User user) throws Exception {
        return mockMvc.perform(get("/api/stream-keys")
                        .header("Authorization", "Bearer " + tokenService.issue(user).accessToken()))
                .andExpect(status().isOk());
    }

    private String issueCode(User user) throws Exception {
        String body = mockMvc.perform(post("/api/stream-keys/pairing-codes")
                        .header("Authorization", "Bearer " + tokenService.issue(user).accessToken()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    private User newUser() {
        return userService.findOrCreate(
                "sub-" + UUID.randomUUID(), "a@example.com", "김태현", null);
    }
}
