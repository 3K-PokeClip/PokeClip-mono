package com.pokeclip.auth.streamkey;

import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.support.Sha256;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StreamKeyServiceTest extends IntegrationTestSupport {

    private final StreamKeyService streamKeyService;
    private final StreamKeyRepository streamKeyRepository;
    private final UserService userService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;

    StreamKeyServiceTest(StreamKeyService streamKeyService,
                         StreamKeyRepository streamKeyRepository,
                         UserService userService, UserRepository userRepository,
                         JdbcTemplate jdbc) {
        this.streamKeyService = streamKeyService;
        this.streamKeyRepository = streamKeyRepository;
        this.userService = userService;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
    }

    /** FK 함정: 자식 행을 남기면 다른 테스트의 users 정리를 막는다. */
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
        jdbc.update("DELETE FROM stream_keys");
        jdbc.update("DELETE FROM secrets");
    }

    @Test
    void 처음_부르면_키를_만든다() {
        StreamKeyMaterial material = streamKeyService.ensureKey(newUser());

        assertThat(material.streamToken()).hasSize(26);
        assertThat(material.passphrase()).hasSize(32);
        assertThat(streamKeyRepository.count()).isEqualTo(1);
    }

    /** POK-67: 이미 있으면 새로 만들지 않고 기존 것을 반환한다. */
    @Test
    void 다시_불러도_같은_키를_돌려준다() {
        Long userId = newUser();

        StreamKeyMaterial first = streamKeyService.ensureKey(userId);
        StreamKeyMaterial second = streamKeyService.ensureKey(userId);

        assertThat(second).isEqualTo(first);
        assertThat(streamKeyRepository.count()).isEqualTo(1);
    }

    /**
     * POK-67의 핵심 인수 기준. 부분 유니크 인덱스가 막고, 진 쪽은
     * DataIntegrityViolationException을 "남이 먼저 만들었다"로 읽는다
     * (UserService.findOrCreate와 같은 방식).
     */
    @Test
    void 동시에_여러_번_요청해도_키가_하나만_생긴다() throws Exception {
        Long userId = newUser();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<StreamKeyMaterial>> jobs = IntStream.range(0, threads)
                    .<Callable<StreamKeyMaterial>>mapToObj(i -> () -> {
                        start.await();
                        return streamKeyService.ensureKey(userId);
                    })
                    .toList();

            List<Future<StreamKeyMaterial>> futures = jobs.stream().map(pool::submit).toList();
            start.countDown();

            List<StreamKeyMaterial> results = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            assertThat(streamKeyRepository.count())
                    .as("부분 유니크 인덱스가 두 번째 삽입을 막지 못했다")
                    .isEqualTo(1);
            assertThat(results)
                    .as("스레드마다 다른 키를 받았다")
                    .containsOnly(results.get(0));
        }
    }

    /** POK-67: streamid 해시만 DB에 둔다. 원본은 안 둔다. */
    @Test
    void 표_어디에도_토큰_원문이_없다() {
        StreamKeyMaterial material = streamKeyService.ensureKey(newUser());

        String dump = jdbc.queryForObject(
                // COALESCE가 없으면 표가 빌 때 null이 오고, AssertJ가
                // "Expecting actual not to be null"로 터진다 — "원문이 남았다"와
                // 전혀 다른 메시지라 원인 추적이 한 단계 늘어난다.
                "SELECT COALESCE(string_agg(t::text, ' '), '') FROM stream_keys t", String.class);

        assertThat(dump).doesNotContain(material.streamToken());
        assertThat(dump).doesNotContain(material.passphrase());
    }

    @Test
    void 해시로_키를_찾을_수_있다() {
        Long userId = newUser();
        StreamKeyMaterial material = streamKeyService.ensureKey(userId);

        assertThat(streamKeyRepository.findByStreamidHash(Sha256.hex(material.streamToken())))
                .isPresent()
                .get()
                .extracting(StreamKey::getUserId)
                .isEqualTo(userId);
    }

    @Test
    void 키가_없으면_material도_비어_있다() {
        assertThat(streamKeyService.findMaterial(newUser())).isEmpty();
    }

    private Long newUser() {
        User user = userService.findOrCreate(
                "sub-" + UUID.randomUUID(), "a@example.com", "김태현", null);
        return user.getId();
    }
}
