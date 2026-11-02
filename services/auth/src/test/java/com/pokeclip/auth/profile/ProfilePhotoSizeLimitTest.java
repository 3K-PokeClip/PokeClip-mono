package com.pokeclip.auth.profile;

import com.pokeclip.auth.support.PhotoLocalStackFixture;
import com.pokeclip.auth.support.RealServerTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 크기 상한은 <b>서블릿 층</b>이 자르므로 진짜 HTTP여야 걸린다. MockMvc는 이미 파싱된 요청을 넣어
 * DispatcherServlet이 재파싱을 건너뛴다({@code DispatcherServlet.checkMultipart}) — 그쪽에 쓰면
 * {@code spring.servlet.multipart.max-file-size}를 지워도 초록인 검사가 된다.
 *
 * <p>상한이 서블릿 층에 있는 것 자체가 방어다 — 앱이 바이트를 다 받기 전에 끊는다.
 */
class ProfilePhotoSizeLimitTest extends RealServerTestSupport {

    private static final int LIMIT = 2 * 1024 * 1024;

    private final TestRestTemplate rest;
    private final UserService userService;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;

    ProfilePhotoSizeLimitTest(TestRestTemplate rest, UserService userService, TokenService tokenService,
                              UserRepository userRepository, JdbcTemplate jdbc) {
        this.rest = rest;
        this.userService = userService;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
    }

    @DynamicPropertySource
    static void photoProperties(DynamicPropertyRegistry registry) {
        PhotoLocalStackFixture.register(registry);
    }

    /** ProfileTestSupport와 같은 이유 — refresh_tokens가 users의 자식이라 남기면 남의 정리가 FK로 터진다. */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM refresh_tokens");
        userRepository.deleteAll();
    }

    @Test
    void 이_메가바이트를_넘는_파일은_413이다() {
        ResponseEntity<String> response = put(png(LIMIT + 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody())
                .as("화면이 「줄여서 다시」를 말할 수 있어야 한다")
                .contains("PHOTO_TOO_LARGE");
    }

    /**
     * 경계 바로 아래는 통과해야 한다 — 상한이 너무 낮게 걸리면 정상 사진이 막힌다.
     * 512x512 PNG의 최악이 1,025KB로 실측됐으므로 1.9MB는 정상 범위 안이다.
     */
    @Test
    void 상한_바로_아래는_통과한다() {
        ResponseEntity<String> response = put(png(1_900_000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * 🔴 <b>정확히 상한인 파일이 통과해야 한다.</b> 위 검사가 1.9MB까지만 재던 시절에는
     * <b>정확히 2MB가 413</b>이었다 — {@code max-file-size}는 파일만 재는데
     * {@code max-request-size}가 multipart 경계·파트 헤더까지 더해 먼저 걸렸고,
     * 둘을 같은 값으로 둔 것이 원인이다. 실효 상한이 문서·주석이 말하는 2MB보다 작았다.
     *
     * <p>경계 아래(1.9MB)만 재면 그 구간이 통째로 안 보인다 — <b>「넘으면 막힌다」와
     * 「딱 맞으면 통과한다」는 다른 단언이다.</b>
     */
    @Test
    void 정확히_상한인_파일은_통과한다() {
        ResponseEntity<String> response = put(png(LIMIT));

        assertThat(response.getStatusCode())
                .as("max-request-size가 max-file-size와 같으면 여기서 413이 난다")
                .isEqualTo(HttpStatus.OK);
    }

    private static byte[] png(int size) {
        byte[] body = new byte[size];
        System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, body, 0, 8);
        return body;
    }

    private ResponseEntity<String> put(byte[] photo) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(photo) {
            @Override
            public String getFilename() {
                return "me.png";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.issue(newUser()).accessToken());

        return rest.exchange("/api/auth/me/photo", HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private User newUser() {
        String id = UUID.randomUUID().toString();
        return userService.findOrCreate("sub-" + id, id + "@example.com", "김태현",
                "https://lh3.googleusercontent.com/" + id);
    }
}
