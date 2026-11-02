package com.pokeclip.auth.youtube;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이그레이션이 심은 표·컬럼 주석은 <b>DB에 저장돼</b> 운영자가 {@code \d+}로 읽는다. 그래서 코드처럼
 * 낡고, 낡은 채로 굳는다 — <b>머지된 마이그레이션은 고치기 어렵다</b>(체크섬이 어긋나 로컬 부팅이 깨진다.
 * 이 저장소엔 V106 주석이 과장된 것을 알고도 못 고치고 3층 기록으로 대신한 전례가 있다).
 *
 * <p>그래서 여기서 잰다. 실제 사고: V109의 컬럼 주석이 <b>태스크 10 폐기로 사라진</b>
 * {@code PUT /api/youtube-link/channel}을 가리키고 있었다(로컬 리뷰 2026-08-24).
 *
 * <p>규칙은 「주석에 API 경로를 적지 않는다」다 — 엔드포인트는 코드에서 사라져도 주석은 남고,
 * DB 안의 그 문자열을 운영자가 사실로 읽는다. 표의 뜻은 적되 <b>주소는 코드가 정본이다.</b>
 */
class YoutubeSchemaCommentTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    YoutubeSchemaCommentTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 주석 문자열을 실제 DB에서 읽는다 — 마이그레이션 파일을 읽으면 「적용됐는지」를 못 잰다. */
    private List<String> youtubeComments() {
        return jdbc.queryForList("""
                SELECT obj_description('youtube_channel_links'::regclass, 'pg_class')
                UNION ALL
                SELECT col_description('youtube_channel_links'::regclass, a.attnum)
                  FROM pg_attribute a
                 WHERE a.attrelid = 'youtube_channel_links'::regclass AND a.attnum > 0
                   AND col_description('youtube_channel_links'::regclass, a.attnum) IS NOT NULL
                """, String.class);
    }

    /** 아래 검사가 「주석이 하나도 없어서」 통과하지 않게 먼저 못박는다. */
    @Test
    void 표와_컬럼_주석이_DB에_실제로_심겨_있다() {
        List<String> comments = youtubeComments();

        assertThat(comments).as("마이그레이션의 COMMENT 문이 적용되지 않았다").hasSizeGreaterThanOrEqualTo(3);
        assertThat(comments).allSatisfy(c -> assertThat(c).isNotBlank());
        assertThat(String.join("\n", comments)).contains("POK-121");
    }

    /**
     * 🔴 주석이 <b>없는 엔드포인트</b>를 가리키면 운영자가 사실이 아닌 것을 읽는다. 코드에서 지운 기능이
     * DB 주석에 살아남는 것이 정확히 그 사고였다. 경로를 아예 안 적는 것이 규칙이다.
     */
    @Test
    void 주석이_API_경로를_가리키지_않는다() {
        List<String> comments = youtubeComments();

        assertThat(comments).allSatisfy(c -> assertThat(c)
                .as("주석에 API 경로가 적혔다 — 코드에서 사라져도 DB에는 남는다. 주소는 코드가 정본이다")
                .doesNotContain("/api/")
                .doesNotContain("/internal/"));
    }

    /**
     * 채널은 동의 시점에 확정되고 UPDATE되지 않는다(2026-08-24 실측). 「재선택으로 바뀐다」는 옛 서술이
     * 되살아나면 여기서 걸린다 — 그 서술이 가리키던 기능은 실측으로 폐기됐다.
     */
    @Test
    void channel_id_주석이_재선택을_말하지_않는다() {
        String channelIdComment = jdbc.queryForObject("""
                SELECT col_description('youtube_channel_links'::regclass, a.attnum)
                  FROM pg_attribute a
                 WHERE a.attrelid = 'youtube_channel_links'::regclass AND a.attname = 'channel_id'
                """, String.class);

        assertThat(channelIdComment).isNotBlank();
        assertThat(channelIdComment)
                .as("채널을 바꾸는 수단은 재연동뿐이다 — 재선택 API는 실측으로 폐기됐다")
                .doesNotContain("재선택").doesNotContain("PUT");
    }
}
