package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 통합 시험의 공용 베이스. {@code ProfileTestSupport}·{@code DelegationTestSupport}와 같은 모양이다.
 *
 * <p>{@code IntegrationTestSupport}에는 {@code @AutoConfigureMockMvc}가 없다. 여기서 붙이지 않으면
 * 하위의 생성자 주입이 "No qualifying bean of type MockMvc"로 실패해 이 계층이 한 건도 안 돈다.
 */
@AutoConfigureMockMvc
public abstract class WithdrawalTestSupport extends IntegrationTestSupport {

    protected final MockMvc mockMvc;
    protected final UserService userService;
    protected final JdbcTemplate jdbc;
    /** 하위가 refresh 원문을 직접 쥐어야 한다 — 「갱신 표가 죽었다」를 그 표로 다시 불러 재는 갈래가 있다. */
    protected final TokenService tokenService;

    protected WithdrawalTestSupport(MockMvc mockMvc, UserService userService,
                                    TokenService tokenService, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.userService = userService;
        this.tokenService = tokenService;
        this.jdbc = jdbc;
    }

    /** 이 계층이 심은 회원 번호. 아래 거두기가 이것으로만 지운다. */
    private final List<Long> 심은_회원 = new ArrayList<>();

    /**
     * 🔴 <b>이름이 아니라 번호로 지운다.</b> 태스크 1에서 데인 자리다 — {@code withdraw}가
     * {@code google_sub}·{@code email}을 바꾸므로 그 값으로 지우는 정리는 <b>탈퇴시킨 행을 하나도
     * 못 고른다.</b> 번호는 안 바뀐다.
     *
     * <p>{@code userRepository.deleteAll()}을 쓰지 않는 이유는 그것이 <b>다른 클래스가 남긴 행까지</b>
     * 지우려 들어 자식 표의 외래키에 걸리기 때문이다(services/CLAUDE.md 「자식 테이블 행을 남기는 테스트」).
     *
     * <p>자식을 먼저 지운다 — {@code refresh_tokens}({@link #bearer}가 만든다) ·
     * {@code pairing_codes}·{@code stream_keys}(스트림키 갈래가 만든다) ·
     * {@code chzzk_channel_links}·{@code youtube_channel_links}(연동 갈래가 만든다).
     * <b>이 목록은 이 계층이 실제로 만드는 것까지다</b> — 초대 표를 심는 시험을 더하는 태스크는
     * 자기 표를 여기 같이 더한다. 안 더하면 다음 클래스의 부모 정리가 외래키로 막힌다.
     *
     * <p>🔴 <b>{@code secrets}를 그것을 가리키는 표보다 <u>먼저</u> 지운다.</b> 그 표에는 회원 칸이 없어
     * <b>스트림키·연동 행을 통해서만</b> 그 회원 몫을 고를 수 있다 — 순서를 뒤집으면 고를 열쇠가 먼저
     * 사라져 비밀값이 <b>주인 없이</b> 남는다. 탈퇴는 아직 스트림키 비밀값을 안 지우므로(태스크 7) 이 계층이
     * 매 시험마다 하나씩 남기고, 그것이 뒤 시험의 「주인 없는 비밀값 0건」을 오염시킨다.
     *
     * <p>연동 쪽 비밀값은 해제 정리가 <b>대개</b> 먼저 지운다. 그래도 여기서 다시 지우는 이유는 정리가
     * 안 도는 갈래(연동만 심고 탈퇴를 안 하는 시험)가 있어서다 — 없는 행을 지우는 것은 0행일 뿐 무해하다.
     */
    @AfterEach
    void 심은_행을_거둔다() {
        for (Long id : 심은_회원) {
            jdbc.update("DELETE FROM secrets WHERE ref IN "
                    + "(SELECT passphrase_ref FROM stream_keys WHERE user_id = ?)", id);
            jdbc.update("DELETE FROM secrets WHERE ref IN "
                    + "(SELECT access_token_ref FROM chzzk_channel_links WHERE user_id = ? "
                    + "UNION SELECT refresh_token_ref FROM chzzk_channel_links WHERE user_id = ?)", id, id);
            jdbc.update("DELETE FROM secrets WHERE ref IN "
                    + "(SELECT access_token_ref FROM youtube_channel_links WHERE user_id = ? "
                    + "UNION SELECT refresh_token_ref FROM youtube_channel_links WHERE user_id = ?)", id, id);
            jdbc.update("DELETE FROM pairing_codes WHERE user_id = ?", id);
            jdbc.update("DELETE FROM stream_keys WHERE user_id = ?", id);
            jdbc.update("DELETE FROM chzzk_channel_links WHERE user_id = ?", id);
            jdbc.update("DELETE FROM youtube_channel_links WHERE user_id = ?", id);
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        심은_회원.clear();
    }

    /**
     * 개인정보 네 칸에 <b>같은 표식</b>을 심는다. 탈퇴 뒤 그 표식이 표에 한 글자도 안 남아야 한다 —
     * 칸마다 다른 값을 넣으면 「어느 칸을 빠뜨렸나」를 단언마다 따로 적어야 하고, 새 칸이 생겼을 때
     * 그 단언을 더하는 것을 잊는다.
     *
     * <p>🔴 <b>구글 사진 주소를 반드시 채운다</b>({@code ProfileTestSupport.newUser}가 같은 이유로 그렇게 한다).
     * 처음부터 비어 있으면 「안 지워졌다」와 「원래 없었다」가 <b>구분되지 않는다.</b>
     *
     * <p>표식이 소문자인 것은 우연이 아니다 — {@code UserCreator}가 이메일을 소문자로 통일해 저장하므로
     * 대문자가 섞이면 표에서 찾는 글자와 심은 글자가 갈린다.
     */
    protected User newUser(String marker) {
        return track(userService.findOrCreate(
                "sub-withdrawal-" + marker,
                marker + "@example.com",
                "김태현-" + marker,
                "https://lh3.googleusercontent.com/" + marker));
    }

    /** 표식을 안 보는 시험용. 이메일 유일 제약(V108)이 있어 계정마다 주소를 흩는다. */
    protected User newUser() {
        return newUser(marker());
    }

    /** 매 호출 다른 값. 짧은 상수를 쓰면 다른 시험의 값과 우연히 겹쳐 「안 남았다」가 헛통과한다. */
    protected static String marker() {
        return UUID.randomUUID().toString();
    }

    /**
     * 창구를 거치지 않고 만든 회원도 거두기 목록에 넣는다. 탈퇴 뒤 재로그인처럼
     * <b>시험 중간에 회원이 하나 더 생기는</b> 갈래가 있어 필요하다.
     */
    protected User track(User user) {
        심은_회원.add(user.getId());
        return user;
    }

    /**
     * 다른 트랜잭션이 이 회원 행을 {@code hold}만큼 잠근 채로 있게 하고, <b>그동안</b> 넘겨받은 일을 돌린다.
     * 락을 실제로 잡은 것을 확인한 뒤에야 일을 시작하므로 대기 시간이 결정적이다.
     *
     * <p>🔴 <b>이것이 재는 것은 「탈퇴가 락 앞에서 얼마나 기다렸나」다.</b> 운영에서 그 대기를 만드는 것은
     * {@code ChzzkTokenRefresher}가 같은 회원 행 락을 쥔 채 치지직 HTTP(connect 2s + read 5s)를
     * 기다리는 자리다(auth/CLAUDE.md 「알려진 구멍」 10). 여기서는 그 HTTP 대신 잠자는 트랜잭션으로
     * 같은 모양을 만든다 — 가짜 서버를 세우지 않아도 대기 폭을 우리가 정할 수 있다.
     *
     * <p>커넥션을 {@code JdbcTemplate}에서 직접 꺼내는 이유는 <b>스프링 트랜잭션 밖</b>이어야 하기 때문이다.
     * {@code TransactionTemplate}으로 잡으면 같은 스레드의 트랜잭션 동기화에 얽혀 대기가 안 생긴다.
     */
    protected void 회원_행을_잠근_채(Long userId, Duration hold, Runnable 그동안) throws Exception {
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

    protected String bearer(User user) {
        return "Bearer " + tokenService.issue(user).accessToken();
    }
}
