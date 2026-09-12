package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방송 정보 저장소를 <b>실 PostgreSQL</b>에서 잰다. 가짜로 못 재는 것이 셋이다 —
 * {@code TEXT[]} 왕복, {@code timestamptz} 왕복, 그리고 <b>{@code null} 시청자 수가
 * 0으로 접히지 않는가</b>({@code rs.getInt}은 null을 0으로 준다).
 *
 * <p><b>방송 번호 접두는 {@code bi-}다.</b> 컨테이너는 JVM에 하나뿐이라 앞 검사의 줄이 섞인다.
 */
@SpringBootTest
@ActiveProfiles("test")
class BroadcastInfoStoreTest extends IntegrationTestSupport {

    private static final List<String> STREAMS =
            List.of("bi-1", "bi-cap", "bi-tags", "bi-null-tag", "bi-null-write");
    private static final Instant T = Instant.parse("2026-09-03T15:00:00Z");

    private final BroadcastInfoStore store;
    private final JdbcTemplate jdbc;

    BroadcastInfoStoreTest(BroadcastInfoStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 내_방송만_비운다() {
        STREAMS.forEach(streamId ->
                jdbc.update("DELETE FROM broadcast_info WHERE stream_id = ?", streamId));
    }

    @Test
    void 최신과_추이를_준다() {
        store.insert(new BroadcastInfo("bi-1", "CH", T, "제목1", List.of("롤"), "LoL", 100));
        store.insert(new BroadcastInfo("bi-1", "CH", T.plusSeconds(60), "제목2",
                List.of("롤", "다이아"), "LoL", null));

        assertThat(store.latest("bi-1").orElseThrow().title()).isEqualTo("제목2");
        assertThat(store.series("bi-1", T.minusSeconds(1), 720))
                .as("오래된 것부터다 — 화면이 왼쪽에서 오른쪽으로 그린다")
                .extracting(BroadcastInfo::viewers)
                .containsExactly(100, null);
        assertThat(store.latest("bi-없음")).isEmpty();
        assertThat(store.series("bi-없음", T, 720)).isEmpty();
    }

    /**
     * {@code null} 시청자 수는 <b>정상값</b>이다(전체 라이브 목록에서 그 방송을 못 찾은 회차).
     * 0으로 접히면 화면이 「아무도 안 봤다」를 그린다 — 그럴듯하게 틀린 답이다.
     */
    @Test
    void 모르는_시청자_수는_0이_아니라_빈칸으로_돌아온다() {
        store.insert(new BroadcastInfo("bi-1", "CH", T, "제목", List.of(), null, null));

        assertThat(store.latest("bi-1").orElseThrow().viewers()).isNull();
    }

    /** 쉼표·중괄호가 든 태그가 배열 문법으로 갈리면 여기서 드러난다. */
    @Test
    void 태그는_구분자가_들어_있어도_그대로_돌아온다() {
        List<String> tags = List.of("롤,LoL", "{중괄호}", "빈 칸 있음");
        store.insert(new BroadcastInfo("bi-tags", "CH", T, "제목", tags, "LoL", 3));

        assertThat(store.latest("bi-tags").orElseThrow().tags()).isEqualTo(tags);
        assertThat(store.latest("bi-tags").orElseThrow().category()).isEqualTo("LoL");
    }

    /**
     * 상한에 걸리면 <b>먼 과거가 잘린다.</b> 오름차순으로 자르면 최근이 통째로 사라지는데,
     * 화면은 그것을 「최근에 관측이 없었다」로 그려 정반대 결론을 만든다.
     */
    @Test
    void 상한을_넘으면_최근부터_남긴다() {
        for (int i = 0; i < 5; i++) {
            store.insert(new BroadcastInfo("bi-cap", "CH", T.plusSeconds(i), "제목",
                    List.of(), "LoL", i));
        }

        assertThat(store.series("bi-cap", T.minusSeconds(1), 2))
                .extracting(BroadcastInfo::viewers)
                .containsExactly(3, 4);
    }

    /**
     * 🔴 <b>이미 들어간 NULL 원소를 읽어도 안 터진다.</b> PostgreSQL {@code TEXT[]}는
     * 원소 NULL을 허용하는데 {@code List.of}는 그것에 NPE를 던진다 — 저장이 성공한 뒤
     * <b>읽기만</b> 터지므로 그 행이 최신인 동안 그 방송의 창구가 <b>영구히 500</b>이 되고,
     * clip이 5xx를 접어 화면에는 「수집 서버가 아프다」로 보인다.
     *
     * <p><b>SQL로 직접 심는 이유</b>는 아래 쌍둥이 검사가 쓰기 쪽을 막아 버려서
     * {@code store.insert}로는 이 줄을 못 만들기 때문이다 — 그래도 <b>과거에 들어간 줄</b>은
     * 실재할 수 있으므로 읽기 쪽 그물을 따로 둔다.
     */
    @Test
    void 표에_들어간_NULL_태그를_읽어도_안_터진다() {
        jdbc.update("INSERT INTO broadcast_info"
                + " (stream_id, channel_id, observed_at, live_title, tags, category, concurrent_users)"
                + " VALUES ('bi-null-tag','CH',?,'제목',ARRAY['롤',NULL]::text[],'LoL',7)",
                java.sql.Timestamp.from(T));

        assertThat(store.latest("bi-null-tag").orElseThrow().tags()).containsExactly("롤");
        assertThat(store.series("bi-null-tag", T.minusSeconds(1), 720))
                .singleElement()
                .extracting(BroadcastInfo::viewers).isEqualTo(7);
    }

    /** 쓰는 쪽도 막는다 — 읽기만 고치면 NULL 원소가 표에 계속 쌓인다. */
    @Test
    void NULL_태그는_표에_안_들어간다() {
        List<String> 널이_섞인_태그 = java.util.Arrays.asList("롤", null, "랭크");

        store.insert(new BroadcastInfo("bi-null-write", "CH", T, "제목", 널이_섞인_태그, "LoL", 1));

        assertThat(jdbc.queryForObject(
                "SELECT array_position(tags, NULL) IS NOT NULL FROM broadcast_info"
                        + " WHERE stream_id = 'bi-null-write'", Boolean.class))
                .as("표에 NULL 원소가 들어갔다 — 읽는 쪽이 그 줄에서 500을 낸다").isFalse();
        assertThat(store.latest("bi-null-write").orElseThrow().tags())
                .containsExactly("롤", "랭크");
    }

    /** {@code since}보다 이른 줄은 추이에 안 든다 — 안 그러면 상한이 무엇을 자르는지 흐려진다. */
    @Test
    void since보다_이른_줄은_추이에_안_든다() {
        store.insert(new BroadcastInfo("bi-1", "CH", T, "옛것", List.of(), "LoL", 1));
        store.insert(new BroadcastInfo("bi-1", "CH", T.plusSeconds(600), "새것", List.of(), "LoL", 2));

        assertThat(store.series("bi-1", T.plusSeconds(300), 720))
                .extracting(BroadcastInfo::viewers).containsExactly(2);
    }
}
