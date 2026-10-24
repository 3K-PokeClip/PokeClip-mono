package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.liveinfo.BroadcastTimeline.Segment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 관측 줄을 구간으로 접는 규칙(POK-462). 순수 함수라 DB 없이 잰다. */
class BroadcastTimelineTest {

    private static final Instant T = Instant.parse("2026-10-25T10:00:00Z");

    private static BroadcastInfo row(Platform platform, int minute, String title, String category, List<String> tags, String image) {
        return new BroadcastInfo(platform, "s", "c", T.plusSeconds(60L * minute), title, tags, category, null, image);
    }

    private static BroadcastInfo chzzk(int minute, String title, String category, String image) {
        return row(Platform.CHZZK, minute, title, category, List.of("롤"), image);
    }

    @Test
    void 같은_값이_이어지면_한_구간이고_마지막_구간의_until은_null이다() {
        BroadcastTimeline timeline = BroadcastTimeline.compress(List.of(
                chzzk(0, "랭크", "LoL", "img"), chzzk(1, "랭크", "LoL", "img"), chzzk(2, "랭크", "LoL", "img")), 100);

        assertThat(timeline.truncated()).isFalse();
        assertThat(timeline.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.platform()).isEqualTo(Platform.CHZZK);
            assertThat(segment.from()).isEqualTo(T);
            assertThat(segment.until()).isNull();
            assertThat(segment.lastObservedAt()).isEqualTo(T.plusSeconds(120));
            assertThat(segment.title()).isEqualTo("랭크");
            assertThat(segment.category()).isEqualTo("LoL");
            assertThat(segment.categoryImageUrl()).isEqualTo("img");
            assertThat(segment.tags()).containsExactly("롤");
        });
    }

    /** 제목·카테고리·태그 어느 하나만 바뀌어도 끊는다. 앞 구간의 until은 다음 구간의 from이다(틈 없이 이어진다). */
    @Test
    void 제목_카테고리_태그_하나만_바뀌어도_끊고_until은_다음_from이다() {
        BroadcastTimeline timeline = BroadcastTimeline.compress(List.of(
                row(Platform.CHZZK, 0, "랭크", "LoL", List.of("롤", "랭크"), null),
                row(Platform.CHZZK, 1, "랭크!", "LoL", List.of("롤", "랭크"), null),
                row(Platform.CHZZK, 2, "랭크!", "Talk", List.of("롤", "랭크"), null),
                row(Platform.CHZZK, 3, "랭크!", "Talk", List.of("랭크", "롤"), null),
                row(Platform.CHZZK, 4, "랭크!", "Talk", List.of("랭크", "롤"), null)), 100);

        assertThat(timeline.segments()).extracting(Segment::from)
                .containsExactly(T, T.plusSeconds(60), T.plusSeconds(120), T.plusSeconds(180));
        assertThat(timeline.segments()).extracting(Segment::until)
                .as("태그 순서가 바뀐 것도 스트리머가 고친 것이다")
                .containsExactly(T.plusSeconds(60), T.plusSeconds(120), T.plusSeconds(180), null);
        assertThat(timeline.segments().get(3).lastObservedAt()).isEqualTo(T.plusSeconds(240));
    }

    /** 바뀌었다가 돌아오면 새 구간이다(앞 구간과 합치지 않는다). */
    @Test
    void 바뀌었다가_돌아오면_새_구간이다() {
        BroadcastTimeline timeline = BroadcastTimeline.compress(List.of(
                chzzk(0, "a", "LoL", null), chzzk(1, "b", "LoL", null), chzzk(2, "a", "LoL", null)), 100);

        assertThat(timeline.segments()).extracting(Segment::title).containsExactly("a", "b", "a");
    }

    /**
     * 두 플랫폼에 같이 내보내면 줄이 섞여 온다. 플랫폼마다 따로 끊고 until도 같은 플랫폼 것이다. 결과는 from 순(같으면 플랫폼 이름순).
     * 섞어서 한 줄로 접으면 플랫폼이 번갈아 와서 1분마다 「바뀜」이 된다.
     */
    @Test
    void 플랫폼이_섞여_와도_플랫폼마다_따로_끊는다() {
        BroadcastTimeline timeline = BroadcastTimeline.compress(List.of(
                row(Platform.CHZZK, 0, "치지직", "LoL", List.of(), null),
                row(Platform.SOOP, 0, "숲", "LOL", List.of(), null),
                row(Platform.CHZZK, 1, "치지직", "LoL", List.of(), null),
                row(Platform.SOOP, 1, "숲", "LOL", List.of(), null),
                row(Platform.SOOP, 2, "숲2", "LOL", List.of(), null),
                row(Platform.CHZZK, 3, "치지직", "LoL", List.of(), null)), 100);

        assertThat(timeline.segments()).extracting(s -> s.platform() + ":" + s.title() + ":" + s.until())
                .containsExactly("CHZZK:치지직:null", "SOOP:숲:" + T.plusSeconds(120), "SOOP:숲2:null");
        assertThat(timeline.segments().get(0).lastObservedAt()).isEqualTo(T.plusSeconds(180));
    }

    /**
     * 사진은 구간을 가르지 않는다. 설정 조회가 거부된 회차는 사진이 null이라, 넣으면 같은 카테고리가 쪼개진다.
     * 구간의 사진 = 그 구간에서 마지막으로 본 null 아닌 값.
     */
    @Test
    void 사진만_다르면_안_끊고_구간의_마지막_null_아닌_사진을_쓴다() {
        BroadcastTimeline timeline = BroadcastTimeline.compress(List.of(
                chzzk(0, "a", "LoL", "old"), chzzk(1, "a", "LoL", null), chzzk(2, "a", "LoL", "new"), chzzk(3, "a", "LoL", null)), 100);

        assertThat(timeline.segments()).singleElement().extracting(Segment::categoryImageUrl).isEqualTo("new");
    }

    @Test
    void 카테고리가_바뀌면_사진도_그_구간_것만_쓴다() {
        BroadcastTimeline timeline = BroadcastTimeline.compress(List.of(
                chzzk(0, "a", "LoL", "lol.jpg"), chzzk(1, "a", "Talk", null)), 100);

        assertThat(timeline.segments()).extracting(Segment::categoryImageUrl).containsExactly("lol.jpg", null);
    }

    /** 제목 앞뒤 공백은 깎아 비교하고 낸다(방송 목록 제목과 같다). 공백뿐이면 null. 카테고리도 같다. */
    @Test
    void 앞뒤_공백만_다르면_같은_값이고_공백뿐이면_null이다() {
        BroadcastTimeline timeline = BroadcastTimeline.compress(List.of(
                chzzk(0, " a ", "LoL", null), chzzk(1, "a", " LoL", null), chzzk(2, "  ", " ", null)), 100);

        assertThat(timeline.segments()).extracting(Segment::title).containsExactly("a", null);
        assertThat(timeline.segments()).extracting(Segment::category).containsExactly("LoL", null);
    }

    /** 구간이 상한을 넘으면 <b>오래된 쪽</b>을 버린다. 라이브 화면에는 최근 값이 필요하다. */
    @Test
    void 구간이_상한을_넘으면_오래된_쪽을_버리고_truncated다() {
        List<BroadcastInfo> rows = new ArrayList<>();
        for (int minute = 0; minute < 5; minute++) {
            rows.add(chzzk(minute, "t" + minute, "LoL", null));
        }

        BroadcastTimeline timeline = BroadcastTimeline.compress(rows, 3);

        assertThat(timeline.truncated()).isTrue();
        assertThat(timeline.segments()).extracting(Segment::title).containsExactly("t2", "t3", "t4");
        assertThat(BroadcastTimeline.compress(rows, 5).truncated()).as("딱 맞으면 안 잘렸다").isFalse();
    }

    @Test
    void 줄이_없으면_빈_목록이다() {
        assertThat(BroadcastTimeline.compress(List.of(), 10).segments()).isEmpty();
    }
}
