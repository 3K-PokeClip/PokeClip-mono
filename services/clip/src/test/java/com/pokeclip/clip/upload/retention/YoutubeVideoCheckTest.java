package com.pokeclip.clip.upload.retention;

import com.pokeclip.clip.render.RenderFixtures;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 30일마다 올린 영상이 아직 있는지 확인한다(POK-423, 처리방침 13절 「지워진 영상의 ID는 삭제」). 토큰은 auth 밖으로 안 나가므로
 * 영상 번호를 auth에 넘기고 auth가 유튜브에 묻는다. 가짜 auth로 답을 정해 두고 표가 어떻게 바뀌는지 잰다.
 */
class YoutubeVideoCheckTest extends IntegrationTestSupport {

    private static final String CHECK = "/internal/youtube-videos/check";
    private static final String 방송 = "s-check";
    private static final String 주인 = "71001";
    private static final String 지금_채널 = "UCnownownownownownownown";
    private static final String 다른_채널 = "UCotherotherotherotherot";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final YoutubeVideoChecker checker;

    YoutubeVideoCheckTest(JdbcTemplate jdbc, YoutubeVideoChecker checker) {
        this.jdbc = jdbc;
        this.checker = checker;
    }

    private long 영상;
    private int 출력;

    @BeforeEach
    void 준비() {
        방송과_카드를_비운다(jdbc);
        RenderFixtures.방송을_넣는다(jdbc, 방송);
        long recipeId = RenderFixtures.편집본을_넣는다(jdbc, 방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT);
        long[] clipId = new long[1];
        RenderFixtures.주문을_넣는다(jdbc, 방송, recipeId, clipId);
        영상 = clipId[0];
    }

    @AfterEach
    void 지운다() {
        jdbc.update("DELETE FROM clip_uploads");
        방송과_카드를_비운다(jdbc);
    }

    /**
     * 30일이 지난 것만 묻는다. 없다는 답은 지금 채널 · 채널 모름 줄만 지우고(다른 채널 영상은 지금 토큰으로 안 보여 「없음」이 근거가
     * 아니다), 있다는 답은 확인 시각을 적고 채널 모름 줄에 <b>그 영상의 주인 채널</b>을 채운다. 🔴 지금 채널로 채우지 않는다: 공개 영상은
     * 다른 채널 토큰으로도 보여 옛 채널 영상이 새 채널 것으로 붙으면 옛 채널 지우기에서 빠진다(PR #375 codex)
     */
    @Test
    void 지난_것만_묻고_없는_것은_지우고_있는_것은_채널을_채운다() throws Exception {
        long 있음 = 업로드("vidKEEP0001", 지금_채널, 31);
        long 모름_있음 = 업로드("vidUNKNOWN1", null, 31);
        long 모름_옛채널 = 업로드("vidOLDPUBLIC", null, 31);
        long 없음 = 업로드("vidGONE0001", 지금_채널, 40);
        long 다른것 = 업로드("vidOTHER001", 다른_채널, 31);
        long 최근 = 업로드("vidRECENT01", 지금_채널, 3);
        AUTH.respondWith(CHECK, 200, "{\"linked\":true,\"channelId\":\"" + 지금_채널 + "\",\"videos\":["
                + "{\"id\":\"vidKEEP0001\",\"channelId\":\"" + 지금_채널 + "\"},"
                + "{\"id\":\"vidUNKNOWN1\",\"channelId\":\"" + 지금_채널 + "\"},"
                + "{\"id\":\"vidOLDPUBLIC\",\"channelId\":\"" + 다른_채널 + "\"}]}");

        checker.checkDue(Instant.now());

        JsonNode 보낸것 = MAPPER.readTree(AUTH.lastBodyOf(CHECK));
        assertThat(보낸것.get("userId").asLong()).isEqualTo(Long.parseLong(주인));
        List<String> 번호 = new ArrayList<>();
        보낸것.get("videoIds").forEach(n -> 번호.add(n.asString()));
        assertThat(번호).containsExactlyInAnyOrder("vidKEEP0001", "vidUNKNOWN1", "vidOLDPUBLIC", "vidGONE0001", "vidOTHER001");

        assertThat(줄(있음)).isEqualTo("vidKEEP0001:" + 지금_채널 + ":checked");
        assertThat(줄(모름_있음)).as("주인 채널로 채운다").isEqualTo("vidUNKNOWN1:" + 지금_채널 + ":checked");
        assertThat(줄(모름_옛채널)).as("보여도 지금 채널이 아니라 주인 채널로 채운다").isEqualTo("vidOLDPUBLIC:" + 다른_채널 + ":checked");
        assertThat(줄(없음)).isEqualTo("null:null:forgotten");
        assertThat(줄(다른것)).as("다른 채널 영상은 지금 토큰으로 안 보여도 안 지운다").isEqualTo("vidOTHER001:" + 다른_채널 + ":checked");
        assertThat(줄(최근)).isEqualTo("vidRECENT01:" + 지금_채널 + ":unchecked");
    }

    /** 연동이 없으면 유튜브에 못 묻는다. 지우는 것은 해제 기한(auth)이 하고, 여기는 확인 시각만 미뤄 매번 묻지 않는다 */
    @Test
    void 연동이_없으면_안_지우고_다음_달로_미룬다() {
        long 옛것 = 업로드("vidKEEP0001", 지금_채널, 31);
        AUTH.respondWith(CHECK, 200, "{\"linked\":false}");

        checker.checkDue(Instant.now());

        assertThat(줄(옛것)).isEqualTo("vidKEEP0001:" + 지금_채널 + ":checked");
    }

    /** auth나 유튜브가 안 되면 아무것도 안 바꾼다(다음 순회가 다시 묻는다). 모르는 모양의 답도 같다 */
    @Test
    void 못_물으면_아무것도_안_바꾼다() {
        long 옛것 = 업로드("vidKEEP0001", 지금_채널, 31);
        AUTH.respondWith(CHECK, 503, "{}");
        checker.checkDue(Instant.now());
        assertThat(줄(옛것)).isEqualTo("vidKEEP0001:" + 지금_채널 + ":unchecked");

        AUTH.respondWith(CHECK, 200, "{\"linked\":true,\"channelId\":\"x\",\"videos\":\"vidKEEP0001\"}");
        checker.checkDue(Instant.now());
        assertThat(줄(옛것)).as("videos가 배열이 아니면 모른다").isEqualTo("vidKEEP0001:" + 지금_채널 + ":unchecked");
    }

    /** 한 번에 50개까지 묻는다(유튜브 videos.list 한 번의 상한). 남은 것은 같은 순회의 다음 묶음이 묻는다 */
    @Test
    void 쉰_개씩_나눠_묻는다() {
        for (int i = 0; i < 51; i++) {
            업로드(String.format("vid%08d", i), 지금_채널, 31);
        }
        AUTH.respondWith(CHECK, 200, "{\"linked\":true,\"channelId\":\"" + 지금_채널 + "\",\"videos\":[]}");

        checker.checkDue(Instant.now());

        assertThat(AUTH.callCount(CHECK)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clip_uploads WHERE video_forgotten_at IS NOT NULL", Integer.class))
                .isEqualTo(51);
    }

    private long 업로드(String videoId, String channelId, int daysAgo) {
        Instant created = Instant.now().minus(Duration.ofDays(daysAgo));
        return jdbc.queryForObject("""
                INSERT INTO clip_uploads (clip_id, output_id, requested_by, channel_owner, title, status, payload,
                                          youtube_video_id, youtube_channel_id, created_at)
                VALUES (?, ?, ?, ?, '제목', 'uploaded', '{}'::jsonb, ?, ?, ?) RETURNING id""", Long.class,
                영상, "o" + (++출력), 주인, 주인, videoId, channelId, java.sql.Timestamp.from(created));
    }

    private String 줄(long id) {
        return jdbc.queryForObject("""
                SELECT coalesce(youtube_video_id, 'null') || ':' || coalesce(youtube_channel_id, 'null') || ':'
                       || CASE WHEN video_forgotten_at IS NOT NULL THEN 'forgotten'
                               WHEN video_checked_at IS NOT NULL THEN 'checked' ELSE 'unchecked' END
                FROM clip_uploads WHERE id = ?""", String.class, id);
    }
}
