package com.pokeclip.auth.streamkey.api;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.streamkey.StreamKeyMaterial;
import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.support.CrockfordBase32;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.support.Sha256;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
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
    private final SecretStore secretStore;

    PairingCodeExchangeTest(MockMvc mockMvc, StreamKeyService streamKeyService,
                            UserService userService, UserRepository userRepository,
                            TokenService tokenService, MeterRegistry meterRegistry,
                            JdbcTemplate jdbc, SecretStore secretStore) {
        this.mockMvc = mockMvc;
        this.streamKeyService = streamKeyService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.meterRegistry = meterRegistry;
        this.jdbc = jdbc;
        this.secretStore = secretStore;
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

    /**
     * 응답은 <b>교환 뒤</b> 살아있는 키다. 교환 전 키와 비교하면 안 된다 — 교환이 그 키를 죽인다(POK-245).
     */
    @Test
    void 코드를_주면_새로_발급한_streamid와_passphrase를_내려준다() throws Exception {
        User user = newUser();
        String code = issueCode(user);
        StreamKeyMaterial before = streamKeyService.findMaterial(user.getId()).orElseThrow();

        String body = exchange(code, "10.0.0.1")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        StreamKeyMaterial after = streamKeyService.findMaterial(user.getId()).orElseThrow();
        assertThat(streamIdOf(body)).isEqualTo(after.streamId().toSrtFormat());
        assertThat(passphraseOf(body)).isEqualTo(after.passphrase());
        assertThat(streamIdOf(body))
                .as("교환 전 키를 그대로 돌려줬다 — 교환이 키를 안 바꿨다")
                .isNotEqualTo(before.streamId().toSrtFormat());
        assertThat(passphraseOf(body)).isNotEqualTo(before.passphrase());
    }

    /**
     * POK-245: 교환할 때마다 새 키다. 마지막으로 연결한 PC만 송출한다 — POK-72의 「같은 키를 준다」를 대체한다.
     *
     * <p>「다르다」만 재면 옛 키가 살아 있어도 초록이다. <b>옛 키가 {@code REVOKED}이고 그 비밀값이
     * 지워졌다</b>까지 재야 「옛 PC가 더는 못 쏜다」가 선다.
     */
    @Test
    void 교환할_때마다_새_키가_나오고_옛_키는_죽는다() throws Exception {
        User user = newUser();
        String first = issueCode(user);
        String second = issueCode(user);

        String a = exchange(first, "10.0.0.1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String oldRef = streamKeyService.findAlive(user.getId()).orElseThrow().getPassphraseRef();
        String b = exchange(second, "10.0.0.2").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(streamIdOf(b)).isNotEqualTo(streamIdOf(a));
        assertThat(passphraseOf(b)).isNotEqualTo(passphraseOf(a));
        assertThat(streamKeyService.resolve(streamIdOf(a)).reason())
                .as("🔴 먼저 연결한 PC의 키가 아직 산다 — 두 PC가 같은 계정으로 쏠 수 있다")
                .isEqualTo("REVOKED");
        assertThat(streamKeyService.resolve(streamIdOf(b)).valid()).isTrue();
        assertThat(secretStore.get(oldRef))
                .as("옛 passphrase가 보관소에 남아 있다")
                .isEmpty();
        assertThat(aliveKeys(user)).isEqualTo(1);
    }

    /**
     * 발급 창구를 안 거친 계정(키가 없는 계정)도 교환은 된다. {@code rotate}처럼 404를 내면
     * 「폐기할 키가 없다」가 교환을 막는다 — 교환의 목적은 무효화가 아니라 이 PC에 줄 자격증명이다.
     */
    @Test
    void 키가_없는_계정도_교환하면_키를_받는다() throws Exception {
        User user = newUser();
        String code = 살아있는_코드를_심는다(user, Instant.now().plus(Duration.ofMinutes(10)));

        String body = exchange(code, "10.0.0.1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(aliveKeys(user)).isEqualTo(1);
        assertThat(streamKeyService.findMaterial(user.getId()).orElseThrow().passphrase())
                .isEqualTo(passphraseOf(body));
    }

    /**
     * 같은 회원의 코드 여럿을 동시에 교환하면 <b>회원 행 락이 줄을 세운다</b> — 뒤에 선 쪽이 앞 쪽이 만든
     * 키를 폐기하고 새로 만든다. 그래서 전부 200이고 살아있는 키는 하나, 그것은 응답 중 하나다.
     *
     * <p>락이 없으면 둘이 같은 옛 키를 폐기하려 들어 한쪽이 404를 받거나
     * {@code uq_stream_keys_alive_user}에 걸린다. 발급 한도(분당 3회)가 코드 수의 상한이다.
     */
    @Test
    void 같은_회원의_코드를_동시에_교환해도_살아있는_키는_하나다() throws Exception {
        User user = newUser();
        List<String> codes = List.of(issueCode(user), issueCode(user), issueCode(user));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(codes.size())) {
            List<Future<MockHttpServletResponse>> futures = IntStream.range(0, codes.size())
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return exchange(codes.get(i), "10.0.2." + i).andReturn().getResponse();
                    }))
                    .toList();
            start.countDown();

            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).extracting(MockHttpServletResponse::getStatus)
                    .as("같은 회원의 교환끼리 부딪혀 실패했다 — 회원 행 락이 줄을 못 세웠다")
                    .containsOnly(200);
            assertThat(aliveKeys(user)).isEqualTo(1);
            StreamKeyMaterial alive = streamKeyService.findMaterial(user.getId()).orElseThrow();
            List<String> passphrases = new ArrayList<>();
            for (MockHttpServletResponse response : responses) {
                passphrases.add(passphraseOf(response.getContentAsString()));
            }
            assertThat(passphrases)
                    .as("살아있는 키가 어느 응답에도 없다 — 아무 PC도 못 쏜다")
                    .contains(alive.passphrase());
            assertThat(revokedKeySecrets(user))
                    .as("폐기된 키의 비밀값이 남았다 — 폐기한 키와 지운 ref가 서로 다르다")
                    .isZero();
        }
    }

    /**
     * 🔴 <b>만료 판정 시각은 회원 행 락을 얻은 뒤에 잡는다.</b> 요청 시작 시각을 쓰면 락을 기다리는 사이
     * 만료된 코드가 「아직 살아있다」로 소비된다(탈퇴의 락 대기 시험과 같은 결함).
     *
     * <p>락을 1.5초 쥐고 코드를 0.6초 뒤에 만료시킨다 — 시각을 락 앞에서 잡으면 결정적으로 200,
     * 락 뒤에서 잡으면 결정적으로 410이다.
     */
    @Test
    void 회원_행_락을_기다리는_사이_만료된_코드는_410이다() throws Exception {
        User user = newUser();
        String code = 살아있는_코드를_심는다(user, Instant.now().plusMillis(600));

        회원_행을_잠근_채(user.getId(), Duration.ofMillis(1500), () ->
                exchange(code, "10.0.3.1")
                        .andExpect(status().isGone())
                        .andExpect(jsonPath("$.reason").value("PAIRING_CODE_EXPIRED")));

        assertThat(aliveKeys(user))
                .as("만료로 거절했는데 키가 생겼다")
                .isZero();
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

    private static String streamIdOf(String body) {
        return JsonPath.read(body, "$.streamid");
    }

    private static String passphraseOf(String body) {
        return JsonPath.read(body, "$.passphrase");
    }

    /** 리포지토리로 세지 않는다 — 영속성 컨텍스트가 메모리의 객체를 돌려줄 수 있다. */
    private int aliveKeys(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM stream_keys WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, user.getId());
    }

    /** 폐기된 키가 아직 가리키는 비밀값 수. 교체가 옛 비밀값을 지웠으면 0이다. */
    private int revokedKeySecrets(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM secrets s "
                        + "JOIN stream_keys k ON k.passphrase_ref = s.ref "
                        + "WHERE k.user_id = ? AND k.revoked_at IS NOT NULL",
                Integer.class, user.getId());
    }

    /** 발급 창구를 안 쓴다 — 그 창구는 키를 함께 만들어 「키가 없는 계정」을 못 만든다. */
    private String 살아있는_코드를_심는다(User user, Instant expiresAt) {
        String code = CrockfordBase32.random(new SecureRandom(), 8);
        jdbc.update("INSERT INTO pairing_codes (user_id, code_hash, expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                user.getId(), Sha256.hex(code), Timestamp.from(expiresAt), Timestamp.from(Instant.now()));
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    @FunctionalInterface
    private interface 시험_동작 {
        void run() throws Exception;
    }

    /**
     * 다른 커넥션이 회원 행 락을 {@code hold}만큼 쥐는 동안 {@code 그동안}을 부른다.
     * {@code WithdrawalTestSupport}의 같은 이름 도구와 같다 — 이 클래스는 그 계층 밖이라 따로 둔다.
     */
    private void 회원_행을_잠근_채(Long userId, Duration hold, 시험_동작 그동안) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch 잡았다 = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> {
                try (Connection connection = jdbc.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement select =
                                 connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                        select.setLong(1, userId);
                        try (ResultSet found = select.executeQuery()) {
                            if (!found.next()) {
                                throw new IllegalStateException("잠글 회원 행이 없다 userId=" + userId);
                            }
                        }
                    }
                    잡았다.countDown();
                    Thread.sleep(hold.toMillis());
                    connection.commit();
                }
                return null;
            });
            assertThat(잡았다.await(10, TimeUnit.SECONDS))
                    .as("다른 트랜잭션이 회원 행 락을 못 잡았다 — 아래 대기가 안 생기므로 시험이 아무것도 안 잰다")
                    .isTrue();
            그동안.run();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
