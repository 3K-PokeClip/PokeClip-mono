package com.pokeclip.auth.profile;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>사진을 바꾸다 표 갱신이 실패하면 무엇이 보이나.</b>
 *
 * <p>창고에 먼저 쓰고 표를 나중에 고치는 순서라, 표 쪽이 실패하면 <b>파일만 새것이 된다.</b>
 * 그때 옛 주소는 안 깨진다 — 사진 표는 버전 칸의 모양만 보고 값은 안 보기 때문이다.
 * 그래서 <b>「실패했다」는 응답을 받은 사용자의 화면에 새 사진이 뜬다.</b>
 *
 * <p>PR #127에서 codex가 짚었고 그때는 「고아 파일 청소가 딸려온다」를 이유로 미뤘는데,
 * 청소 없이 닫는 길이 있었다 — 파일 이름을 <b>버전의 홀짝으로 두 자리에 번갈아</b> 쓰는 것이다.
 * 자리가 둘뿐이라 주인 없는 파일이 회원당 최대 하나이고 <b>다음 업로드가 그 자리를 덮어쓴다.</b>
 *
 * <p>🔴 <b>자리를 표에서 읽지 않는다</b> — 주소에 실린 버전에서 곧바로 얻는다.
 * 표를 읽으면 「사진을 올렸는가」로 걸리는 시간이 갈려 존재가 새기 때문이다
 * ({@link ProfilePhotoService#read} 주석). 그 방어를 지키면서 이 결함을 닫는 것이 이 설계의 요점이다.
 */
class PhotoReplacementFailureTest extends PhotoTestSupport {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final PhotoStorage storage;
    private final PhotoProperties properties;
    private final UserRepository users;

    PhotoReplacementFailureTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                                TokenService tokenService, JdbcTemplate jdbc,
                                PhotoStorage storage, PhotoProperties properties) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.storage = storage;
        this.properties = properties;
        this.users = userRepository;
    }

    /** 표 갱신만 실패시킨다 — 창고 호출은 진짜로 일어나야 이 검사가 뜻을 가진다. */
    private ProfilePhotoService serviceWithFailingTable() {
        PhotoAttacher throwing = new PhotoAttacher(users) {
            @Override
            User attach(long userId, long version) {
                throw new IllegalStateException("표 갱신 실패를 흉내낸다");
            }
        };
        return new ProfilePhotoService(storage, throwing, properties);
    }

    @Test
    void 표_갱신이_실패하면_옛_주소는_옛_그림을_계속_준다() throws Exception {
        User u = newUser();
        byte[] first = upload(u, png("first.png", 1));
        String urlBeforeFailure = photoUrlFromMe(u);
        assertThat(fetch(urlBeforeFailure))
                .as("준비: 옛 주소가 첫 그림을 준다")
                .isEqualTo(first);

        assertThatThrownBy(() -> serviceWithFailingTable().upload(u.getId(), png("second.png", 2)))
                .as("사용자는 실패를 받는다")
                .isInstanceOf(IllegalStateException.class);

        assertThat(fetch(urlBeforeFailure))
                .as("🔴 실패했다는데 화면에 새 사진이 뜨면 안 된다 — 파일 자리를 갈라야 막힌다")
                .isEqualTo(first);
    }

    /** 표가 안 바뀌었으므로 회원 정보가 주는 주소도 그대로여야 한다 — 옛 그림을 가리킨 채로. */
    @Test
    void 표_갱신이_실패하면_회원_정보의_주소도_안_바뀐다() throws Exception {
        User u = newUser();
        byte[] first = upload(u, png("first.png", 1));
        String before = photoUrlFromMe(u);

        assertThatThrownBy(() -> serviceWithFailingTable().upload(u.getId(), png("second.png", 2)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(photoUrlFromMe(u)).as("표가 안 바뀌었으니 주소도 같다").isEqualTo(before);
        assertThat(fetch(photoUrlFromMe(u))).as("그 주소가 주는 것도 첫 그림이다").isEqualTo(first);
    }

    /** 실패 뒤 정상 업로드가 이어지면 그때는 새 그림이 보여야 한다 — 자리를 갈랐다고 막히면 안 된다. */
    @Test
    void 실패한_뒤에_다시_올리면_새_그림이_보인다() throws Exception {
        User u = newUser();
        upload(u, png("first.png", 1));

        assertThatThrownBy(() -> serviceWithFailingTable().upload(u.getId(), png("lost.png", 2)))
                .isInstanceOf(IllegalStateException.class);

        byte[] third = upload(u, png("third.png", 3));
        assertThat(fetch(photoUrlFromMe(u)))
                .as("실패가 다음 업로드를 막으면 안 된다")
                .isEqualTo(third);
    }

    private String photoUrlFromMe(User u) throws Exception {
        String body = mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(u)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.profileImageUrl");
    }

    private byte[] fetch(String absoluteUrl) throws Exception {
        return mockMvc.perform(get(URI.create(absoluteUrl)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static MockMultipartFile png(String filename, int seed) {
        byte[] body = new byte[512];
        System.arraycopy(PNG_MAGIC, 0, body, 0, PNG_MAGIC.length);
        for (int i = PNG_MAGIC.length; i < body.length; i++) {
            body[i] = (byte) (seed * 31 + i);
        }
        return new MockMultipartFile("file", filename, "image/png", body);
    }

    private byte[] upload(User u, MockMultipartFile file) throws Exception {
        mockMvc.perform(multipart("/api/auth/me/photo").file(file)
                        .header("Authorization", bearer(u))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk());
        return file.getBytes();
    }
}
