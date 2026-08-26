package com.pokeclip.auth.profile;

import com.jayway.jsonpath.JsonPath;
import com.pokeclip.auth.support.PhotoLocalStackFixture;
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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「사진을 바꾼 직후 새 그림이 바로 보이는가」와 「같은 사진을 계속 다시 받지 않는가」는
 * <b>눈에 안 보이는 실패</b>다 — 안 재면 모른다. 사용자가 계획 검토에서 짚은 자리다.
 *
 * <p>회원 정보는 60초마다·탭에 돌아올 때마다 다시 불린다. 부를 때마다 표를 새로 만들면 주소가
 * 매번 달라져 같은 그림을 계속 다시 받는다. {@link PhotoToken}이 만료를 10분 경계에 맞추는 것이
 * 그 해답이고, <b>이 클래스가 그 성질을 실제로 재는 유일한 자리다.</b>
 *
 * <p>🔴 <b>창구만으로는 그 성질을 못 잰다.</b> 회원 정보를 연달아 두 번 부르면 두 호출의
 * {@code Instant.now()}가 같은 초에 떨어지므로, 만료를 「지금 + 20분」으로 바꿔 놔도 두 주소가
 * 같게 나온다(주입해서 확인했다). 그래서 {@link PhotoUrls}를 직접 부르는 갈래를 함께 둔다 —
 * 같은 10분 창의 <b>서로 다른 두 시각</b>을 넣어야 경계 맞춤이 재어진다.
 */
class PhotoUrlStabilityTest extends PhotoTestSupport {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final PhotoUrls photoUrls;
    private final PhotoAttacher attacher;

    PhotoUrlStabilityTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                          TokenService tokenService, JdbcTemplate jdbc, PhotoUrls photoUrls,
                          PhotoAttacher attacher) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.photoUrls = photoUrls;
        this.attacher = attacher;
    }

    @Test
    void 사진을_안_올린_회원은_구글_주소_그대로다() throws Exception {
        User u = newUser();
        assertThat(u.getProfileImageUrl()).as("가입 때 받은 구글 주소").isNotBlank();

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(u.getProfileImageUrl()));
    }

    @Test
    void 사진을_올리면_우리_주소로_바뀐다() throws Exception {
        User u = newUser();
        String google = u.getProfileImageUrl();
        upload(u, png("me.png", 1));

        String url = photoUrlFromMe(u);

        assertThat(url)
                .as("창고를 공개하지 않고 우리가 내보낸다 — 구글 주소가 그대로 나가면 안 된다")
                .isNotEqualTo(google)
                .startsWith(PhotoLocalStackFixture.BASE_URL + "/api/profile-photos/" + u.getId() + "?token=");
    }

    /**
     * 회원 정보는 60초마다·탭 복귀마다 다시 불린다. 매번 주소가 바뀌면 같은 그림을 계속 다시 받는다.
     *
     * <p>같은 창의 두 끝(600초·1199초)을 넣는다 — 599초 떨어져 있는데도 글자까지 같아야 한다.
     * 그리고 창을 넘으면(1200초) 달라져야 한다. 뒤 단언이 없으면 「항상 같은 값을 준다」로도
     * 앞 단언이 참이 되어, 사진을 바꿔도 안 바뀌는 구현이 통과한다.
     */
    @Test
    void 같은_십분_안에서_두_번_부르면_주소가_글자까지_같다() throws Exception {
        User u = newUser();
        upload(u, png("me.png", 1));
        User withPhoto = userRepository.findById(u.getId()).orElseThrow();

        String early = photoUrls.of(withPhoto, Instant.ofEpochSecond(600));
        String late = photoUrls.of(withPhoto, Instant.ofEpochSecond(1_199));
        String nextSlot = photoUrls.of(withPhoto, Instant.ofEpochSecond(1_200));

        assertThat(early).as("같은 10분 창이면 599초가 벌어져도 같은 글자다").isEqualTo(late);
        assertThat(nextSlot).as("창을 넘으면 달라진다 — 상수를 주는 구현을 가른다").isNotEqualTo(early);

        // 창구로도 같은 것이 나가는지 본다. 이쪽만으로는 위 성질을 못 재지만(두 호출이 같은 초다),
        // 브라우저가 실제로 받는 것은 이쪽이라 둘 다 남긴다.
        // 🔴 먼저 비어 있지 않은 것을 못박는다 — 둘 다 null이면 아래 비교가 저절로 참이 된다
        // (창구가 사진 주소를 아예 안 싣게 만드는 주입에서 실제로 초록이었다).
        String first = photoUrlFromMe(u);
        assertThat(first).isNotNull();
        assertThat(photoUrlFromMe(u)).isEqualTo(first);
    }

    /**
     * 바꾼 직후 주소가 달라져야 브라우저가 새 그림을 받는다.
     *
     * <p><b>같은 회원인지 확인한다</b> — 회원이 달라서 주소가 달라진 것이면 아무것도 안 잰 것이다.
     * 갈리는 칸이 표의 {@code version}(사진 수정일시)인지도 따로 본다.
     *
     */
    @Test
    void 사진을_바꾸면_주소가_즉시_달라지고_새_그림이_나온다() throws Exception {
        User u = newUser();
        byte[] first = upload(u, png("first.png", 1));
        String before = photoUrlFromMe(u);

        byte[] second = upload(u, png("second.png", 2));
        String after = photoUrlFromMe(u);

        assertThat(second).as("두 그림이 실제로 달라야 아래 비교가 뜻을 가진다").isNotEqualTo(first);
        assertThat(pathOf(after))
                .as("같은 회원의 주소끼리 비교하고 있는가 — 회원이 달라서 달라진 것이면 아무것도 안 잰 것이다")
                .isEqualTo(pathOf(before))
                .isEqualTo("/api/profile-photos/" + u.getId());
        assertThat(after).as("바꾼 직후 주소가 달라져야 브라우저가 새 그림을 받는다").isNotEqualTo(before);
        assertThat(versionOf(after))
                .as("갈리는 칸은 사진 수정일시여야 한다 — 만료가 우연히 창을 넘어 갈린 것일 수 있다")
                .isNotEqualTo(versionOf(before));

        assertThat(fetch(after))
                .as("새 주소로 꺼낸 것이 방금 올린 그림이어야 한다")
                .isEqualTo(second);
    }

    /**
     * 🔴 <b>같은 초에 두 번 올려도 주소가 달라져야 한다.</b> version이 수정일시의 <b>초</b>였을 때
     * 감사자가 연달아 두 번 올리기를 10회 해서 <b>10/10 같은 초·10/10 같은 주소</b>를 봤다
     * (올리기 왕복 7ms). 사람이 0.3초 간격으로 두 번 누르면 약 70%다.
     *
     * <p><b>서버는 새 그림을 내보낸다 — 틀리는 것은 브라우저다.</b> 주소가 글자까지 같은데
     * {@code Cache-Control: private, max-age=600}이 붙어 있어 <b>캐시된 옛 그림을 최대 10분</b> 본다.
     * PRD 성공 기준(「사진을 바꾼 직후 새 그림이 나온다」)이 깨지는 자리다.
     *
     * <p>위 검사는 초가 넘어갈 때까지 기다려서 이 결함을 <b>통째로 피해 가고 있었다.</b>
     * 여기서는 기다리지 않고, <b>두 번이 실제로 같은 초에 떨어진 것을 확인한 뒤</b> 잰다 —
     * 초가 갈렸으면 재려던 상황이 아니라 아무것도 안 잰 것이다.
     */
    @Test
    void 같은_초에_두_번_올려도_주소가_달라진다() throws Exception {
        User u = newUser();
        Instant firstAt;
        Instant secondAt;
        String before;
        String after;
        byte[] second;
        int tries = 0;
        do {
            upload(u, png("first.png", 1));
            firstAt = photoUpdatedAt(u);
            before = photoUrlFromMe(u);
            second = upload(u, png("second.png", 2));
            secondAt = photoUpdatedAt(u);
            after = photoUrlFromMe(u);
            tries++;
        } while (firstAt.getEpochSecond() != secondAt.getEpochSecond() && tries < 5);

        assertThat(firstAt.getEpochSecond())
                .as("두 번이 같은 초에 떨어져야 이 검사가 뜻을 가진다 — 실측 10/10이라 대개 한 바퀴다")
                .isEqualTo(secondAt.getEpochSecond());
        assertThat(after).as("같은 초여도 주소가 달라져야 브라우저가 새 그림을 받는다").isNotEqualTo(before);
        assertThat(versionOf(after))
                .as("갈리는 칸은 사진 수정일시여야 한다")
                .isNotEqualTo(versionOf(before));
        assertThat(fetch(after)).as("새 주소로 꺼낸 것이 두 번째 그림이어야 한다").isEqualTo(second);
    }

    /**
     * 🔴 <b>같은 초로는 부족하다 — 같은 밀리초에도 갈려야 한다</b>(PR #127 codex P2).
     * 탭 둘에서 동시에 올리면 두 저장이 같은 밀리초에 떨어질 수 있고, 그때 밀리초로 자른
     * 버전은 <b>글자까지 같은 주소</b>를 만든다. 그러면 먼저 받은 주소를 캐시한 브라우저가
     * 두 번째 사진을 <b>10분 동안 못 본다</b> — 위 검사가 막으려던 바로 그 실패다.
     *
     * <p>업로드 왕복이 약 7ms라 실기동으로는 같은 밀리초를 만들기 어렵다. 그래서 표를 거치지 않고
     * <b>주소를 짓는 쪽을 직접 부른다</b> — 재는 것은 「같은 밀리초, 다른 마이크로초가 다른 주소를
     * 내는가」이지 업로드 경로가 아니다. 그 마이크로초가 DB를 왕복해도 살아 있는지는 아래에서 따로 잰다.
     */
    @Test
    void 같은_밀리초_안에서_바꿔도_주소가_달라진다() throws Exception {
        User u = newUser();
        upload(u, png("first.png", 1));
        User user = userRepository.findById(u.getId()).orElseThrow();
        String key = "profile-photos/" + u.getId();

        Instant base = photoUpdatedAt(u);
        // 밀리초 경계로 내린 뒤 1마이크로초만 민다.
        Instant earlier = Instant.ofEpochSecond(base.getEpochSecond(),
                (base.getNano() / 1_000_000) * 1_000_000L);
        Instant later = earlier.plusNanos(1_000);
        assertThat(earlier.toEpochMilli())
                .as("두 시각이 같은 밀리초여야 이 검사가 뜻을 가진다 — 갈렸으면 아무것도 안 잰 것이다")
                .isEqualTo(later.toEpochMilli());

        // 부르는 시각은 같게 둔다 — 갈려야 하는 것은 만료가 아니라 사진 버전이다.
        Instant now = Instant.now();
        user.attachPhoto(key, earlier);
        String first = photoUrls.of(user, now);
        user.attachPhoto(key, later);
        String second = photoUrls.of(user, now);

        assertThat(versionOf(second))
                .as("밀리초로 자르면 여기서 같아진다")
                .isNotEqualTo(versionOf(first));
        assertThat(second).isNotEqualTo(first);
    }

    /**
     * 🔴 <b>위 검사는 이것이 참일 때만 뜻을 가진다.</b> 마이크로초가 표를 왕복하며 잘리면
     * 위는 메모리에서만 참이고 실제 주소는 여전히 겹친다. {@code TIMESTAMPTZ}가 마이크로초까지
     * 저장한다는 것에 기대고 있으므로, 컬럼 타입을 바꾸는 날 여기가 빨간불이 되어야 한다.
     */
    @Test
    void 사진_시각의_마이크로초가_표를_왕복해도_살아_있다() {
        User u = newUser();
        // 마이크로초 자리가 0이 아닌 값을 일부러 만든다 — 0이면 「잘려도 같다」라 아무것도 안 잰다.
        Instant stamped = Instant.ofEpochSecond(Instant.now().getEpochSecond(), 123_456_000L);
        assertThat(stamped.getNano() % 1_000_000).as("밀리초 아래 자리가 있어야 잰다").isNotZero();

        attacher.attach(u.getId(), stamped);   // 실제 표 갱신 경로. 자체 트랜잭션이라 여기서 커밋된다

        assertThat(photoUpdatedAt(u))
                .as("마이크로초가 잘리면 같은 밀리초의 두 업로드가 다시 겹친다")
                .isEqualTo(stamped);
    }

    /** 그 주소를 실제로 불러 200이 나오는지까지 본다 — 주소만 맞고 안 열리면 화면은 깨진 그림이다. */
    @Test
    void 회원_정보가_준_주소로_바로_그림을_꺼낼_수_있다() throws Exception {
        User u = newUser();
        byte[] uploaded = upload(u, png("me.png", 1));

        assertThat(fetch(photoUrlFromMe(u)))
                .as("올린 바이트가 그대로 나와야 한다 — 길이만 재면 빈 배열끼리도 참이 된다")
                .isNotEmpty()
                .isEqualTo(uploaded);
    }

    /**
     * 🔴 태스크 5가 미뤄 둔 단언이 여기서 선다. 그때는 이 칸이 null이라
     * {@code jsonPath(...).exists()}가 키가 본문에 있어도 빨간불이었다(critic 1회차 실측).
     */
    @Test
    void 사진을_올린_직후_회원_정보의_사진_주소가_비어_있지_않다() throws Exception {
        User u = newUser();

        putPhoto(u, png("me.png", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").exists())
                .andExpect(jsonPath("$.profileImageUrl",
                        startsWith(PhotoLocalStackFixture.BASE_URL + "/api/profile-photos/" + u.getId() + "?token=")));
    }

    private String photoUrlFromMe(User u) throws Exception {
        String body = mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(u)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.profileImageUrl");
    }

    /** 절대 주소를 그대로 던진다 — 앞부분까지 살아 있어야 화면이 실제로 그림을 받는다. */
    private byte[] fetch(String absoluteUrl) throws Exception {
        return mockMvc.perform(get(URI.create(absoluteUrl)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private Instant photoUpdatedAt(User u) {
        return userRepository.findById(u.getId()).orElseThrow().getProfilePhotoUpdatedAt();
    }

    private static String pathOf(String url) {
        return URI.create(url).getPath();
    }

    /** 표는 {@code userId.exp.version.signature}다. */
    private static String versionOf(String url) {
        String token = URI.create(url).getQuery().substring("token=".length());
        return token.split("\\.", -1)[2];
    }

    /** 바이트마다 다른 값을 넣는다 — 0으로만 채우면 「엉뚱한 파일을 내보냈다」를 못 잡는다. */
    private static MockMultipartFile png(String filename, int seed) {
        byte[] body = new byte[512];
        System.arraycopy(PNG_MAGIC, 0, body, 0, PNG_MAGIC.length);
        for (int i = PNG_MAGIC.length; i < body.length; i++) {
            body[i] = (byte) (seed * 31 + i);
        }
        return new MockMultipartFile("file", filename, "image/png", body);
    }

    private org.springframework.test.web.servlet.ResultActions putPhoto(User u, MockMultipartFile file)
            throws Exception {
        return mockMvc.perform(multipart("/api/auth/me/photo").file(file)
                .header("Authorization", bearer(u))
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                }));
    }

    private byte[] upload(User u, MockMultipartFile file) throws Exception {
        putPhoto(u, file).andExpect(status().isOk());
        return file.getBytes();
    }
}
