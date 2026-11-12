package com.pokeclip.clip.segment;

import com.pokeclip.clip.recipe.RecipeDocument;
import com.pokeclip.clip.segment.CutDecision.End;
import com.pokeclip.clip.segment.CutDecision.Gap;
import com.pokeclip.clip.segment.CutDecision.LedgerNeighbor;
import com.pokeclip.clip.segment.CutDecision.NotReady;
import com.pokeclip.clip.segment.CutDecision.Prime;
import com.pokeclip.clip.segment.CutDecision.PrimeSkip;
import com.pokeclip.clip.segment.CutDecision.Ready;
import com.pokeclip.clip.segment.CutDecision.Reason;
import com.pokeclip.clip.segment.CutDecision.RunHead;
import com.pokeclip.clip.segment.CutDecision.SubtitlePoint.AtMedia;
import com.pokeclip.clip.segment.CutDecision.SubtitlePoint.InGap;
import com.pokeclip.clip.segment.CutDecision.SubtitleSpan;
import com.pokeclip.clip.segment.CutDecision.Unrenderable;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 렌더 주문의 컷 판정(POK-196 · 설계 r7 9.2 · 9.4 · 16.2 P11 · P19 · P30). 순수 판정이라 DB 없이 잰다 — 장부 이웃(끝 소리 확보
 * 후보 · 디코더 준비 후보)은 {@link FakeLedger}가 준다.
 *
 * <p>시각은 epoch ms 대신 작은 값을 쓴다. 축은 목록 PDT({@code COALESCE(playback_pdt, start_wall_utc)})이다 — 행 짓개의 기본은
 * PDT = 파일명 시각이라, 두 축을 갈라야 하는 시험만 PDT 를 따로 준다.
 */
class CutPlannerTest {

    private static final Instant NOW = Instant.ofEpochMilli(1_000_000);

    // ── 범위 가르기 ─────────────────────────────────────────────

    @Test
    void 회차_행이_없으면_끝난_회차는_녹화_밖이고_안_끝났으면_아직이다() {
        assertThat(plan(List.of(), 1_000, 6_000, true)).isEqualTo(new Unrenderable(Reason.OUTSIDE_RECORDING));
        assertThat(plan(List.of(), 1_000, 6_000, false)).isInstanceOf(NotReady.class);
    }

    /** 회차 첫 행 앞은 뒤에 생기지 않는다 — 안 끝난 회차라도 종국 거절이다. */
    @Test
    void 컷이_회차_첫_행_앞에서_시작하면_녹화_밖이다() {
        List<RoundRow> rows = List.of(row(1, 10_000, 4_000).build());

        assertThat(plan(rows, 9_000, 12_000, false)).isEqualTo(new Unrenderable(Reason.OUTSIDE_RECORDING));
    }

    @Test
    void 컷_끝이_마지막_행_뒤면_끝난_회차는_녹화_밖이고_안_끝났으면_아직이다() {
        List<RoundRow> rows = List.of(row(1, 10_000, 4_000).build());

        assertThat(plan(rows, 11_000, 15_000, true)).isEqualTo(new Unrenderable(Reason.OUTSIDE_RECORDING));
        assertThat(plan(rows, 11_000, 15_000, false)).isEqualTo(new NotReady("not_yet_recorded"));
    }

    /**
     * 범위의 종국 거절은 회차 완결 술어만 본다. 침묵 정리는 회차 완결 확인 없이 방송을 {@code ended} 로 만드므로, 방송이 끝났어도
     * 회차가 안 닫혔으면 아직 안 온 꼬리 · 첫 행은 기다린다(되돌릴 수 없는 422 가 아니다).
     */
    @Test
    void 방송만_끝나고_회차가_안_닫혔으면_범위는_기다린다() {
        List<RoundRow> rows = List.of(row(1, 10_000, 4_000).first().build());

        assertThat(CutPlanner.plan(input(rows, 11_000, 15_000, false, true), new FakeLedger()))
                .isEqualTo(new NotReady("not_yet_recorded"));
        assertThat(CutPlanner.plan(input(List.of(), 11_000, 15_000, false, true), new FakeLedger()))
                .isEqualTo(new NotReady("not_yet_recorded"));
    }

    @Test
    void 회차가_닫혔으면_방송_상태와_무관하게_범위_밖은_녹화_밖이다() {
        List<RoundRow> rows = List.of(row(1, 10_000, 4_000).first().build());

        assertThat(CutPlanner.plan(input(rows, 11_000, 15_000, true, false), new FakeLedger()))
                .isEqualTo(new Unrenderable(Reason.OUTSIDE_RECORDING));
        assertThat(CutPlanner.plan(input(List.of(), 11_000, 15_000, true, false), new FakeLedger()))
                .isEqualTo(new Unrenderable(Reason.OUTSIDE_RECORDING));
    }

    /** 끝 소리 확보는 방송 {@code ended} 이거나 회차 완결이면 다음 행 없이 진행한다(계약 6-6-8 5항) — 둘 다 아니면 기다린다. */
    @Test
    void 확보는_방송이_끝났거나_회차가_닫혔으면_다음_행_없이_진행한다() {
        List<RoundRow> rows = List.of(row(1, 0, 4_000).first().build());

        assertThat(CutPlanner.plan(input(rows, 1_000, 3_500, false, true), new FakeLedger()))
                .as("방송만 끝남").isInstanceOfSatisfying(Ready.class, r -> assertThat(r.capture()).isEmpty());
        assertThat(CutPlanner.plan(input(rows, 1_000, 3_500, true, false), new FakeLedger()))
                .as("회차만 닫힘").isInstanceOfSatisfying(Ready.class, r -> assertThat(r.capture()).isEmpty());
        assertThat(CutPlanner.plan(input(rows, 1_000, 3_500, false, false), new FakeLedger()))
                .isEqualTo(new NotReady("capture_pending"));
    }

    // ── 설계 5.1 주문서 예 ───────────────────────────────────────

    /**
     * 장부 100(A 의 장부 바로 앞 행 · 같은 녹화) · A=101 · B=102(끊긴 이웃 — 2초 틈) · C=103(이어짐). 컷 [101000, 112000) · 자막 둘
     * (하나는 틈을 걸침 · 하나는 틈 안 통째).
     */
    @Test
    void 설계_예_주문은_좌표_자막_길이_준비_조각이_예와_같다() {
        List<RoundRow> rows = List.of(
                row(101, 100_000, 4_000).first().build(),
                row(102, 106_000, 4_000).discontinuity().build(),
                row(103, 110_000, 4_000).build());
        FakeLedger ledger = new FakeLedger().before(101, neighbor(100, RoundRow.UPLOADED));
        List<RecipeDocument.Segment> subtitles = List.of(
                new RecipeDocument.Segment(101_500L, 106_500L, "걸침"),
                new RecipeDocument.Segment(104_500L, 105_500L, "틈 안"));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 101_000, 112_000, true, subtitles), ledger);

        assertThat(ready.in()).isEqualTo(new MediaCoord(101, 1_000_000));
        assertThat(ready.out()).isEqualTo(new End(103, 2_000_000L));
        assertThat(ready.media()).extracting(RoundRow::seq).containsExactly(101L, 102L, 103L);
        assertThat(ready.subtitles()).containsExactly(
                new SubtitleSpan(0, new AtMedia(new MediaCoord(101, 1_500_000)), new AtMedia(new MediaCoord(102, 500_000))),
                new SubtitleSpan(1, new InGap(101, 500), new InGap(101, 1_500)));
        assertThat(ready.lengthMs()).isEqualTo(9_000);
        assertThat(ready.gaps()).containsExactly(new Gap(101, 102, Gap.Location.MIDDLE, 2_000, 2_000));
        assertThat(ready.primes()).containsExactly(new Prime(101, neighbor(100, RoundRow.UPLOADED)));
        assertThat(ready.runHeads()).containsExactly(
                new RunHead(101, RunHead.Kind.CONTINUED), new RunHead(102, RunHead.Kind.NEW_RECORDING));
        assertThat(ready.capture()).as("판 NULL · 다음 행 없음 · 끝난 회차 → 확보 없이 진행").isEmpty();
        assertThat(ledger.askedBefore).as("새 녹화 머리 102 는 앞 행을 묻지 않는다").containsExactly(List.of(101L));
    }

    // ── 틈 판정 · 좌표(P11) ─────────────────────────────────────

    /**
     * P11 반례 — 컷이 끊긴 틈 안에서 시작하면 틈 판정은 좌표로 옮기기 <b>전</b> 원래 범위로 한다: 머리 틈이 기록되고 시작은
     * {@code (B, 0)}, 틈 안에서 시작한 자막은 틈 자리 끝점이다. 좌표 뒤 범위({@code [B 시작, out)})로 재면 머리 틈이 사라진다.
     */
    @Test
    void 컷이_끊긴_틈_안에서_시작하면_머리_틈을_기록하고_뒤_조각_머리에서_시작한다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(2, 5_000, 4_000).discontinuity().build());
        List<RecipeDocument.Segment> subtitles = List.of(new RecipeDocument.Segment(4_200L, 6_000L, "머리 틈"));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 4_400, 8_000, true, subtitles), new FakeLedger());

        assertThat(ready.gaps()).containsExactly(new Gap(1, 2, Gap.Location.HEAD, 1_000, 600));
        assertThat(ready.in()).isEqualTo(new MediaCoord(2, 0));
        assertThat(ready.media()).extracting(RoundRow::seq).containsExactly(2L);
        assertThat(ready.subtitles()).containsExactly(
                new SubtitleSpan(0, new InGap(1, 400), new AtMedia(new MediaCoord(2, 1_000_000))));
        assertThat(ready.lengthMs()).isEqualTo(3_600 - 600);
    }

    @Test
    void 컷이_끊긴_틈_안에서_끝나면_꼬리_틈이고_앞_조각_끝까지다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(2, 5_000, 4_000).discontinuity().build());

        Ready ready = (Ready) CutPlanner.plan(input(rows, 1_000, 4_500, true), new FakeLedger());

        assertThat(ready.out()).isEqualTo(new End(1, null));
        assertThat(ready.gaps()).containsExactly(new Gap(1, 2, Gap.Location.TAIL, 1_000, 500));
        assertThat(ready.lengthMs()).isEqualTo(3_000);
    }

    @Test
    void 컷이_틈_하나_안에_통째로_들면_영상_조각_없음이다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(2, 6_000, 4_000).discontinuity().build());

        assertThat(plan(rows, 4_200, 5_800, true)).isEqualTo(new Unrenderable(Reason.NO_FOOTAGE));
    }

    /** B7 — 같은 녹화로 이어진 이웃 사이 장부 틈은 틈이 아니다(F 3항이 뒤 조각 머리를 준다). 길이에서 빼지 않는다. */
    @Test
    void 이어진_이웃_사이_장부_틈은_틈이_아니고_뒤_조각_머리다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(2, 4_600, 4_000).build());

        Ready ready = (Ready) CutPlanner.plan(input(rows, 4_300, 7_000, true), new FakeLedger());

        assertThat(ready.in()).isEqualTo(new MediaCoord(2, 0));
        assertThat(ready.gaps()).isEmpty();
        assertThat(ready.lengthMs()).isEqualTo(2_700);
    }

    /**
     * 이어진 틈 안에서 시작한 컷의 자막 — 끝 F끝 은 이어진 틈 안 끝을 앞 조각 끝 {@code (A, dur)} 로 내는데 A 는 계획 밖이다(렌더
     * 5.3 규칙 5 거절). 같은 매체 자리의 계획 안 표기 {@code (B, 0)} 로 낸다 — 결과 길이 0 이라 렌더가 버린다.
     */
    @Test
    void 이어진_틈_안에서_시작한_컷의_자막_끝점은_계획_안_조각을_가리킨다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(2, 4_600, 4_000).build(),
                row(3, 9_200, 4_000).build());
        List<RecipeDocument.Segment> subtitles = List.of(new RecipeDocument.Segment(4_400L, 4_500L, "틈 안"));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 4_300, 10_300, true, subtitles), new FakeLedger());

        assertThat(ready.media()).extracting(RoundRow::seq).containsExactly(2L, 3L);
        assertThat(ready.subtitles()).containsExactly(
                new SubtitleSpan(0, new AtMedia(new MediaCoord(2, 0)), new AtMedia(new MediaCoord(2, 0))));
        assertAllInPlan(ready);
    }

    /** 꼬리 대칭 — 컷이 이어진 틈 안에서 끝나면 틈 안 자막의 시작 F 가 뒤 조각 {@code (C, 0)} 이다. 계획 안 표기는 {@code (B, dur)}. */
    @Test
    void 이어진_틈_안에서_끝난_컷의_자막_시작점은_계획_안_조각을_가리킨다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(2, 4_600, 4_000).build(),
                row(3, 9_200, 4_000).build());
        List<RecipeDocument.Segment> subtitles = List.of(new RecipeDocument.Segment(8_700L, 8_800L, "틈 안"));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 1_000, 8_800, true, subtitles), new FakeLedger());

        assertThat(ready.out()).isEqualTo(new End(2, 4_000_000L));
        assertThat(ready.media()).extracting(RoundRow::seq).containsExactly(1L, 2L);
        assertThat(ready.subtitles()).containsExactly(new SubtitleSpan(0,
                new AtMedia(new MediaCoord(2, 4_000_000)), new AtMedia(new MediaCoord(2, 4_000_000))));
        assertAllInPlan(ready);
    }

    /**
     * 컷과 겹치지 않는 자막은 싣지 않고 나머지는 레시피 안 자리({@code index})를 지킨다. 컷 시작에 끝이 닿기만 한 자막(반개 구간이라
     * 겹침 0)도 싣지 않는다.
     */
    @Test
    void 컷_밖_자막을_빼도_나머지_자막은_레시피_자리를_지킨다() {
        List<RoundRow> rows = List.of(row(1, 0, 4_000).first().build(), row(2, 4_000, 4_000).build());
        List<RecipeDocument.Segment> subtitles = List.of(
                new RecipeDocument.Segment(100L, 500L, "컷 앞"),
                new RecipeDocument.Segment(500L, 1_000L, "컷 시작에 닿기만"),
                new RecipeDocument.Segment(2_000L, 3_000L, "컷 안"));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 1_000, 6_000, true, subtitles), new FakeLedger());

        assertThat(ready.subtitles()).extracting(SubtitleSpan::index).containsExactly(2);
    }

    /** B8 · K1-c — 판 2 두 조각이 999ms 겹치면 겹친 구간은 나중 조각이다. */
    @Test
    void 겹친_999ms_구간에서_시작하면_나중_조각이다() {
        List<RoundRow> rows = List.of(
                row(1, 100_000, 4_000).first().v2().build(),
                row(2, 103_001, 4_000).v2().build());

        Ready ready = (Ready) CutPlanner.plan(input(rows, 103_500, 106_000, true), new FakeLedger());

        assertThat(ready.in()).isEqualTo(new MediaCoord(2, 499_000));
        assertThat(ready.media()).extracting(RoundRow::seq).containsExactly(2L);
    }

    /**
     * 999ms 겹침의 경계 — 겹침 바로 앞은 앞 조각, 겹침 시작은 뒤 조각 머리다. 끝은 F끝 이라 뒤 조각 시작과 같은 끝은 앞 조각 끝
     * {@code (1, dur)}(맞닿은 다음 컷의 {@code (2, 0)} 과 사이 매체가 빠지지 않게), 앞 조각 끝과 같은 끝은 뒤 조각 안이다.
     */
    @Test
    void 겹친_999ms_구간의_두_끝_경계() {
        List<RoundRow> rows = List.of(
                row(1, 100_000, 4_000).first().v2().build(),
                row(2, 103_001, 4_000).v2().build());

        Ready before = (Ready) CutPlanner.plan(input(rows, 103_000, 103_001, true), new FakeLedger());
        Ready atEnd = (Ready) CutPlanner.plan(input(rows, 101_000, 104_000, true), new FakeLedger());

        assertThat(before.in()).isEqualTo(new MediaCoord(1, 3_000_000));
        assertThat(before.out()).isEqualTo(new End(1, 4_000_000L));
        assertThat(atEnd.out()).isEqualTo(new End(2, 999_000L));
    }

    /** 비귀속 u 가 낀 j → u → k — 회차 행 열에서 k 는 j 의 장부 이웃이 아니라 끊긴 이웃이고, k 묶음은 u 로 디코더를 채운다. */
    @Test
    void 비귀속_행이_낀_이웃은_끊긴_틈이고_뒤_머리는_그_행으로_준비한다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(3, 4_300, 4_000).notLedgerNeighbor().build());
        FakeLedger ledger = new FakeLedger()
                .before(1, neighbor(0, RoundRow.UPLOADED))
                .before(3, neighbor(2, RoundRow.UPLOADED));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 1_000, 7_000, true), ledger);

        assertThat(ready.gaps()).containsExactly(new Gap(1, 3, Gap.Location.MIDDLE, 300, 300));
        assertThat(ready.primes()).containsExactly(
                new Prime(1, neighbor(0, RoundRow.UPLOADED)),
                new Prime(3, neighbor(2, RoundRow.UPLOADED)));
        assertThat(ready.lengthMs()).isEqualTo(6_000 - 300);
        assertThat(ledger.askedBefore).as("머리 둘의 앞 행을 한 번에 묻는다").containsExactly(List.of(1L, 3L));
    }

    // ── 축 · 원점(P19 · P5 — kty 2026-11-12 D1) ──────────────────

    /**
     * P19 · P5 — 판 2 회차는 PDT 축이고 원점은 영상 첫 표본({@code VIDEO_START})이다. PDT 를 파일명 시각보다 120초 어긋낸 시드라
     * 파일명 시각으로 고르면 행 33 · 35 가 된다(PR-B1 꼴 복귀 변이가 여기서 FAIL).
     */
    @Test
    void 판_2_회차는_PDT_축으로_고르고_원점은_영상_첫_표본이다() {
        List<RoundRow> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            RowBuilder b = row(i + 1, 4_000L * i, 4_000).pdt(4_000L * i + 120_000).v2();
            rows.add((i == 0 ? b.first() : b).build());
        }

        Ready ready = (Ready) CutPlanner.plan(input(rows, 130_500, 138_000, true), new FakeLedger());

        assertThat(ready.in()).isEqualTo(new MediaCoord(3, 2_500_000));
        assertThat(ready.out()).isEqualTo(new End(5, 2_000_000L));
        assertThat(ready.offsetOrigin()).isEqualTo(OffsetOrigin.VIDEO_START);
    }

    /**
     * P19 · P5 — 판 NULL 래칫 회차(PDT 가 파일명 시각보다 앞섬)도 화면과 같은 PDT 축이고 원점은 파일 머리({@code FILE_HEAD})다.
     * 좌표 · 자막 끝점 · 끊긴 틈 · 범위가 모두 같은 래칫 축이어야 한다 — 「자막만 파일명 시각」 · 「틈만 파일명 시각」 변이가 자막 ·
     * 틈 단언에서 FAIL 한다.
     *
     * <p>시드: 행 1–10 · 파일명 시각 3800i · PDT 4000i(행 6 부터 끊긴 새 녹화라 PDT +1500). PDT 축에서 행 5 끝 20000 과 행 6
     * 시작 21500 사이가 1500ms 틈이고, 파일명 시각 축에서는 두 행이 200ms 겹쳐 틈이 없다.
     */
    @Test
    void 판_NULL_래칫_회차는_좌표_자막_틈이_같은_PDT_축이고_원점은_파일_머리다() {
        List<RecipeDocument.Segment> subtitles = List.of(new RecipeDocument.Segment(21_000L, 22_000L, "틈에서 시작"));

        Ready ready = (Ready) CutPlanner.plan(input(ratchetRows(), 17_000, 27_000, true, subtitles), new FakeLedger());

        assertThat(ready.in()).as("PDT 16000 행(5) — 파일명 시각으로 고르면 행 5 의 1800").isEqualTo(new MediaCoord(5, 1_000_000));
        assertThat(ready.out()).isEqualTo(new End(7, 1_500_000L));
        assertThat(ready.gaps()).containsExactly(new Gap(5, 6, Gap.Location.MIDDLE, 1_500, 1_500));
        assertThat(ready.subtitles()).containsExactly(
                new SubtitleSpan(0, new InGap(5, 1_000), new AtMedia(new MediaCoord(6, 500_000))));
        assertThat(ready.lengthMs()).isEqualTo(10_000 - 1_500);
        assertThat(ready.offsetOrigin()).isEqualTo(OffsetOrigin.FILE_HEAD);
    }

    /**
     * P19 — 범위(마지막 행 뒤)도 같은 래칫 축이다. 마지막 행 10 은 PDT [37500, 41500) · 파일명 시각 [34200, 38200) 이라, 끝
     * 40000 은 PDT 축에서는 행 10 안이고 파일명 시각 축에서는 마지막 행 뒤(닫힌 회차 → 녹화 밖)다.
     */
    @Test
    void 판_NULL_래칫_회차의_범위도_PDT_축이다() {
        Ready ready = (Ready) CutPlanner.plan(input(ratchetRows(), 36_000, 40_000, true), new FakeLedger());

        assertThat(ready.out()).isEqualTo(new End(10, 2_500_000L));
    }

    /** P5 — 한 회차는 한 판이다(계약 6-6-4). 판이 섞이면 장부 불변식 위반이라 원점을 고를 수 없다 — 조용히 한쪽으로 접지 않는다. */
    @Test
    void 회차_안에_판이_섞이면_예외다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().v2().build(),
                row(2, 4_000, 4_000).build());

        assertThatThrownBy(() -> plan(rows, 1_000, 6_000, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("판");
    }

    @Test
    void 계획_신호에_축과_원점을_남긴다() {
        List<RoundRow> rows = List.of(row(1, 0, 4_000).first().v2().build(), row(2, 4_000, 4_000).v2().build());

        try (LogCaptor logs = new LogCaptor()) {
            CutPlanner.plan(input(rows, 1_000, 6_000, true), new FakeLedger());

            assertThat(logs.messages()).anyMatch(m -> m.startsWith(
                    "clip.render.cut_planned streamId=s-1 recipeId=7 axis=pdt origin=VIDEO_START "));
        }
    }

    // ── 웹 컷 식과의 경계(C-B1 · C-B3 · C-B5) ───────────────────────

    /**
     * C-B1 — 웹 r5 컷 식은 in · out 을 ms 로 내린다(30fps 프레임 F = 7 · {@code p_F − D0} = 233.33…ms → in 233 · F 끝 266.66…ms →
     * out 266). clip 은 그 ms 를 더하거나 빼지 않고 µs 로만 옮긴다. 렌더(판 2 · {@code VIDEO_START})의 시작 「PTS − D0 ≤ x 인
     * 마지막」 은 F 바로 앞(6)이고 끝 「PTS − D0 < x_out」 은 F 까지다 — 「= F」 로 단정하지 않는다(머리에 앞 프레임 하나가 더
     * 들어갈 수 있다 · kty A2).
     */
    @Test
    void 웹이_내린_ms_컷은_그대로_옮겨_렌더가_F_또는_F_앞에서_시작해_F_에서_끝난다() {
        List<RoundRow> rows = List.of(row(1, 100_000, 4_000).first().v2().build());
        long frame = 7;

        Ready ready = (Ready) CutPlanner.plan(input(rows, 100_000 + floorMs(frame), 100_000 + floorMs(frame + 1), true),
                new FakeLedger());

        assertThat(ready.in()).isEqualTo(new MediaCoord(1, 233_000));
        assertThat(ready.out()).isEqualTo(new End(1, 266_000L));
        assertThat(lastFrameAtOrBefore(ready.in().offsetUs())).isBetween(frame - 1, frame);
        assertThat(lastFrameBefore(ready.out().offsetUs())).isEqualTo(frame);
    }

    /**
     * C-B3 · 웹 경계 시드(오케스트레이터 2026-11-12 정정 — 웹 K1) — 웹은 out 이 장부 틈 안에 떨어져도 맞추지 않고 그 T 를 그대로
     * 보낸다(여기 T = 4050 · k 끝 4000 · 뒤 행 시작 4100). 세 갈래 모두 k 를 끝까지 담는다.
     * <ol>
     *   <li>이어진 틈(같은 녹화 · 장부 시각만 벌어짐): F끝 이 k 끝 {@code (k, 4000ms)} 로 접는다 — 렌더 끝 배타
     *       {@code PTS − D0 < x_out} 가 판 2({@code VIDEO_START})에서 k 의 마지막 30fps 프레임(119)까지 담는다. 판 NULL
     *       ({@code FILE_HEAD})은 렌더가 m 을 빼 끝이 당겨지므로 꼬리 프레임 하나가 빠질 수 있다(이 시험 밖 · 렌더 몫)</li>
     *   <li>회차 안 끊긴 틈(k+1 {@code is_discontinuity} 참 · 장부 이웃): 틈 → {@code toSegmentEnd} — 렌더가 마지막 프레임 끝까지
     *       보존한다</li>
     *   <li>비귀속 행이 낀 자리(k+2 의 이웃 표시 거짓): 2 와 같다</li>
     * </ol>
     */
    @Test
    void 웹_out_이_틈_안_그대로면_갈래마다_k_를_끝까지_담는다() {
        for (boolean v2 : List.of(true, false)) {
            List<RoundRow> continued = versioned(v2, row(1, 0, 4_000).first(), row(2, 4_100, 4_000));
            List<RoundRow> broken = versioned(v2, row(1, 0, 4_000).first(), row(2, 4_100, 4_000).discontinuity());
            List<RoundRow> unattributed = versioned(v2, row(1, 0, 4_000).first(),
                    row(3, 4_100, 4_000).notLedgerNeighbor());

            Ready joined = (Ready) CutPlanner.plan(input(continued, 1_000, 4_050, true), new FakeLedger());
            Ready cut = (Ready) CutPlanner.plan(input(broken, 1_000, 4_050, true), new FakeLedger());
            Ready skipped = (Ready) CutPlanner.plan(input(unattributed, 1_000, 4_050, true), new FakeLedger());

            assertThat(joined.out()).as("판 2=%s 이어진 틈", v2).isEqualTo(new End(1, 4_000_000L));
            assertThat(joined.gaps()).isEmpty();
            assertThat(cut.out()).as("판 2=%s 끊긴 틈", v2).isEqualTo(new End(1, null));
            assertThat(cut.gaps()).containsExactly(new Gap(1, 2, Gap.Location.TAIL, 100, 50));
            assertThat(skipped.out()).as("판 2=%s 비귀속 낀 자리", v2).isEqualTo(new End(1, null));
            assertThat(skipped.gaps()).containsExactly(new Gap(1, 3, Gap.Location.TAIL, 100, 50));
            for (Ready ready : List.of(joined, cut, skipped)) {
                assertThat(ready.media()).extracting(RoundRow::seq).containsExactly(1L);
            }
        }
        assertThat(lastFrameBefore(4_000_000)).as("판 2 이어진 틈 — k 의 마지막 프레임까지").isEqualTo(119);
    }

    /**
     * 웹 경계 시드(같은 정정) — 회차 마지막 행 뒤로 나간 out 은 범위 밖(F끝 AfterLast)이다: 닫힌 회차는 녹화 밖 422 · 안 닫힌 회차는
     * 「아직 녹화 안 됨」 409. 그 조각 끝 값({@code PDT_k + duration_ms(k)})으로 맞춘 out 은 통과하고 k 를 끝까지 담는다.
     */
    @Test
    void 웹_out_이_마지막_행_뒤면_범위_밖이고_조각_끝이면_통과한다() {
        List<RoundRow> rows = List.of(row(1, 0, 4_000).first().build());

        assertThat(plan(rows, 1_000, 4_050, true)).isEqualTo(new Unrenderable(Reason.OUTSIDE_RECORDING));
        assertThat(plan(rows, 1_000, 4_050, false)).isEqualTo(new NotReady("not_yet_recorded"));
        assertThat(plan(rows, 1_000, 4_000, true)).isInstanceOfSatisfying(Ready.class,
                ready -> assertThat(ready.out()).isEqualTo(new End(1, 4_000_000L)));
    }

    /** C-B5 — 웹은 in 이 뒤 행 PDT 이상이면 {@code PDT_{k+1} − 1} 로 접어 보낸다. 그 값은 앞 조각 안 마지막 ms 다. */
    @Test
    void 웹이_접은_in_은_앞_조각_안_마지막_ms_다() {
        List<RoundRow> rows = List.of(row(1, 0, 4_000).first().build(), row(2, 4_000, 4_000).build());

        Ready ready = (Ready) CutPlanner.plan(input(rows, 3_999, 6_000, true), new FakeLedger());

        assertThat(ready.in()).isEqualTo(new MediaCoord(1, 3_999_000));
    }

    // ── 준비 ─────────────────────────────────────────────────────

    @Test
    void 결과에_드는_조각이_아직_안_올라갔으면_아직이다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(2, 4_000, 4_000).pending().build());

        assertThat(plan(rows, 1_000, 6_000, true)).isEqualTo(new NotReady("media_pending"));
    }

    @Test
    void 끝_소리_확보_조각을_싣고_아직이면_기다린다() {
        List<RoundRow> rows = List.of(row(1, 0, 4_000).first().build());
        RoundRow nextUp = row(2, 4_000, 4_000).build();

        Ready ready = (Ready) CutPlanner.plan(input(rows, 1_000, 3_500, false), new FakeLedger().after(1, nextUp));

        assertThat(ready.capture()).contains(nextUp);
        assertThat(CutPlanner.plan(input(rows, 1_000, 3_500, false),
                new FakeLedger().after(1, row(2, 4_000, 4_000).pending().build())))
                .isEqualTo(new NotReady("capture_pending"));
    }

    @Test
    void 확보_후보가_15분_넘게_failed_면_없이_진행하고_신호를_남긴다() {
        List<RoundRow> rows = List.of(row(1, 0, 4_000).first().build());
        RoundRow failed = row(2, 4_000, 4_000).failed().build();
        CutPlanner.CutInput late = new CutPlanner.CutInput("s-1", 7L, rows, 1_000, 3_500, List.of(), false, false,
                Instant.ofEpochMilli(4_000).plus(Duration.ofMinutes(16)));

        try (LogCaptor logs = new LogCaptor()) {
            Ready ready = (Ready) CutPlanner.plan(late, new FakeLedger().after(1, failed));

            assertThat(ready.capture()).isEmpty();
            assertThat(logs.messages()).contains("clip.render.capture_skipped streamId=s-1 seq=2 reason=failed ageMin=16");
        }
    }

    /** 확보 없이 진행했다는 신호는 계획이 나올 때만 남긴다 — 준비 조각이 아직이라 409 를 다시 물을 때마다 같은 줄이 쌓이지 않게. */
    @Test
    void 확보_생략_신호는_계획이_나올_때만_남긴다() {
        List<RoundRow> rows = List.of(row(1, 0, 4_000).first().build());
        RoundRow failed = row(2, 4_000, 4_000).failed().build();
        CutPlanner.CutInput late = new CutPlanner.CutInput("s-1", 7L, rows, 1_000, 3_500, List.of(), false, false,
                Instant.ofEpochMilli(4_000).plus(Duration.ofMinutes(16)));

        try (LogCaptor logs = new LogCaptor()) {
            CutDecision decision = CutPlanner.plan(late, new FakeLedger().after(1, failed)
                    .before(1, neighbor(0, RoundRow.PENDING)));

            assertThat(decision).isEqualTo(new NotReady("prime_pending"));
            assertThat(logs.messages()).noneMatch(m -> m.startsWith("clip.render.capture_skipped"));
        }
    }

    // ── 디코더 준비(P30 판정) ───────────────────────────────────

    /** P30 ① — 회차 첫 행 k 의 장부 바로 앞 행 u 가 비귀속이어도 같은 녹화면 준비 조각이다. */
    @Test
    void P30_1_회차_첫_행의_앞_비귀속_행을_준비로_싣는다() {
        Ready ready = (Ready) CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().before(10, neighbor(9, RoundRow.UPLOADED)));

        assertThat(ready.primes()).containsExactly(new Prime(10, neighbor(9, RoundRow.UPLOADED)));
        assertThat(ready.runHeads()).containsExactly(new RunHead(10, RunHead.Kind.CONTINUED));
    }

    @Test
    void P30_2_준비_조각이_아직이면_기다린다() {
        assertThat(CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().before(10, neighbor(9, RoundRow.PENDING))))
                .isEqualTo(new NotReady("prime_pending"));
    }

    @Test
    void P30_3_준비_조각이_failed_면_기다리지_않고_없이_진행한다() {
        Ready ready = (Ready) CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().before(10, neighbor(9, RoundRow.FAILED)));

        assertThat(ready.primes()).isEmpty();
        assertThat(ready.primeSkips()).containsExactly(new PrimeSkip(10, 9L, "failed"));
    }

    @Test
    void P30_4_머리가_새_녹화면_앞_행을_묻지_않고_준비가_없다() {
        List<RoundRow> rows = List.of(row(10, 10_000, 4_000).first().discontinuity().build());
        FakeLedger ledger = new FakeLedger().before(10, neighbor(9, RoundRow.UPLOADED));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 11_000, 13_000, true), ledger);

        assertThat(ready.primes()).isEmpty();
        assertThat(ready.runHeads()).containsExactly(new RunHead(10, RunHead.Kind.NEW_RECORDING));
        assertThat(ledger.askedBefore).isEmpty();
    }

    /** P30 ⑤ — A → 비귀속 U → B. 둘째 묶음 머리 B 도 같은 규칙으로 U 를 준비로 싣는다(첫 묶음 전용이 아니다). */
    @Test
    void P30_5_둘째_묶음_머리도_앞_비귀속_행을_준비로_싣는다() {
        List<RoundRow> rows = List.of(
                row(5, 0, 4_000).first().discontinuity().build(),
                row(7, 8_000, 4_000).notLedgerNeighbor().build());
        FakeLedger ledger = new FakeLedger().before(7, neighbor(6, RoundRow.UPLOADED));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 1_000, 10_000, true), ledger);

        assertThat(ready.primes()).containsExactly(new Prime(7, neighbor(6, RoundRow.UPLOADED)));
        assertThat(ready.runHeads()).containsExactly(
                new RunHead(5, RunHead.Kind.NEW_RECORDING), new RunHead(7, RunHead.Kind.CONTINUED));
    }

    /**
     * P30 ⑥ — 앞 행이 이 방송의 장부 구간 시작 앞이면 앞 방송 몫이라 쓰지 않는다(5-2 소비자 규약). 장부 읽기가 구간 경계를 조회
     * 단계에서 걸어 그 행을 아예 주지 않으므로(K4), 판정은 「앞 방송이 있는데 구간 안 앞 행이 없다」 로 안다 — 그 행의 seq 도 모른다.
     */
    @Test
    void P30_6_앞_행이_방송_장부_구간_시작_앞이면_다른_방송_몫이라_안_싣는다() {
        CutDecision result = CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().previousBroadcast());

        assertThat(result).isInstanceOfSatisfying(Ready.class, ready -> {
            assertThat(ready.primes()).isEmpty();
            assertThat(ready.primeSkips()).containsExactly(new PrimeSkip(10, null, "other_broadcast"));
            assertThat(ready.runHeads()).containsExactly(new RunHead(10, RunHead.Kind.CONTINUED));
        });
    }

    /**
     * K2 — 앞 방송이 없을 때만 「앞 행 없음」 이 진짜 시작이다. 앞 방송이 있으면 구간 안 앞 행이 없는 머리는 앞 방송이 만료돼 그 행이
     * 지워졌든 남았든 같은 열화({@code other_broadcast})로 센다 — 만료 전후로 집계 칸이 바뀌지 않는다.
     */
    @Test
    void 앞_방송이_있으면_구간_안_앞_행이_없어도_진짜_시작으로_세지_않는다() {
        Ready none = (Ready) CutPlanner.plan(input(oneRow(), 11_000, 13_000, true), new FakeLedger());
        Ready afterBroadcast = (Ready) CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().previousBroadcast());

        assertThat(none.runHeads()).containsExactly(new RunHead(10, RunHead.Kind.NO_PREV_ROW));
        assertThat(none.primeSkips()).isEmpty();
        assertThat(afterBroadcast.runHeads()).containsExactly(new RunHead(10, RunHead.Kind.CONTINUED));
        assertThat(afterBroadcast.primeSkips()).containsExactly(new PrimeSkip(10, null, "other_broadcast"));
    }

    /** P30 ⑦ — seq 는 조밀하다고 보장되지 않는다. 장부 바로 앞 행이 h − 3 이어도 그 행이다. */
    @Test
    void P30_7_seq_가_조밀하지_않아도_장부_바로_앞_행을_쓴다() {
        Ready ready = (Ready) CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().before(10, neighbor(7, RoundRow.UPLOADED)));

        assertThat(ready.primes()).containsExactly(new Prime(10, neighbor(7, RoundRow.UPLOADED)));
    }

    @Test
    void 장부에_앞_행이_없으면_진짜_시작이라_준비가_없다() {
        Ready ready = (Ready) CutPlanner.plan(input(oneRow(), 11_000, 13_000, true), new FakeLedger());

        assertThat(ready.primes()).isEmpty();
        assertThat(ready.primeSkips()).isEmpty();
        assertThat(ready.runHeads()).containsExactly(new RunHead(10, RunHead.Kind.NO_PREV_ROW));
    }

    // ── 신호 ─────────────────────────────────────────────────────

    @Test
    void 계획과_거절을_신호로_남긴다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(2, 5_000, 4_000).discontinuity().build());

        try (LogCaptor logs = new LogCaptor()) {
            CutPlanner.plan(input(rows, 1_000, 6_000, true), new FakeLedger().before(1, neighbor(0, RoundRow.UPLOADED)));
            CutPlanner.plan(input(rows, 4_200, 4_800, true), new FakeLedger());

            assertThat(logs.messages()).contains(
                    "clip.render.cut_planned streamId=s-1 recipeId=7 axis=pdt origin=FILE_HEAD gaps=middle:1-2:1000:1000"
                            + " joinedMs=1000 lengthMs=4000 runHeads=1:continued,2:new_recording",
                    "clip.render.cut_rejected streamId=s-1 recipeId=7 reason=no_footage");
        }
    }

    /**
     * 준비 조각을 쓰지 못한 머리(열화)는 계획이 나올 때 {@code clip.render.prime_skipped} 로 센다. 앞 방송 몫은 그 행을 읽지 않아
     * seq 를 모른다({@code seq=-}).
     */
    @Test
    void 준비_생략을_신호로_남긴다() {
        try (LogCaptor logs = new LogCaptor()) {
            CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                    new FakeLedger().before(10, neighbor(9, RoundRow.FAILED)));
            CutPlanner.plan(input(oneRow(), 11_000, 13_000, true), new FakeLedger().previousBroadcast());

            assertThat(logs.messages()).contains(
                    "clip.render.prime_skipped streamId=s-1 seq=9 ownerSeq=10 reason=failed",
                    "clip.render.prime_skipped streamId=s-1 seq=- ownerSeq=10 reason=other_broadcast");
        }
    }

    // ── 도우미 ────────────────────────────────────────────────────

    /** 렌더 계획 검사 5.3 규칙 5 — 자막의 매체 끝점 seq 는 계획 매체 안이다. */
    private static void assertAllInPlan(Ready ready) {
        List<Long> plan = ready.media().stream().map(RoundRow::seq).toList();
        for (SubtitleSpan span : ready.subtitles()) {
            for (var point : List.of(span.start(), span.end())) {
                if (point instanceof AtMedia at) {
                    assertThat(plan).as("자막 %d 끝점 %s", span.index(), point).contains(at.at().seq());
                }
            }
        }
    }

    /** 판 NULL 래칫 시드(위 래칫 시험 셋) — 파일명 시각 3800i · PDT 4000i · 행 6 부터 끊긴 새 녹화(PDT +1500). */
    private static List<RoundRow> ratchetRows() {
        List<RoundRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            RowBuilder b = row(i + 1, 3_800L * i, 4_000).pdt(4_000L * i + (i >= 5 ? 1_500 : 0));
            if (i == 5) {
                b.discontinuity();
            }
            rows.add((i == 0 ? b.first() : b).build());
        }
        return rows;
    }

    /** 행 짓개들을 판 2(참) 또는 판 NULL 로 짓는다. */
    private static List<RoundRow> versioned(boolean v2, RowBuilder... builders) {
        List<RoundRow> rows = new ArrayList<>();
        for (RowBuilder b : builders) {
            rows.add((v2 ? b.v2() : b).build());
        }
        return rows;
    }

    /** 30fps 프레임 n 의 표시 시각(D0 기준)을 ms 로 내린 값 — 웹 r5 컷 식의 {@code floor(1000·(p_F − D0))}. */
    private static long floorMs(long frame) {
        return frame * 1000 / 30;
    }

    /** 렌더 시작 규칙(설계 7.3) — {@code PTS − D0 ≤ x} 인 마지막 30fps 프레임. 정수식: {@code n/30 s ≤ x µs ⇔ n·10⁶ ≤ 30·x}. */
    private static long lastFrameAtOrBefore(long offsetUs) {
        return Math.floorDiv(30 * offsetUs, 1_000_000);
    }

    /** 렌더 끝 규칙 — {@code PTS − D0 < x_out} 인 마지막 30fps 프레임. */
    private static long lastFrameBefore(long offsetUs) {
        return Math.floorDiv(30 * offsetUs - 1, 1_000_000);
    }

    private static List<RoundRow> oneRow() {
        return List.of(row(10, 10_000, 4_000).first().build());
    }

    private static CutDecision plan(List<RoundRow> rows, long in, long out, boolean ended) {
        return CutPlanner.plan(input(rows, in, out, ended), new FakeLedger());
    }

    private static CutPlanner.CutInput input(List<RoundRow> rows, long in, long out, boolean ended) {
        return input(rows, in, out, ended, List.of());
    }

    /**
     * 회차 완결과 방송 {@code ended} 에 같은 값을 넣는다. 두 값이 갈리는 효과는 따로 주는 아래 꼴의 시험 셋(범위 대기 · 범위 거절 ·
     * 확보)이 잰다.
     */
    private static CutPlanner.CutInput input(List<RoundRow> rows, long in, long out, boolean ended,
                                            List<RecipeDocument.Segment> subtitles) {
        return new CutPlanner.CutInput("s-1", 7L, rows, in, out, subtitles, ended, ended, NOW);
    }

    /** 회차 완결 술어와 clip 방송 {@code ended} 를 따로 준다. */
    private static CutPlanner.CutInput input(List<RoundRow> rows, long in, long out, boolean roundComplete,
                                            boolean broadcastEnded) {
        return new CutPlanner.CutInput("s-1", 7L, rows, in, out, List.of(), roundComplete, broadcastEnded, NOW);
    }

    private static LedgerNeighbor neighbor(long seq, String state) {
        return new LedgerNeighbor(seq, 4_000, state, "k" + seq);
    }

    private static RowBuilder row(long seq, long wall, int duration) {
        return new RowBuilder(seq, wall, duration);
    }

    /** 기본: 판 NULL · 올라감 · 장부 바로 앞 행과 같은 녹화로 이어짐 · PDT = 파일명 시각. */
    private static final class RowBuilder {
        private final long seq;
        private final long wall;
        private final int duration;
        private Long pdt;
        private boolean discontinuity;
        private boolean followsLedgerPrevious = true;
        private String state = RoundRow.UPLOADED;
        private Integer version;

        RowBuilder(long seq, long wall, int duration) {
            this.seq = seq;
            this.wall = wall;
            this.duration = duration;
            this.pdt = wall;
        }

        /** 목록 첫 행 — 앞 행이 없다. */
        RowBuilder first() {
            followsLedgerPrevious = false;
            return this;
        }

        /** 장부 바로 앞 행이 목록 바로 앞 행이 아니다(사이에 비귀속 행). */
        RowBuilder notLedgerNeighbor() {
            followsLedgerPrevious = false;
            return this;
        }

        RowBuilder discontinuity() {
            discontinuity = true;
            return this;
        }

        RowBuilder pdt(long ms) {
            pdt = ms;
            return this;
        }

        RowBuilder v2() {
            version = 2;
            return this;
        }

        RowBuilder pending() {
            state = RoundRow.PENDING;
            return this;
        }

        RowBuilder failed() {
            state = RoundRow.FAILED;
            return this;
        }

        RoundRow build() {
            return new RoundRow(seq, wall, pdt, duration, discontinuity, followsLedgerPrevious, state, version,
                    "k" + seq);
        }
    }

    /** 장부 이웃을 메모리에서 준다 — 무엇을 물었는지도 적는다. */
    private static final class FakeLedger implements CutPlanner.Ledger {
        private final Map<Long, RoundRow> after = new HashMap<>();
        private final Map<Long, LedgerNeighbor> before = new HashMap<>();
        private boolean previousBroadcast;
        final List<List<Long>> askedBefore = new ArrayList<>();

        FakeLedger after(long seq, RoundRow next) {
            after.put(seq, next);
            return this;
        }

        FakeLedger before(long head, LedgerNeighbor row) {
            before.put(head, row);
            return this;
        }

        /** 같은 물리 키에 앞 방송이 있다(이 방송 장부 구간에 시작이 있다). */
        FakeLedger previousBroadcast() {
            previousBroadcast = true;
            return this;
        }

        @Override
        public Optional<RoundRow> rowAfter(long seq) {
            return Optional.ofNullable(after.get(seq));
        }

        @Override
        public CutPlanner.Preceding rowsBefore(List<Long> heads) {
            askedBefore.add(List.copyOf(heads));
            Map<Long, LedgerNeighbor> found = new HashMap<>();
            for (long h : heads) {
                if (before.containsKey(h)) {
                    found.put(h, before.get(h));
                }
            }
            return new CutPlanner.Preceding(found, previousBroadcast);
        }
    }
}
