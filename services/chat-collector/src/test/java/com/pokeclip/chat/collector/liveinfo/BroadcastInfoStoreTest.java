package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
            List.of("bi-1", "bi-cap", "bi-tags", "bi-null-tag", "bi-null-write", "bi-title-a", "bi-title-b", "bi-title-gap", "bi-plat",
                    "bi-dual", "bi-soop-early", "bi-soop-untitled");
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

    /** 🔴 표의 기본값이 CHZZK라 SOOP으로만 잰다. 쓰기와 읽기(매퍼)를 같이 본다. */
    @Test
    void 플랫폼을_적고_그대로_읽는다() {
        store.insert(new BroadcastInfo(Platform.SOOP, "bi-plat", "CH", T, "제목", List.of(), null, null));

        assertThat(jdbc.queryForObject("SELECT platform FROM broadcast_info WHERE stream_id = 'bi-plat'",
                String.class)).isEqualTo("SOOP");
        assertThat(store.latest("bi-plat")).get().extracting(BroadcastInfo::platform).isEqualTo(Platform.SOOP);
        assertThat(store.series("bi-plat", T, 10)).extracting(BroadcastInfo::platform).containsExactly(Platform.SOOP);
    }

    /** 카테고리 사진 칸(POK-462)의 왕복과, 칸이 생기기 전 줄(값 없음)이 null로 읽히는 것. */
    @Test
    void 카테고리_사진을_적고_읽는다_옛_줄은_null이다() {
        // STREAMS에 안 넣는다: 그 목록을 통째로 묻는 검사가 있다
        jdbc.update("DELETE FROM broadcast_info WHERE stream_id IN ('bi-img', 'bi-img-old')");
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-img", "CH", T, "제목", List.of(), "LoL", null,
                "https://img.example/lol.jpg"));
        jdbc.update("INSERT INTO broadcast_info (stream_id, channel_id, observed_at) VALUES ('bi-img-old', 'CH', now())");

        assertThat(store.latest("bi-img").orElseThrow().categoryImageUrl()).isEqualTo("https://img.example/lol.jpg");
        assertThat(store.latest("bi-img-old").orElseThrow().categoryImageUrl()).isNull();
    }

    /** 구간 창구가 접을 줄(POK-462). 오름차순이고, 상한을 넘으면 오래된 줄을 버리고 truncated다. 딱 상한이면 안 잘렸다. */
    @Test
    void 구간용_줄은_오름차순이고_넘치면_오래된_줄을_버린다() {
        jdbc.update("DELETE FROM broadcast_info WHERE stream_id = 'bi-tl'");
        for (int minute = 0; minute < 4; minute++) {
            store.insert(new BroadcastInfo(Platform.CHZZK, "bi-tl", "CH", T.plusSeconds(60L * minute), "t" + minute, List.of(), null, null));
        }

        BroadcastInfoStore.TimelineRows cut = store.timelineRows("bi-tl", 3);
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.rows()).extracting(BroadcastInfo::title).containsExactly("t1", "t2", "t3");

        BroadcastInfoStore.TimelineRows exact = store.timelineRows("bi-tl", 4);
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.rows()).extracting(BroadcastInfo::title).containsExactly("t0", "t1", "t2", "t3");
        assertThat(store.timelineRows("bi-없음", 4).rows()).isEmpty();
    }

    @Test
    void 최신과_추이를_준다() {
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-1", "CH", T, "제목1", List.of("롤"), "LoL", 100));
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-1", "CH", T.plusSeconds(60), "제목2",
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
     * 여러 방송의 마지막 제목을 한 번에 읽는다(POK-259). 방송마다 <b>자기</b> 제목을 받는지 같이 잰다. 질의 한 번이라 방송끼리
     * 섞이면 여기서 드러난다. 관측이 없는 방송은 맵에 키가 없다.
     */
    @Test
    void 여러_방송의_마지막_제목을_한_번에_준다() {
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-title-a", "CH", T, "옛 제목", List.of(), "Talk", null));
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-title-a", "CH", T.plusSeconds(60), "  새 제목 ", List.of(), "LoL", null));
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-title-b", "CH", T, "다른 방송", List.of(), null, null));

        assertThat(store.latestTitles(List.of("bi-title-a", "bi-title-b", "bi-없음")))
                .containsOnlyKeys("bi-title-a", "bi-title-b")
                .containsEntry("bi-title-a", new BroadcastInfoStore.Title("새 제목", "LoL"))
                .containsEntry("bi-title-b", new BroadcastInfoStore.Title("다른 방송", null));
        assertThat(store.latestTitles(List.of())).isEmpty();
    }

    /**
     * 한 방송을 치지직·SOOP에 같이 내보내면 플랫폼마다 최신 값이 따로다(POK-397). 시각을 엇갈리게 심어 두 플랫폼 줄이 번갈아 섞이면
     * 드러나게 한다. 시청자 수·관측 시각은 그 플랫폼의 <b>최신 줄</b>(제목 조건 없음), 제목·카테고리는 그 플랫폼의 <b>제목 있는</b> 최신 줄이다.
     * 옛 칸({@code latestTitles})은 플랫폼과 무관한 「제목 있는 마지막 값」 그대로다.
     */
    @Test
    void 플랫폼마다_최신_값을_따로_준다() {
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-dual", "CH", T, "치지직1", List.of(), "LoL", 10));
        store.insert(new BroadcastInfo(Platform.SOOP, "bi-dual", "SU", T.plusSeconds(60), "숲1", List.of(), "Talk", 5));
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-dual", "CH", T.plusSeconds(120), "치지직2", List.of(), "LoL", 20));
        store.insert(new BroadcastInfo(Platform.SOOP, "bi-dual", "SU", T.plusSeconds(180), null, List.of(), null, 7));
        // SOOP이 먼저 끝난 방송: SOOP 마지막 관측은 처음 한 줄이다
        store.insert(new BroadcastInfo(Platform.SOOP, "bi-soop-early", "SU", T, "숲 처음", List.of(), null, 3));
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-soop-early", "CH", T.plusSeconds(300), "치지직 끝까지", List.of(), null, 30));
        // 제목을 한 번도 못 얻은 SOOP 방송도 시청자 수는 나간다(계획 검증 M1)
        store.insert(new BroadcastInfo(Platform.SOOP, "bi-soop-untitled", "SU", T, null, List.of(), null, 9));

        Map<String, List<BroadcastInfoStore.PlatformLatest>> latest =
                store.latestByPlatform(List.of("bi-dual", "bi-soop-early", "bi-soop-untitled", "bi-없음"));

        assertThat(latest).containsOnlyKeys("bi-dual", "bi-soop-early", "bi-soop-untitled");
        assertThat(latest.get("bi-dual")).as("순서는 플랫폼 열거 순(CHZZK, SOOP)").containsExactly(
                new BroadcastInfoStore.PlatformLatest(Platform.CHZZK, "치지직2", "LoL", 20, T.plusSeconds(120)),
                new BroadcastInfoStore.PlatformLatest(Platform.SOOP, "숲1", "Talk", 7, T.plusSeconds(180)));
        assertThat(latest.get("bi-soop-early")).containsExactly(
                new BroadcastInfoStore.PlatformLatest(Platform.CHZZK, "치지직 끝까지", null, 30, T.plusSeconds(300)),
                new BroadcastInfoStore.PlatformLatest(Platform.SOOP, "숲 처음", null, 3, T));
        assertThat(latest.get("bi-soop-untitled")).containsExactly(
                new BroadcastInfoStore.PlatformLatest(Platform.SOOP, null, null, 9, T));
        assertThat(store.latestByPlatform(List.of())).isEmpty();

        assertThat(store.latestTitles(List.of("bi-dual", "bi-soop-untitled")))
                .as("옛 칸은 플랫폼과 무관한 제목 있는 마지막 값이고, 제목 없는 방송은 키가 없다")
                .containsOnlyKeys("bi-dual")
                .containsEntry("bi-dual", new BroadcastInfoStore.Title("치지직2", "LoL"));
        assertThat(store.series("bi-dual", T.minusSeconds(1), 720)).as("추이 줄마다 플랫폼이 실린다")
                .extracting(BroadcastInfo::platform)
                .containsExactly(Platform.CHZZK, Platform.SOOP, Platform.CHZZK, Platform.SOOP);
    }

    /**
     * 제목을 못 얻은 회차(설정 조회와 라이브 목록이 둘 다 비었다)는 제목 없이 한 줄이 남는다. 그 줄을 고르면 보이던 제목이 방송
     * 번호로 돌아가므로 제목이 있는 마지막 관측을 고르고, 카테고리도 그 줄 것을 싣는다.
     */
    @Test
    void 제목이_빈_관측은_건너뛰고_그_앞_제목을_준다() {
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-title-gap", "CH", T, "살아 있는 제목", List.of(), "Talk", null));
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-title-gap", "CH", T.plusSeconds(60), null, List.of(), "LoL", null));
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-title-gap", "CH", T.plusSeconds(120), " \t ", List.of(), "LoL", null));

        assertThat(store.latestTitles(List.of("bi-title-gap")))
                .containsEntry("bi-title-gap", new BroadcastInfoStore.Title("살아 있는 제목", "Talk"));
    }

    /**
     * {@code null} 시청자 수는 <b>정상값</b>이다(전체 라이브 목록에서 그 방송을 못 찾은 회차).
     * 0으로 접히면 화면이 「아무도 안 봤다」를 그린다 — 그럴듯하게 틀린 답이다.
     */
    @Test
    void 모르는_시청자_수는_0이_아니라_빈칸으로_돌아온다() {
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-1", "CH", T, "제목", List.of(), null, null));

        assertThat(store.latest("bi-1").orElseThrow().viewers()).isNull();
    }

    /** 쉼표·중괄호가 든 태그가 배열 문법으로 갈리면 여기서 드러난다. */
    @Test
    void 태그는_구분자가_들어_있어도_그대로_돌아온다() {
        List<String> tags = List.of("롤,LoL", "{중괄호}", "빈 칸 있음");
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-tags", "CH", T, "제목", tags, "LoL", 3));

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
            store.insert(new BroadcastInfo(Platform.CHZZK, "bi-cap", "CH", T.plusSeconds(i), "제목",
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

        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-null-write", "CH", T, "제목", 널이_섞인_태그, "LoL", 1));

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
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-1", "CH", T, "옛것", List.of(), "LoL", 1));
        store.insert(new BroadcastInfo(Platform.CHZZK, "bi-1", "CH", T.plusSeconds(600), "새것", List.of(), "LoL", 2));

        assertThat(store.series("bi-1", T.plusSeconds(300), 720))
                .extracting(BroadcastInfo::viewers).containsExactly(2);
    }
}
