package com.pokeclip.auth.profile;

import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>이름 수정과 사진 수정이 서로를 덮지 않는가.</b>
 *
 * <p>창구가 둘이고 둘 다 같은 회원 행을 고친다. Hibernate 기본값은 <b>매핑된 칸을 전부</b>
 * UPDATE에 싣기 때문에, 두 트랜잭션이 같은 스냅샷을 읽고 각자 커밋하면 <b>나중에 커밋한 쪽이
 * 상대가 방금 넣은 값을 옛 값으로 되돌린다</b>(PR #133 codex P2, 재현함 — 사진 쪽이 방금 바뀐
 * 이름을 되돌렸다). 사진 칸이 지워지는 방향이면 <b>S3 파일이 주인 없이 남는다.</b>
 *
 * <p>{@code User}의 {@code @DynamicUpdate}가 그것을 막는다 — 그 트랜잭션에서 <b>실제로 바뀐
 * 칸만</b> 나간다. <b>그 애너테이션을 지우면 이 검사가 빨간불이어야 한다.</b>
 */
class ProfileWriteInterferenceTest extends PhotoTestSupport {

    private final PlatformTransactionManager txManager;

    ProfileWriteInterferenceTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                                 TokenService tokenService, JdbcTemplate jdbc,
                                 PlatformTransactionManager txManager) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.txManager = txManager;
    }

    @Test
    void 이름_수정과_사진_붙이기가_서로를_안_덮는다() throws Exception {
        User u = newUser();
        String photoKey = "profile-photos/" + u.getId() + "/1";

        TransactionTemplate tx = new TransactionTemplate(txManager);
        // 🔴 둘이 같은 스냅샷을 읽은 뒤에야 각자 쓴다 — 이 걸쇠가 없으면 순서가 갈려
        //    「겹쳤다」를 만들지 못하고 검사가 아무것도 안 잰다.
        CountDownLatch bothLoaded = new CountDownLatch(2);

        Thread renaming = new Thread(() -> tx.executeWithoutResult(s -> {
            User loaded = userRepository.findById(u.getId()).orElseThrow();
            await(bothLoaded);
            loaded.changeName("나중이름", Instant.now());
            userRepository.saveAndFlush(loaded);
        }));

        Thread attaching = new Thread(() -> tx.executeWithoutResult(s -> {
            User loaded = userRepository.findById(u.getId()).orElseThrow();
            await(bothLoaded);
            loaded.attachPhoto(photoKey, Instant.now());
            userRepository.saveAndFlush(loaded);
        }));

        renaming.start();
        attaching.start();
        renaming.join(10_000);
        attaching.join(10_000);

        User after = userRepository.findById(u.getId()).orElseThrow();
        assertThat(after.getName())
                .as("사진 트랜잭션이 옛 스냅샷으로 이름을 되돌리면 안 된다")
                .isEqualTo("나중이름");
        assertThat(after.getProfilePhotoKey())
                .as("이름 트랜잭션이 방금 붙인 사진을 지우면 S3 파일이 주인 없이 남는다")
                .isEqualTo(photoKey);
    }

    private static void await(CountDownLatch latch) {
        latch.countDown();
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
