package com.pokeclip.auth.profile;

import com.pokeclip.auth.profile.api.ProfilePhotoController;
import com.pokeclip.auth.support.PhotoLocalStackFixture;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사진 내보내기 종단. <b>거절 넷이 갈리면 안 된다</b> — 표가 틀렸든 만료됐든 그런 사진이 없든
 * 그런 회원이 없든 전부 404다. 갈라 주면 「그 회원이 사진을 올렸는가」가 표 없이도 새어 나가고,
 * 사진을 비공개로 둔 이유(편집자 프라이버시)가 그 자리에서 무너진다.
 *
 * <p>표는 여기서 <b>다시 만든다</b> — 서비스에게 「표 하나 만들어 줘」라고 묻지 않는다.
 * 같은 함수를 쓰면 서명 규약이 바뀔 때 둘이 함께 움직여 아무것도 안 재게 된다
 * (PhotoLocalStackFixture가 파일 이름을 다시 짓는 것과 같은 이유).
 */
class ProfilePhotoReadTest extends PhotoTestSupport {

    private final ProfilePhotoController controller;

    ProfilePhotoReadTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                         TokenService tokenService, JdbcTemplate jdbc, ProfilePhotoController controller) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.controller = controller;
    }

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    void 올린_사진을_표로_꺼낼_수_있다() throws Exception {
        User u = newUser();
        byte[] uploaded = upload(u, png("me.png", "image/png"));

        byte[] served = mockMvc.perform(get(photoUrl(u.getId(), token(u.getId()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(served)
                .as("올린 바이트가 그대로 나와야 한다 — 길이만 재면 빈 배열끼리도 참이 된다")
                .isNotEmpty()
                .isEqualTo(uploaded);
    }

    /** 올린 쪽이 밝힌 이름표를 그대로 실으면 그림이 아닌 것이 보는 사람의 브라우저에서 실행된다. */
    @Test
    void 형식은_우리가_판정한_값으로_나간다() throws Exception {
        User u = newUser();
        // 이름도 밝힌 형식도 gif인데 내용은 PNG다. 나가는 것은 우리가 판정한 image/png여야 한다.
        upload(u, png("me.gif", "image/gif"));

        mockMvc.perform(get(photoUrl(u.getId(), token(u.getId()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
    }

    /**
     * 🔴 <b>창구를 직접 부르는 갈래가 반드시 있어야 한다.</b> 스프링 시큐리티가 기본으로 같은 헤더를
     * 붙이므로, HTTP로만 재면 <b>우리 줄을 지워도 초록이다</b>(주입해서 확인했다). 그러면 이 검사는
     * 시큐리티 기본값을 재는 것이지 사진 창구를 재는 것이 아니다.
     *
     * <p>둘 다 남기는 이유: 브라우저가 실제로 받는 것은 HTTP 쪽이 재고(그것이 요구사항이다),
     * 우리 줄이 살아 있는지는 직접 호출이 잰다. 겹치는 방어 둘이라 <b>한쪽이 초록인 것을 근거로
     * 나머지를 지우면 안 된다</b> — 시큐리티 기본값이 꺼지는 날 남는 것은 우리 줄뿐이다.
     */
    @Test
    void 내용_추측_금지_표시가_붙는다() throws Exception {
        User u = newUser();
        upload(u, png("me.png", "image/png"));

        mockMvc.perform(get(photoUrl(u.getId(), token(u.getId()))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        assertThat(controller.photo(u.getId(), token(u.getId())).getHeaders()
                .getFirst("X-Content-Type-Options"))
                .as("창구가 스스로 붙이지 않으면 시큐리티 기본값에 얹혀 있는 것이다")
                .isEqualTo("nosniff");
    }

    @Test
    void 캐시_지시가_private다() throws Exception {
        User u = newUser();
        upload(u, png("me.png", "image/png"));

        String cacheControl = mockMvc.perform(get(photoUrl(u.getId(), token(u.getId()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Cache-Control");

        assertThat(cacheControl).isNotNull();
        assertThat(cacheControl)
                .as("표 수명과 맞춘다")
                .contains("max-age=" + PhotoToken.SLOT_SECONDS);
        assertThat(cacheControl)
                .as("중간 캐시가 남의 사진을 들고 있으면 안 된다")
                .contains("private")
                .doesNotContain("public");
    }

    @Test
    void 표가_없으면_404다() throws Exception {
        User u = newUser();
        upload(u, png("me.png", "image/png"));

        mockMvc.perform(get("/api/profile-photos/" + u.getId()))
                .andExpect(status().isNotFound());
    }

    /**
     * 서명의 <b>첫 글자</b>를 뒤집는다. 마지막 글자는 base64url에서 유효 비트가 4개뿐이라
     * 뒤집어도 같은 32바이트로 디코딩되는 경우가 있다(auth/CLAUDE.md, 923d424).
     */
    @Test
    void 서명을_고친_표는_404다() throws Exception {
        User u = newUser();
        upload(u, png("me.png", "image/png"));

        String valid = token(u.getId());
        int lastDot = valid.lastIndexOf('.');
        char first = valid.charAt(lastDot + 1);
        String tampered = valid.substring(0, lastDot + 1)
                + (first == 'A' ? 'B' : 'A')
                + valid.substring(lastDot + 2);
        assertThat(tampered).as("실제로 달라졌는가").isNotEqualTo(valid);

        mockMvc.perform(get(photoUrl(u.getId(), tampered)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 만료된_표는_404다() throws Exception {
        User u = newUser();
        upload(u, png("me.png", "image/png"));

        String expired = PhotoToken.issue(PhotoLocalStackFixture.TOKEN_SECRET, u.getId(), 0,
                Instant.ofEpochSecond(0));

        mockMvc.perform(get(photoUrl(u.getId(), expired)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 남의_번호_주소에_내_표를_쓰면_404다() throws Exception {
        User mine = newUser();
        User other = newUser();
        upload(mine, png("me.png", "image/png"));
        upload(other, png("other.png", "image/png"));

        mockMvc.perform(get(photoUrl(other.getId(), token(mine.getId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void 사진을_안_올린_회원은_404다() throws Exception {
        User u = newUser();

        mockMvc.perform(get(photoUrl(u.getId(), token(u.getId()))))
                .andExpect(status().isNotFound());
    }

    /** 위 넷과 같은 404다 — 어느 쪽인지 알려주지 않는다. */
    @Test
    void 없는_회원_번호도_404다() throws Exception {
        long absent = 999_999_999L;

        mockMvc.perform(get(photoUrl(absent, token(absent))))
                .andExpect(status().isNotFound());
    }

    /** 구조로 갈린다는 근거 — 로그인 토큰은 칸 수부터 다르고 서명키도 다르다. */
    @Test
    void 로그인_토큰을_표_자리에_넣어도_404다() throws Exception {
        User u = newUser();
        upload(u, png("me.png", "image/png"));

        String accessToken = bearer(u).substring("Bearer ".length());

        mockMvc.perform(get(photoUrl(u.getId(), accessToken)))
                .andExpect(status().isNotFound());
    }

    /**
     * 🔴 <b>「GET만 연다」가 주석에만 있고 아무 데도 안 재어져 있었다</b>(감사 2라운드).
     * {@code SecurityConfig}에서 {@code HttpMethod.GET}을 지워도 68건이 전부 초록이었다 —
     * 오늘 안 뚫리는 이유는 이 뿌리에 GET 말고 매핑이 없어서일 뿐이다.
     * <b>나중에 이 뿌리에 POST가 생기면 그날 조용히 열린다.</b>
     *
     * <p>표까지 옳게 실어 보낸다 — 표가 맞아도 GET이 아니면 안 열린다는 것이 재려는 것이다.
     *
     * <p><b>HEAD도 401이다</b>(여기서 안 잰다). GET 매처가 HEAD를 안 덮기 때문인데,
     * {@code <img>}는 HEAD를 안 쓰므로 화면이 안 깨지고 <b>어느 쪽이든 정보가 안 샌다</b> —
     * 401은 사진이 있는지 없는지를 말해 주지 않는다. <b>결함이 아니라 지금 상태다</b>
     * (감사 2라운드에서 판단하고 그대로 뒀다). 단언으로 못박지 않은 것은, HEAD를 여는 날이
     * 오면 그것이 고침이지 회귀가 아니기 때문이다.
     */
    @Test
    void 사진_경로는_GET만_열려_있다() throws Exception {
        User u = newUser();
        upload(u, png("me.png", "image/png"));
        String url = photoUrl(u.getId(), token(u.getId()));

        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            int status = mockMvc.perform(request(method, url)).andReturn().getResponse().getStatus();
            assertThat(status)
                    .as("%s는 토큰을 요구해야 한다 — 이 뿌리에 매핑이 없어서 안 뚫리는 것과 다르다", method)
                    .isEqualTo(401);
        }
    }

    /**
     * 🔴 <b>버전을 아무 값이나 넣으면 안 된다</b> — 그 값이 <b>파일 자리를 정하기 때문이다</b>
     * (자리 둘을 번갈아 쓴다. {@link PhotoStorage#keyOf}). 예전에는 캐시를 비우는 용도뿐이라
     * 0을 박아 뒀는데, 그러면 사진이 반대 자리에 있을 때 404가 난다.
     *
     * <p>서명 규약은 여전히 여기서 <b>다시</b> 만든다 — 그것이 이 검사가 지키려는 것이고,
     * 버전 값을 표에서 읽는 것은 그 취지와 무관하다.
     */
    private String token(long userId) {
        long version = userRepository.findById(userId)
                .map(User::getProfilePhotoUpdatedAt)
                .map(PhotoStorage::versionOf)
                .orElse(0L);
        return PhotoToken.issue(PhotoLocalStackFixture.TOKEN_SECRET, userId, version, Instant.now());
    }

    private static String photoUrl(long userId, String token) {
        return "/api/profile-photos/" + userId + "?token=" + token;
    }

    /** 바이트마다 다른 값을 넣는다 — 0으로만 채우면 「엉뚱한 파일을 내보냈다」를 못 잡는다. */
    private static MockMultipartFile png(String filename, String declaredType) {
        byte[] body = new byte[512];
        System.arraycopy(PNG_MAGIC, 0, body, 0, PNG_MAGIC.length);
        for (int i = PNG_MAGIC.length; i < body.length; i++) {
            body[i] = (byte) (filename.hashCode() + i);
        }
        return new MockMultipartFile("file", filename, declaredType, body);
    }

    private byte[] upload(User u, MockMultipartFile file) throws Exception {
        mockMvc.perform(multipart("/api/auth/me/photo").file(file)
                .header("Authorization", bearer(u))
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                })).andExpect(status().isOk());
        return file.getBytes();
    }
}
