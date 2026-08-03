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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    PairingCodeExchangeTest(MockMvc mockMvc, StreamKeyService streamKeyService,
                            UserService userService, UserRepository userRepository,
                            TokenService tokenService, MeterRegistry meterRegistry,
                            JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.streamKeyService = streamKeyService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.meterRegistry = meterRegistry;
        this.jdbc = jdbc;
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
        String first = issueCode(user);
        String second = issueCode(user);

        String a = exchange(first, "10.0.0.1").andReturn().getResponse().getContentAsString();
        String b = exchange(second, "10.0.0.2").andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.read(a, "$.passphrase").toString())
                .isEqualTo(JsonPath.read(b, "$.passphrase").toString());
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

    /** IP 원문을 남기지 않는다. 청소 작업이 없어 사실상 영구 보관이 된다. */
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
}
