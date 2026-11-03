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

/**
 * 렌더 주문의 컷 판정(POK-196 · 설계 r7 9.2 · 9.4 · 16.2 P11 · P19 · P30). 순수 판정이라 DB 없이 잰다 — 장부 이웃(끝 소리 확보
 * 후보 · 디코더 준비 후보)은 {@link FakeLedger}가 준다.
 *
 * <p>시각은 epoch ms 대신 작은 값을 쓴다. 축은 늘 파일명 시각({@code start_wall_utc})이다.
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
        FakeLedger ledger = new FakeLedger().before(101, neighbor(100, 96_000, RoundRow.UPLOADED));
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
        assertThat(ready.gaps()).containsExactly(new Gap(101, 102, Gap.Place.MIDDLE, 2_000, 2_000));
        assertThat(ready.primes()).containsExactly(new Prime(101, neighbor(100, 96_000, RoundRow.UPLOADED)));
        assertThat(ready.runHeads()).containsExactly(
                new RunHead(101, RunHead.Kind.CONTINUED), new RunHead(102, RunHead.Kind.NEW_RECORDING));
        assertThat(ready.capture()).as("판 NULL · 다음 행 없음 · 끝난 회차 → 확보 없이 진행").isEmpty();
        assertThat(ledger.askedBefore).as("새 녹화 머리 102 는 앞 행을 묻지 않는다").containsExactly(List.of(101L));
    }

    // ── 틈 판정 · 좌표(P11) ─────────────────────────────────────

    /**
     * P11 · cx-3 반례 — 컷이 끊긴 틈 안에서 시작하면 틈 판정은 좌표로 옮기기 <b>전</b> 원래 범위로 한다: 머리 틈이 기록되고 시작은
     * {@code (B, 0)}, 틈 안에서 시작한 자막은 틈 자리 끝점이다. 좌표 뒤 범위({@code [B 시작, out)})로 재면 머리 틈이 사라진다.
     */
    @Test
    void 컷이_끊긴_틈_안에서_시작하면_머리_틈을_기록하고_뒤_조각_머리에서_시작한다() {
        List<RoundRow> rows = List.of(
                row(1, 0, 4_000).first().build(),
                row(2, 5_000, 4_000).discontinuity().build());
        List<RecipeDocument.Segment> subtitles = List.of(new RecipeDocument.Segment(4_200L, 6_000L, "머리 틈"));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 4_400, 8_000, true, subtitles), new FakeLedger());

        assertThat(ready.gaps()).containsExactly(new Gap(1, 2, Gap.Place.HEAD, 1_000, 600));
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
        assertThat(ready.gaps()).containsExactly(new Gap(1, 2, Gap.Place.TAIL, 1_000, 500));
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
                .before(1, neighbor(0, -4_000, RoundRow.UPLOADED))
                .before(3, neighbor(2, 4_000, RoundRow.UPLOADED));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 1_000, 7_000, true), ledger);

        assertThat(ready.gaps()).containsExactly(new Gap(1, 3, Gap.Place.MIDDLE, 300, 300));
        assertThat(ready.primes()).containsExactly(
                new Prime(1, neighbor(0, -4_000, RoundRow.UPLOADED)),
                new Prime(3, neighbor(2, 4_000, RoundRow.UPLOADED)));
        assertThat(ready.lengthMs()).isEqualTo(6_000 - 300);
        assertThat(ledger.askedBefore).as("머리 둘의 앞 행을 한 번에 묻는다").containsExactly(List.of(1L, 3L));
    }

    // ── 축(P19) ──────────────────────────────────────────────────

    /** P19 — 판 2 회차에서도 축은 파일명 시각이다. PDT 를 120초 어긋낸 시드에서 PDT 로 고르면 다른 조각이다. */
    @Test
    void 판_2_회차도_파일명_시각으로_고른다() {
        List<RoundRow> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            RowBuilder b = row(i + 1, 4_000L * i, 4_000).pdt(4_000L * i + 120_000).v2();
            rows.add((i == 0 ? b.first() : b).build());
        }

        Ready ready = (Ready) CutPlanner.plan(input(rows, 130_500, 138_000, true), new FakeLedger());

        assertThat(ready.in()).isEqualTo(new MediaCoord(33, 2_500_000));
        assertThat(ready.out()).isEqualTo(new End(35, 2_000_000L));
    }

    /** P19 — 판 NULL 래칫(PDT 가 파일명 시각보다 앞섬)도 들어오지 않는다. */
    @Test
    void 판_NULL_래칫_회차도_파일명_시각으로_고른다() {
        List<RoundRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            RowBuilder b = row(i + 1, 3_800L * i, 4_000).pdt(4_000L * i);
            rows.add((i == 0 ? b.first() : b).build());
        }

        Ready ready = (Ready) CutPlanner.plan(input(rows, 30_500, 33_000, true), new FakeLedger());

        assertThat(ready.in()).as("파일명 시각 30400 행(9) — PDT 로 고르면 행 8").isEqualTo(new MediaCoord(9, 100_000));
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
                null, Instant.ofEpochMilli(4_000).plus(Duration.ofMinutes(16)));

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
                null, Instant.ofEpochMilli(4_000).plus(Duration.ofMinutes(16)));

        try (LogCaptor logs = new LogCaptor()) {
            CutDecision decision = CutPlanner.plan(late, new FakeLedger().after(1, failed)
                    .before(1, neighbor(0, -4_000, RoundRow.PENDING)));

            assertThat(decision).isEqualTo(new NotReady("prime_pending"));
            assertThat(logs.messages()).noneMatch(m -> m.startsWith("clip.render.capture_skipped"));
        }
    }

    // ── 디코더 준비(P30 판정) ───────────────────────────────────

    /** P30 ① — 회차 첫 행 k 의 장부 바로 앞 행 u 가 비귀속이어도 같은 녹화면 준비 조각이다. */
    @Test
    void P30_1_회차_첫_행의_앞_비귀속_행을_준비로_싣는다() {
        Ready ready = (Ready) CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().before(10, neighbor(9, 6_000, RoundRow.UPLOADED)));

        assertThat(ready.primes()).containsExactly(new Prime(10, neighbor(9, 6_000, RoundRow.UPLOADED)));
        assertThat(ready.runHeads()).containsExactly(new RunHead(10, RunHead.Kind.CONTINUED));
    }

    @Test
    void P30_2_준비_조각이_아직이면_기다린다() {
        assertThat(CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().before(10, neighbor(9, 6_000, RoundRow.PENDING))))
                .isEqualTo(new NotReady("prime_pending"));
    }

    @Test
    void P30_3_준비_조각이_failed_면_기다리지_않고_없이_진행한다() {
        Ready ready = (Ready) CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().before(10, neighbor(9, 6_000, RoundRow.FAILED)));

        assertThat(ready.primes()).isEmpty();
        assertThat(ready.primeSkips()).containsExactly(new PrimeSkip(10, 9, "failed"));
    }

    @Test
    void P30_4_머리가_새_녹화면_앞_행을_묻지_않고_준비가_없다() {
        List<RoundRow> rows = List.of(row(10, 10_000, 4_000).first().discontinuity().build());
        FakeLedger ledger = new FakeLedger().before(10, neighbor(9, 6_000, RoundRow.UPLOADED));

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
        FakeLedger ledger = new FakeLedger().before(7, neighbor(6, 4_000, RoundRow.UPLOADED));

        Ready ready = (Ready) CutPlanner.plan(input(rows, 1_000, 10_000, true), ledger);

        assertThat(ready.primes()).containsExactly(new Prime(7, neighbor(6, 4_000, RoundRow.UPLOADED)));
        assertThat(ready.runHeads()).containsExactly(
                new RunHead(5, RunHead.Kind.NEW_RECORDING), new RunHead(7, RunHead.Kind.CONTINUED));
    }

    /** P30 ⑥ — 앞 행이 이 방송의 장부 구간 시작 앞이면 앞 방송 몫이라 쓰지 않는다(5-2 소비자 규약). */
    @Test
    void P30_6_앞_행이_방송_장부_구간_시작_앞이면_다른_방송_몫이라_안_싣는다() {
        CutPlanner.CutInput in = new CutPlanner.CutInput("s-1", 7L, oneRow(), 11_000, 13_000, List.of(), true, true,
                Instant.ofEpochMilli(8_000), NOW);

        Ready ready = (Ready) CutPlanner.plan(in, new FakeLedger().before(10, neighbor(9, 6_000, RoundRow.PENDING)));

        assertThat(ready.primes()).isEmpty();
        assertThat(ready.primeSkips()).containsExactly(new PrimeSkip(10, 9, "other_broadcast"));
    }

    /** P30 ⑦ — seq 는 조밀하다고 보장되지 않는다. 장부 바로 앞 행이 h − 3 이어도 그 행이다. */
    @Test
    void P30_7_seq_가_조밀하지_않아도_장부_바로_앞_행을_쓴다() {
        Ready ready = (Ready) CutPlanner.plan(input(oneRow(), 11_000, 13_000, true),
                new FakeLedger().before(10, neighbor(7, 6_000, RoundRow.UPLOADED)));

        assertThat(ready.primes()).containsExactly(new Prime(10, neighbor(7, 6_000, RoundRow.UPLOADED)));
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
            CutPlanner.plan(input(rows, 1_000, 6_000, true), new FakeLedger().before(1, neighbor(0, -4_000,
                    RoundRow.UPLOADED)));
            CutPlanner.plan(input(rows, 4_200, 4_800, true), new FakeLedger());

            assertThat(logs.messages()).contains(
                    "clip.render.cut_planned streamId=s-1 recipeId=7 axis=wall gaps=middle:1-2:1000:1000 joinedMs=1000"
                            + " lengthMs=4000 runHeads=1:continued,2:new_recording",
                    "clip.render.cut_rejected streamId=s-1 recipeId=7 reason=no_footage");
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
        return new CutPlanner.CutInput("s-1", 7L, rows, in, out, subtitles, ended, ended, null, NOW);
    }

    /** 회차 완결 술어와 clip 방송 {@code ended} 를 따로 준다. */
    private static CutPlanner.CutInput input(List<RoundRow> rows, long in, long out, boolean roundComplete,
                                            boolean broadcastEnded) {
        return new CutPlanner.CutInput("s-1", 7L, rows, in, out, List.of(), roundComplete, broadcastEnded, null, NOW);
    }

    private static LedgerNeighbor neighbor(long seq, long wall, String state) {
        return new LedgerNeighbor(seq, wall, 4_000, state, "k" + seq);
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
        final List<List<Long>> askedBefore = new ArrayList<>();

        FakeLedger after(long seq, RoundRow next) {
            after.put(seq, next);
            return this;
        }

        FakeLedger before(long head, LedgerNeighbor row) {
            before.put(head, row);
            return this;
        }

        @Override
        public Optional<RoundRow> rowAfter(long seq) {
            return Optional.ofNullable(after.get(seq));
        }

        @Override
        public Map<Long, LedgerNeighbor> rowsBefore(List<Long> heads) {
            askedBefore.add(List.copyOf(heads));
            Map<Long, LedgerNeighbor> found = new HashMap<>();
            for (long h : heads) {
                if (before.containsKey(h)) {
                    found.put(h, before.get(h));
                }
            }
            return found;
        }
    }
}
