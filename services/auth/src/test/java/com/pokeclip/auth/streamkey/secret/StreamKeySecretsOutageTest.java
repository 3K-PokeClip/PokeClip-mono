package com.pokeclip.auth.streamkey.secret;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.streamkey.pairing.PairingCodeService;
import com.pokeclip.auth.support.FakeSecretsManagerServer;
import com.pokeclip.auth.support.FakeSecretsManagerServer.Step;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.withdrawal.WithdrawalTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Secrets Manager가 답하지 못할 때 페어링 교환(POK-272, 1번 설계 8-A 7)). 500이고, <b>코드가 소비되지 않아</b> 같은
 * 코드로 다시 시도하면 성공한다. 교환 트랜잭션이 롤백되며 {@code markUsed}도 되돌아가서다.
 *
 * <p>장애를 결정적으로 만들어야 해서 LocalStack이 아니라 가짜 서버에 붙인 컨텍스트다(하나 더 뜬다).
 */
class StreamKeySecretsOutageTest extends WithdrawalTestSupport {

    private static final String EXCHANGE = "/api/stream-keys/pairing-codes/exchange";
    private static final FakeSecretsManagerServer FAKE = FakeSecretsManagerServer.start();

    private final StreamKeyService streamKeyService;
    private final PairingCodeService pairingCodeService;

    StreamKeySecretsOutageTest(MockMvc mockMvc, UserService userService, TokenService tokenService, JdbcTemplate jdbc,
                               StreamKeyService streamKeyService, PairingCodeService pairingCodeService) {
        super(mockMvc, userService, tokenService, jdbc);
        this.streamKeyService = streamKeyService;
        this.pairingCodeService = pairingCodeService;
    }

    @DynamicPropertySource
    static void fakeStore(DynamicPropertyRegistry registry) {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        registry.add("pokeclip.stream-key-secret-store.type", () -> "aws");
        registry.add("pokeclip.stream-key-secret-store.retire-from", () -> "postgres,aws");
        registry.add("pokeclip.stream-key-secret-store.ref-prefix", () -> "pokeclip/test/stream-key/");
        registry.add("pokeclip.stream-key-secret-store.endpoint", FAKE::endpoint);
        registry.add("pokeclip.stream-key-secret-store.region", () -> "ap-northeast-2");
    }

    @AfterAll
    static void stopFake() {
        FAKE.close();
    }

    @BeforeEach
    void setUp() {
        FAKE.reset();
        jdbc.update("DELETE FROM pairing_exchange_attempts");
    }

    @Test
    void 저장소_장애면_500이고_코드가_남아_다시_시도하면_성공한다() throws Exception {
        User user = newUser();
        String passphrase = streamKeyService.ensureKey(user.getId()).passphrase();
        String code = pairingCodeService.issue(user.getId()).code();
        FAKE.script("GetSecretValue", Step.error("ThrottlingException", 400), Step.error("ThrottlingException", 400),
                Step.error("ThrottlingException", 400), Step.error("ThrottlingException", 400));

        // MockMvc는 오류 디스패치가 없어 500 응답 대신 예외가 그대로 올라온다. 컨테이너에서는 그것이 500이다
        assertThatThrownBy(() -> exchange(code))
                .hasRootCauseInstanceOf(SecretStoreUnavailableException.class);

        Integer used = jdbc.queryForObject(
                "SELECT count(*) FROM pairing_codes WHERE user_id = ? AND used_at IS NOT NULL", Integer.class, user.getId());
        assertThat(used).as("500인데 코드가 소비됐다. 사용자는 새 코드를 받아야 한다").isZero();

        // 저장소가 회복하면 같은 코드로 성공한다. SDK가 다 안 쓴 스로틀 대본은 버린다
        FAKE.clearScripts();
        String body = exchange(code).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.read(body, "$.passphrase").toString()).isEqualTo(passphrase);
    }

    private ResultActions exchange(String code) throws Exception {
        return mockMvc.perform(post(EXCHANGE)
                .with(request -> {
                    request.setRemoteAddr("10.0.7.1");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }
}
