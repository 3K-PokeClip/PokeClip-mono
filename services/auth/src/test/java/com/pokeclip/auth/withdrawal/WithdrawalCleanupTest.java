package com.pokeclip.auth.withdrawal;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.support.PhotoLocalStackFixture;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 커밋 뒤 정리 — <b>표 밖에 남는 것</b>을 잰다(전수 표의 기준 C·D).
 *
 * <p>여기 있는 것은 표를 아무리 뒤져도 안 보이는 자리다. 비밀값은 회원 칸이 없어
 * {@code stream_keys} 행을 통해서만 고를 수 있고, 사진 파일은 아예 DB 밖이다.
 * <b>그래서 「표가 비었다」로는 한 줄도 대신 잴 수 없다.</b>
 *
 * <p>🔴 <b>사진은 「파일 개수」가 아니라 「이미 나간 주소가 404」로 잰다.</b> 감사 1회차가
 * 지금 상태를 재현했다 — 탈퇴 뒤에도 그 주소가 {@code afterStatus=200 · sameBytes=true ·
 * photoKeyAfter=null}이었다. <b>표를 비워도 사진이 바이트까지 똑같이 나간다.</b>
 * 개수만 세면 「표에서 안 보인다」와 「밖에서 못 받는다」가 구분되지 않는다.
 *
 * <p>정리는 전용 스레드에서 도므로 <b>세기 전에 {@code awaitIdle}로 기다린다</b> —
 * 안 기다리면 이 검사들은 정리 코드를 통째로 지워도 초록일 수 있다(경합).
 *
 * <p>사진 창고를 켠 컨텍스트가 필요해 {@code PhotoLocalStackFixture}를 등록한다.
 * {@code PhotoTestSupport}를 상속하지 않는 이유는 그쪽 {@code @BeforeEach}가
 * {@code userRepository.deleteAll()}이라 스트림키·연동 자식 행이 있는 이 계층에서는
 * 외래키에 막히기 때문이다.
 */
class WithdrawalCleanupTest extends WithdrawalTestSupport {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final StreamKeyService streamKeyService;
    private final SecretStore secretStore;
    private final WithdrawalCleanupExecutor cleanup;

    WithdrawalCleanupTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                          JdbcTemplate jdbc, StreamKeyService streamKeyService, SecretStore secretStore,
                          WithdrawalCleanupExecutor cleanup) {
        super(mockMvc, userService, tokenService, jdbc);
        this.streamKeyService = streamKeyService;
        this.secretStore = secretStore;
        this.cleanup = cleanup;
    }

    @DynamicPropertySource
    static void photoProperties(DynamicPropertyRegistry registry) {
        PhotoLocalStackFixture.register(registry);
    }

    /**
     * 🔴 <b>「살아있는 키 하나」로는 못 채운다</b>(감사 1회차 중대-2). 재발급을 여러 번 한 회원은
     * <b>폐기된 키가 여럿이고 각각의 비밀값이 남아 있을 수 있다</b> — 재발급의 커밋 뒤 삭제가
     * 한 번이라도 실패하면 그렇게 되고, 구현자 주입이 이미 {@code secretsOf=5}를 만들었다.
     * <b>「정확히 하나」는 불변식이 아니다.</b>
     *
     * <p>그래서 여기서 <b>그 상태를 손으로 만든다</b> — 폐기된 키의 자리에 비밀값을 되살려 놓는다.
     * 이 줄이 없으면 살아있는 키 하나만 지우는 구현도 초록이다.
     */
    @Test
    void 탈퇴하면_폐기된_키의_비밀값까지_한_톨도_안_남는다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());
        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(user)))
                .andExpect(status().isOk());
        // 재발급의 커밋 뒤 삭제가 실패한 회원을 재현한다 — 폐기된 키의 자리에 비밀값이 그대로 있다.
        List<String> refs = passphraseRefs(user);
        assertThat(refs).as("재발급이 안 됐으면 아래가 아무것도 안 잰다").hasSize(2);
        secretStore.put(refs.get(0), "되살린-옛-비밀값");
        assertThat(secretsOf(user)).as("전제: 이 회원 몫 비밀값이 둘이다").isEqualTo(2);

        withdraw(user);

        assertThat(secretsOf(user))
                .as("🔴 폐기된 키의 비밀값이 남았다 — 살아있는 키 하나만 지우는 구현이다")
                .isZero();
    }

    /**
     * 🔴 <b>이 시험이 이 태스크의 이유다.</b> 사진 주소는 {@code permitAll}이라 전면 차단 필터가
     * 원리상 못 막고, {@code ProfilePhotoService.read}는 <b>회원 표를 아예 안 읽어</b> 익명화도
     * 그 주소를 못 닫는다. 표는 상태 없는 HMAC이라 폐기할 수단도 없다(남은 수명 10~20분).
     *
     * <p><b>그 주소를 닫는 것은 파일 삭제 하나뿐이다.</b> 그래서 그물을 파일 개수가 아니라
     * <b>주소</b>로 친다 — 탈퇴 전 200을 먼저 재지 않으면 404가 「지워져서」인지
     * 「원래 없어서」인지 갈리지 않는다.
     */
    @Test
    void 탈퇴_전에_받아_둔_사진_주소는_404가_된다() throws Exception {
        User user = newUser();
        upload(user, "before.png");
        String url = photoUrlFromMe(user);

        byte[] before = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(before).as("탈퇴 전에 안 나오면 아래 404가 아무것도 안 잰다").isNotEmpty();

        withdraw(user);

        mockMvc.perform(get(url))
                .andExpect(status().isNotFound());
    }

    /**
     * 자리가 둘이고 버전의 홀짝으로 번갈아 쓴다({@code PhotoStorage.keyOf}). <b>둘 다 지워야 한다</b> —
     * 표는 한 자리만 가리키므로 표의 키만 보고 지우면 반대 자리의 파일이 그대로 남는다.
     */
    @Test
    void 탈퇴하면_사진_자리_둘이_모두_비워진다() throws Exception {
        User user = newUser();
        upload(user, "first.png");
        upload(user, "second.png");
        assertThat(PhotoLocalStackFixture.downloadPhoto(user.getId(), 0))
                .as("전제: 자리 0에 파일이 있다").isPresent();
        assertThat(PhotoLocalStackFixture.downloadPhoto(user.getId(), 1))
                .as("전제: 자리 1에 파일이 있다 — 한 자리만 차 있으면 「둘 다 지운다」를 못 잰다").isPresent();

        withdraw(user);

        assertThat(PhotoLocalStackFixture.downloadPhoto(user.getId(), 0)).isEmpty();
        assertThat(PhotoLocalStackFixture.downloadPhoto(user.getId(), 1)).isEmpty();
    }

    /**
     * <b>표가 안 가리키는 파일도 지운다.</b> 창고에 쓴 뒤 표 갱신이 실패하면 그런 파일이 남는데
     * (「알려진 구멍」 23), 그 자리를 표에서 읽어 지우는 구현은 그것을 영영 못 찾는다.
     * 여기서는 올린 뒤 표의 사진 칸 둘을 비워 그 상태를 만든다.
     */
    @Test
    void 표가_사진을_안_가리켜도_창고에서_지운다() throws Exception {
        User user = newUser();
        upload(user, "orphan.png");
        // 칸 둘은 함께 비어야 한다(V111의 CHECK). 한쪽만 비우면 부팅이 아니라 이 UPDATE가 막힌다.
        jdbc.update("UPDATE users SET profile_photo_key = NULL, profile_photo_updated_at = NULL WHERE id = ?",
                user.getId());
        assertThat(anyPhoto(user)).as("전제: 표는 안 가리키는데 파일은 있다").isPresent();

        withdraw(user);

        assertThat(anyPhoto(user))
                .as("🔴 표가 가리키는 자리만 지웠다 — 고아 파일이 그대로 남는다")
                .isEmpty();
    }

    /**
     * 🔴 <b>정리가 회원 범위를 잃으면 탈퇴 한 건이 남의 사진과 비밀값을 지운다.</b> 응답은 204고
     * 탈퇴자 쪽 단언은 전부 초록이라 <b>조용하다</b> — 회수 쿼리에 같은 그물을 친 이유와 같다.
     */
    @Test
    void 남의_비밀값과_사진은_안_건드린다() throws Exception {
        User withdrawing = newUser();
        streamKeyService.ensureKey(withdrawing.getId());
        upload(withdrawing, "mine.png");
        User bystander = newUser();
        streamKeyService.ensureKey(bystander.getId());
        upload(bystander, "yours.png");

        withdraw(withdrawing);

        assertThat(secretsOf(bystander))
                .as("🔴 남의 비밀값이 지워졌다 — 지울 자리를 회원으로 좁히지 않았다")
                .isEqualTo(1);
        assertThat(anyPhoto(bystander))
                .as("🔴 남의 사진 파일이 지워졌다")
                .isPresent();
        // 표만 보면 「안 지웠다」와 「원래 그렇다」가 안 갈린다. 실제로 그 주소가 아직 200인지까지 본다.
        mockMvc.perform(get(photoUrlFromMe(bystander))).andExpect(status().isOk());
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    /** 탈퇴시키고 <b>정리 잡이 끝날 때까지 기다린다.</b> 안 기다리면 아래 단언이 경합으로 초록이 된다. */
    private void withdraw(User user) throws Exception {
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(20)))
                .as("정리 잡이 시한 안에 안 끝났다 — 아래 단언은 「아직 안 지웠다」를 보는 것이 된다")
                .isTrue();
    }

    /** 이 회원의 스트림키 행이 가리키는 비밀값 수. 폐기된 행도 센다 — 「안 지웠다」가 거기서 보인다. */
    private int secretsOf(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM secrets s "
                        + "JOIN stream_keys k ON k.passphrase_ref = s.ref "
                        + "WHERE k.user_id = ?", Integer.class, user.getId());
    }

    private List<String> passphraseRefs(User user) {
        return jdbc.queryForList(
                "SELECT passphrase_ref FROM stream_keys WHERE user_id = ? ORDER BY id",
                String.class, user.getId());
    }

    private Optional<byte[]> anyPhoto(User user) {
        return PhotoLocalStackFixture.downloadAnyPhoto(user.getId());
    }

    /**
     * <b>화면이 실제로 받아 가는 주소</b>를 그대로 쓴다. 검사가 표를 직접 만들면 「우리가 만들 수 있는
     * 주소」를 재는 것이지 「이미 나간 주소」를 재는 것이 아니다.
     */
    private String photoUrlFromMe(User user) throws Exception {
        String body = mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.profileImageUrl");
        assertThat(url).as("사진을 올렸는데 회원 정보가 우리 주소를 안 준다")
                .startsWith(PhotoLocalStackFixture.BASE_URL + "/api/profile-photos/");
        return url.substring(PhotoLocalStackFixture.BASE_URL.length());
    }

    private void upload(User user, String filename) throws Exception {
        byte[] body = new byte[512];
        System.arraycopy(PNG_MAGIC, 0, body, 0, PNG_MAGIC.length);
        for (int i = PNG_MAGIC.length; i < body.length; i++) {
            body[i] = (byte) (filename.hashCode() + i);
        }
        mockMvc.perform(multipart("/api/auth/me/photo")
                .file(new MockMultipartFile("file", filename, "image/png", body))
                .header("Authorization", bearer(user))
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                })).andExpect(status().isOk());
    }
}
