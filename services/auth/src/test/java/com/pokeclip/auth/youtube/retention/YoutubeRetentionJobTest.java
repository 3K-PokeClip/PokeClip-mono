package com.pokeclip.auth.youtube.retention;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.youtube.YoutubeChannel;
import com.pokeclip.auth.youtube.YoutubeChannelLink;
import com.pokeclip.auth.youtube.YoutubeChannelLinkRepository;
import com.pokeclip.auth.youtube.YoutubeCleanupExecutor;
import com.pokeclip.auth.youtube.YoutubeLinkService;
import com.pokeclip.auth.youtube.YoutubeLinkStateCodec;
import com.pokeclip.auth.youtube.YoutubeLinkTestSupport;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import com.pokeclip.auth.youtube.YoutubeOAuthClient;
import com.pokeclip.auth.youtube.YoutubeTokens;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유튜브 데이터 보관(POK-423, 처리방침 13절). 해제 7일 · 철회 30일 뒤 채널 정보가 지워지고 clip에 영상 번호 지우기가 가는지, 연동 중
 * 30일마다 채널 이름을 다시 받는지 잰다. clip은 가짜 서버다(받은 본문을 적는다). 시각은 한 바퀴에 넣어 준다.
 */
class YoutubeRetentionJobTest extends YoutubeLinkTestSupport {

    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";

    private final YoutubeRetentionStore store;
    private final YoutubeLinkService linkService;
    private final YoutubeOAuthClient oauth;
    private final YoutubeRetentionProperties properties;
    private final RestClient.Builder restClientBuilder;

    private HttpServer clip;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger answer = new AtomicInteger(200);
    private YoutubeRetentionJob job;

    YoutubeRetentionJobTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                            TokenService tokenService, YoutubeLinkStateCodec codec,
                            YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                            YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                            YoutubeRetentionStore store, YoutubeLinkService linkService, YoutubeOAuthClient oauth,
                            YoutubeRetentionProperties properties, RestClient.Builder restClientBuilder) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc,
                cleanup);
        this.store = store;
        this.linkService = linkService;
        this.oauth = oauth;
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    /** clip 노릇을 하는 서버. 받은 「경로 토큰일치 본문」을 적는다. 루프백에 붙인다(가로채기 방지). */
    @BeforeEach
    void 가짜_clip을_띄운다() throws IOException {
        clip = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        clip.createContext("/internal/", exchange -> {
            received.add(exchange.getRequestURI().getPath() + " "
                    + INTERNAL.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token")) + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
        });
        clip.start();
        YoutubeVideoForgetNotifier notifier = new YoutubeVideoForgetNotifier(restClientBuilder,
                "http://127.0.0.1:" + clip.getAddress().getPort(), INTERNAL);
        job = new YoutubeRetentionJob(store, notifier, linkService, oauth, properties);
        jdbc.update("DELETE FROM youtube_video_forget_jobs");
    }

    @AfterEach
    void 내린다() {
        clip.stop(0);
        jdbc.update("DELETE FROM youtube_video_forget_jobs");
    }

    @Test
    void 해제_7일이_지나면_채널을_지우고_clip에_그_채널의_영상_번호_지우기를_보낸다() {
        User user = newUser();
        String 채널 = 연동(user, "UC-old-");
        닫는다(user, "USER_UNLINKED", Duration.ofDays(8));
        // DB 시각은 마이크로초까지라 그 자리에서 자른다(장부에서 읽은 값과 글자로 견준다)
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        job.runOnce(now);

        assertThat(남은_채널(user)).as("닫힌 줄의 채널 번호 · 이름").containsExactly("null:null");
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r).startsWith("/internal/youtube-videos/forget true ");
            assertThat(r).contains("\"owner\":\"" + user.getId() + "\"", "\"channelId\":\"" + 채널 + "\"",
                    "\"before\":\"" + now + "\"");
        });
        assertThat(장부()).as("보냈으면 장부 줄째 지운다(채널 번호를 남기지 않는다)").isZero();
    }

    @Test
    void 해제_7일_전이면_그대로다() {
        User user = newUser();
        String 채널 = 연동(user, "UC-keep-");
        닫는다(user, "USER_UNLINKED", Duration.ofDays(6));

        job.runOnce(Instant.now());

        assertThat(남은_채널(user)).containsExactly(채널 + ":채널");
        assertThat(received).isEmpty();
    }

    /** 기한 안에 같은 채널로 다시 연동했으면 닫힌 줄의 채널만 지우고 영상 번호는 남긴다(살아 있는 연동의 것이다) */
    @Test
    void 같은_채널로_다시_연동했으면_영상_번호는_안_지운다() {
        User user = newUser();
        String 채널 = 연동(user, "UC-same-");
        닫는다(user, "USER_UNLINKED", Duration.ofDays(8));
        writer.create(user.getId(), new YoutubeChannel(채널, "채널"),
                new YoutubeTokens("at-2", "rt-2", Duration.ofHours(1), null));

        job.runOnce(Instant.now());

        assertThat(남은_채널(user)).containsExactlyInAnyOrder("null:null", 채널 + ":채널");
        assertThat(received).isEmpty();
        assertThat(장부()).isZero();
    }

    @Test
    void 다른_채널로_바꿨으면_옛_채널의_영상_번호_지우기를_보낸다() {
        User user = newUser();
        String 옛 = 연동(user, "UC-before-");
        닫는다(user, "USER_UNLINKED", Duration.ofDays(8));
        String 새 = 연동(user, "UC-after-");

        job.runOnce(Instant.now());

        assertThat(received).singleElement().satisfies(r -> assertThat(r).contains("\"channelId\":\"" + 옛 + "\""));
        assertThat(남은_채널(user)).containsExactlyInAnyOrder("null:null", 새 + ":채널");
    }

    /**
     * 0일 해제 → 1일 같은 채널 재연동 → 6일 해제면, 7일째에 옛 줄이 기한이어도 새 줄의 7일이 남았다. 옛 줄은 채널만 비우고 장부는 새 줄이
     * 기한에 적는다(옛 줄이 새 줄의 기한을 깎으면 재연동 뒤 올린 영상까지 지운다, PR #375 codex)
     */
    @Test
    void 같은_채널의_더_늦게_닫힌_줄이_있으면_그_줄의_기한까지_미룬다() {
        User user = newUser();
        String 채널 = 연동(user, "UC-twice-close-");
        닫는다(user, "USER_UNLINKED", Duration.ofDays(8));
        writer.create(user.getId(), new YoutubeChannel(채널, "채널"), new YoutubeTokens("at-2", "rt-2", Duration.ofHours(1), null));
        닫는다(user, "USER_UNLINKED", Duration.ofDays(2));

        job.runOnce(Instant.now());

        assertThat(남은_채널(user)).containsExactlyInAnyOrder("null:null", 채널 + ":채널");
        assertThat(received).as("새 줄의 기한이 남았다").isEmpty();
        assertThat(장부()).isZero();

        job.runOnce(Instant.now().plus(Duration.ofDays(6)));

        assertThat(남은_채널(user)).containsExactly("null:null", "null:null");
        assertThat(received).singleElement().satisfies(r -> assertThat(r).contains("\"channelId\":\"" + 채널 + "\""));
    }

    /** 구글 쪽 철회 · 갱신 불가(REFRESH_REJECTED)는 30일이다 */
    @Test
    void 끊김은_30일이_지나야_지운다() {
        User 이십구일 = newUser();
        String 남을_채널 = 연동(이십구일, "UC-29-");
        닫는다(이십구일, "REFRESH_REJECTED", Duration.ofDays(29));
        User 삼십일일 = newUser();
        연동(삼십일일, "UC-31-");
        닫는다(삼십일일, "REFRESH_REJECTED", Duration.ofDays(31));

        job.runOnce(Instant.now());

        assertThat(남은_채널(이십구일)).containsExactly(남을_채널 + ":채널");
        assertThat(남은_채널(삼십일일)).containsExactly("null:null");
        assertThat(received).singleElement().satisfies(r -> assertThat(r).contains("\"owner\":\"" + 삼십일일.getId() + "\""));
    }

    /** clip이 못 받으면 장부에 남고 나중에 다시 보낸다. 채널은 이미 지웠어도 영상 번호 지우기는 잃지 않는다 */
    @Test
    void clip이_못_받으면_장부에_남아_다시_보낸다() {
        User user = newUser();
        연동(user, "UC-retry-");
        닫는다(user, "USER_UNLINKED", Duration.ofDays(8));
        answer.set(503);

        job.runOnce(Instant.now());

        assertThat(남은_채널(user)).containsExactly("null:null");
        assertThat(장부()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM youtube_video_forget_jobs", Integer.class)).isEqualTo(1);

        answer.set(200);
        jdbc.update("UPDATE youtube_video_forget_jobs SET next_attempt_at = now() - interval '1 second'");
        job.runOnce(Instant.now());

        assertThat(received).hasSize(2);
        assertThat(장부()).isZero();
    }

    /**
     * 같은 채널 줄이 아직 안 보내졌는데(clip이 오래 못 받는다) 또 닫힌 줄이 기한을 넘으면 기준 시각을 늦춘다. 처음 시각에 머물면 그 사이
     * 같은 채널로 다시 연동해 올린 업로드가 지우는 범위에서 빠진다
     */
    @Test
    void 안_보낸_장부가_있으면_기준_시각을_늦춘다() {
        User user = newUser();
        String 채널 = 연동(user, "UC-twice-");
        닫는다(user, "USER_UNLINKED", Duration.ofDays(20));
        answer.set(503);
        Instant 처음 = Instant.now().minus(Duration.ofDays(12)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        job.runOnce(처음);
        writer.create(user.getId(), new YoutubeChannel(채널, "채널"), new YoutubeTokens("at-2", "rt-2", Duration.ofHours(1), null));
        닫는다(user, "USER_UNLINKED", Duration.ofDays(8));
        Instant 나중 = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("UPDATE youtube_video_forget_jobs SET next_attempt_at = now() + interval '1 hour'");

        job.runOnce(나중);

        assertThat(장부()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_at FROM youtube_video_forget_jobs", Timestamp.class).toInstant())
                .isEqualTo(나중);
    }

    /** 보내는 사이 다른 닫힘이 기준 시각을 늦췄으면 보낸 줄을 지우지 않고 곧바로 다시 보낸다(늦춘 시각을 잃지 않게) */
    @Test
    void 보내는_사이_기준_시각이_늦춰지면_장부를_안_지운다() {
        User user = newUser();
        연동(user, "UC-race-");
        닫는다(user, "USER_UNLINKED", Duration.ofDays(8));
        answer.set(503);
        job.runOnce(Instant.now());
        jdbc.update("UPDATE youtube_video_forget_jobs SET next_attempt_at = now() - interval '1 second'");
        YoutubeRetentionStore.ForgetJob 집은것 = store.claim(Instant.now(), Duration.ofMinutes(5), 10).getFirst();
        jdbc.update("UPDATE youtube_video_forget_jobs SET created_at = created_at + interval '1 day'");

        store.sent(집은것, Instant.now());

        assertThat(장부()).as("늦춘 시각이 남아 있어야 한다").isEqualTo(1);
        // 「다음 시도 시각」은 시험 쪽 시계로 적고 DB 시계와 몇 ms 어긋날 수 있어 여유를 둔다(리스 5분보다 한참 작다)
        assertThat(jdbc.queryForObject("SELECT next_attempt_at <= now() + interval '10 seconds' FROM youtube_video_forget_jobs", Boolean.class))
                .as("곧바로 다시 보낸다").isTrue();
        store.sent(store.claim(Instant.now(), Duration.ofMinutes(5), 10).getFirst(), Instant.now());
        assertThat(장부()).isZero();
    }

    /** 연동 중 30일이 지나면 channels.list로 채널 이름을 다시 받는다. 30일 전이면 안 묻는다 */
    @Test
    void 연동_중_30일마다_채널_이름을_다시_받는다() {
        User 오래 = newUser();
        String 채널 = 연동(오래, "UC-refresh-");
        jdbc.update("UPDATE youtube_channel_links SET created_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(31))), 오래.getId());
        User 최근 = newUser();
        String 최근_채널 = 연동(최근, "UC-recent-");
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"" + 채널 + "\",\"snippet\":{\"title\":\"새 이름\"}}]}");

        job.runOnce(Instant.now());

        assertThat(YOUTUBE.channelsCalls()).as("30일 안 된 연동은 안 묻는다").isEqualTo(1);
        assertThat(남은_채널(오래)).containsExactly(채널 + ":새 이름");
        assertThat(jdbc.queryForObject("SELECT channel_checked_at IS NOT NULL FROM youtube_channel_links WHERE user_id = ?",
                Boolean.class, 오래.getId())).isTrue();
        assertThat(남은_채널(최근)).containsExactly(최근_채널 + ":채널");

        job.runOnce(Instant.now());
        assertThat(YOUTUBE.channelsCalls()).as("받은 시각을 적어 다음 바퀴에 다시 안 묻는다").isEqualTo(1);
    }

    private String 연동(User user, String prefix) {
        String channelId = prefix + UUID.randomUUID();
        YoutubeChannelLink link = writer.create(user.getId(), new YoutubeChannel(channelId, "채널"),
                new YoutubeTokens("at-old", "rt-old", Duration.ofHours(1), null));
        return link.getChannelId();
    }

    /** 살아 있는 연동을 닫고 닫은 시각을 그만큼 앞당긴다. 정리 스레드(secrets 삭제)는 이 시험과 무관해 표만 바꾼다. */
    private void 닫는다(User user, String reason, Duration ago) {
        jdbc.update("UPDATE youtube_channel_links SET revoked_at = ?, revoke_reason = ? WHERE user_id = ? AND revoked_at IS NULL",
                Timestamp.from(Instant.now().minus(ago)), reason, user.getId());
    }

    private List<String> 남은_채널(User user) {
        return jdbc.queryForList("""
                SELECT coalesce(channel_id, 'null') || ':' || coalesce(channel_name, 'null')
                FROM youtube_channel_links WHERE user_id = ?""", String.class, user.getId());
    }

    private int 장부() {
        return jdbc.queryForObject("SELECT count(*) FROM youtube_video_forget_jobs", Integer.class);
    }
}
