package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.jumpcard.CardPositionReader.Scene;
import com.pokeclip.clip.jumpcard.CardPositionReader.ThumbnailOrder;
import com.pokeclip.clip.segment.OffsetOrigin;
import com.pokeclip.clip.segment.RoundRows.Row;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 위치 → 장면 하나(POK-196 · 설계 r7 8.2 「카드 장면 함수」 · kty 11:27). 썸네일 주문 값 · 편집기 {@code fileHead} ·
 * 플레이어 {@code playerAtMs} 가 같은 장면에서 나오는지, 틈 안(이어진 · 끊긴 · 비귀속 자리)이 모두 뒤 귀속 행 첫 화면인지
 * 잰다. 회차 행 열만 받는 순수 함수라 DB 없이 잰다 — 왕복 수는 {@code CardPositionRoundTripTest}, 실행 계획은
 * {@code segment.RoundRowsPlanTest}.
 */
class CardPositionReaderTest {

    private static Row legacy(long seq, long pts, int duration, long wall, Long pdt) {
        return new Row(seq, pts, duration, wall, pdt, null, true, null);
    }

    private static Row v2(long seq, long pts, int duration, long wall, long pdt) {
        return new Row(seq, pts, duration, wall, pdt, 2, true, null);
    }

    @Test
    void 조각_안이면_그_자리이고_셋이_같은_조각에서_나온다() {
        List<Row> rows = List.of(legacy(1, 0, 4_000, 100_000, 100_000L), legacy(2, 4_000, 4_000, 104_900, 104_900L));

        Scene scene = CardPositionReader.scenesOf(rows, List.of(4_500L)).get(4_500L);

        assertThat(scene.seq()).isEqualTo(2);
        assertThat(scene.offsetMs()).isEqualTo(500);
        assertThat(scene.fileHeadMs()).isEqualTo(105_400);
        assertThat(scene.playerAtMs()).isEqualTo(105_400L);
        assertThat(scene.thumbnailOrder()).contains(new ThumbnailOrder(2, 500, OffsetOrigin.FILE_HEAD));
    }

    /** 원점 표지는 행 판이다 — 판 2 행이면 영상 시작(설계 r7 3.2 표 · 3.5). */
    @Test
    void 판_2_조각의_썸네일_주문은_영상_시작_원점이다() {
        List<Row> rows = List.of(v2(1, 0, 4_000, 100_700, 100_000));

        assertThat(CardPositionReader.scenesOf(rows, List.of(1_000L)).get(1_000L).thumbnailOrder())
                .contains(new ThumbnailOrder(1, 1_000, OffsetOrigin.VIDEO_START));
    }

    /** 이어진 틈(장부 틈 · B7)이든 끊긴 틈(재접속)이든 뒤 조각 첫 화면이다(kty 11:27 · 경계 표 10판 B9). */
    @Test
    void 틈_안이면_이어졌든_끊겼든_뒤_조각_첫_화면이다() {
        List<Row> rows = List.of(legacy(10, 200_000, 4_000, 100_000, 100_000L),
                legacy(11, 210_000, 4_000, 110_000, 110_000L));

        Scene scene = CardPositionReader.scenesOf(rows, List.of(204_000L)).get(204_000L);

        assertThat(scene.seq()).isEqualTo(11);
        assertThat(scene.offsetMs()).isZero();
        assertThat(scene.thumbnailOrder()).contains(new ThumbnailOrder(11, 0, OffsetOrigin.FILE_HEAD));
        assertThat(scene.playerAtMs()).isEqualTo(110_000L);
        assertThat(scene.fileHeadMs()).isEqualTo(110_000);
    }

    /** 비귀속 행은 회차 행 열에 없다 — 그 자리 위치(채팅 카드)는 두 귀속 행 사이 틈이라 뒤 귀속 행 첫 화면이다. */
    @Test
    void 비귀속_행_자리는_뒤_귀속_행_첫_화면이다() {
        List<Row> rows = List.of(legacy(20, 300_000, 4_000, 300_000, 300_000L),
                legacy(22, 308_000, 4_000, 308_000, 312_000L));

        Scene scene = CardPositionReader.scenesOf(rows, List.of(305_500L)).get(305_500L);

        assertThat(scene.seq()).isEqualTo(22);
        assertThat(scene.offsetMs()).isZero();
    }

    @Test
    void 첫_귀속_행_앞이면_첫_행_첫_화면이다() {
        List<Row> rows = List.of(legacy(10, 200_000, 4_000, 100_000, 100_000L));

        Scene scene = CardPositionReader.scenesOf(rows, List.of(0L)).get(0L);

        assertThat(scene.seq()).isEqualTo(10);
        assertThat(scene.offsetMs()).isZero();
    }

    /** 라이브 머리(다음 행이 아직 없음)는 장면이 없다 — 셋 모두 없다(웹은 지금 식으로 떨어진다). */
    @Test
    void 마지막_귀속_행_뒤면_장면이_없다() {
        List<Row> rows = List.of(legacy(10, 200_000, 4_000, 100_000, 100_000L));

        assertThat(CardPositionReader.scenesOf(rows, List.of(204_000L))).doesNotContainKey(204_000L);
    }

    @Test
    void 행이_없으면_장면이_없다() {
        assertThat(CardPositionReader.scenesOf(List.of(), List.of(1L))).isEmpty();
    }

    @Test
    void PDT가_빈_행이면_플레이어_값만_없다() {
        List<Row> rows = List.of(legacy(1, 0, 4_000, 100_000, null));

        Scene scene = CardPositionReader.scenesOf(rows, List.of(1_000L)).get(1_000L);

        assertThat(scene.playerAtMs()).isNull();
        assertThat(scene.fileHeadMs()).isEqualTo(101_000);
        assertThat(scene.thumbnailOrder()).isPresent();
    }

    @Test
    void 아직_안_올라간_조각이면_썸네일_주문만_없다() {
        List<Row> rows = List.of(new Row(1, 0, 4_000, 100_000, 100_000L, null, false, null));

        Scene scene = CardPositionReader.scenesOf(rows, List.of(1_000L)).get(1_000L);

        assertThat(scene.thumbnailOrder()).isEmpty();
        assertThat(scene.playerAtMs()).isEqualTo(101_000L);
    }

    /** B8 부분 겹침(K1-c 999ms) — 겹친 구간 카드는 나중 조각이다. */
    @Test
    void 겹친_구간_카드는_나중_조각이다() {
        List<Row> rows = List.of(v2(1, 0, 4_000, 100_000, 100_000), v2(2, 3_001, 4_000, 103_001, 103_001));

        Scene scene = CardPositionReader.scenesOf(rows, List.of(3_500L)).get(3_500L);

        assertThat(scene.seq()).isEqualTo(2);
        assertThat(scene.offsetMs()).isEqualTo(499);
        assertThat(scene.playerAtMs()).isEqualTo(103_500L);
    }

    @Test
    void 값_여럿을_한_번에_푼다() {
        List<Row> rows = List.of(legacy(1, 0, 4_000, 100_000, 100_000L), legacy(2, 4_000, 4_000, 104_000, 104_000L));

        Map<Long, Scene> scenes = CardPositionReader.scenesOf(rows, List.of(1_000L, 5_000L, 9_000L));

        assertThat(scenes).containsOnlyKeys(1_000L, 5_000L);
        assertThat(scenes.get(1_000L).seq()).isEqualTo(1);
        assertThat(scenes.get(5_000L).seq()).isEqualTo(2);
    }
}
