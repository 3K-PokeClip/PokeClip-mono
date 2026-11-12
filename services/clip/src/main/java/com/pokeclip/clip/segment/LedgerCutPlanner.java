package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.recipe.RecipeDocument;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 렌더 주문의 컷 판정을 장부에서 읽어 돌린다(POK-196 · 설계 r7 9.1). 판정 자체는 {@link CutPlanner} 한 곳이고, 여기는 판정 입력을
 * 장부에서 모으는 일만 한다 — 회차 고르기 · 회차 행 전부(가장자리는 판정이 메모리 F 로 고른다) · 회차 완결 · 방송 상태, 그리고 판정
 * 중에 묻는 장부 이웃(확보 후보 · 준비 후보).
 *
 * <p><b>왕복은 회차 길이와 무관하다</b>: 회차 고르기 1 · 회차 행 1 · 회차 완결 0–1(회차 세션이 있을 때만) · 확보 후보 0–1(판정이
 * 필요할 때만) · 준비 후보 0–1(구간 시작과 같은 왕복). 회차 행 읽기는 회차 길이에 비례하고 키 이력은 훑지 않는다.
 */
@Component
public class LedgerCutPlanner {

    private final RoundRows rounds;
    private final StreamSegmentReader segments;

    LedgerCutPlanner(RoundRows rounds, StreamSegmentReader segments) {
        this.rounds = rounds;
        this.segments = segments;
    }

    /**
     * @param inMs 레시피 컷 시작(목록 PDT 축 epoch ms)
     * @param outMs 레시피 컷 끝(반개)
     * @param subtitles 레시피 자막. 없으면 빈 목록
     * @param now 지금 — 확보 후보 {@code failed} 문턱을 잰다
     */
    public CutDecision plan(Broadcast broadcast, long recipeId, long inMs, long outMs,
                            List<RecipeDocument.Segment> subtitles, Instant now) {
        Optional<RoundRows.Round> round = rounds.roundOf(broadcast);
        List<RoundRow> rows = round.map(segments::findRoundRows).orElse(List.of());
        // 회차 완결 술어는 회차 칸이 있는 세션에만 있다 — 옛 방송 시간창은 술어가 없다(거짓 · RoundRows.ROUND_COMPLETE).
        boolean roundComplete = round.orElse(null) instanceof RoundRows.Members
                && rounds.roundComplete(broadcast.getStreamId());
        CutPlanner.CutInput input = new CutPlanner.CutInput(broadcast.getStreamId(), recipeId, rows, inMs, outMs,
                subtitles, roundComplete, broadcast.getStatus() != BroadcastStatus.LIVE, now);
        return CutPlanner.plan(input, ledgerOf(broadcast));
    }

    /** 판정이 정한 행에 대해서만 묻는 장부 이웃. 둘 다 회차로 자르지 않는다(계약 6-6-8 · 설계 r7 9.4). */
    private CutPlanner.Ledger ledgerOf(Broadcast broadcast) {
        String ingestKey = broadcast.ingestKey();
        return new CutPlanner.Ledger() {
            @Override
            public Optional<RoundRow> rowAfter(long seq) {
                return segments.findRowAfter(ingestKey, seq).map(StreamSegmentReader.WallAxisRow::row);
            }

            @Override
            public CutPlanner.Preceding rowsBefore(List<Long> heads) {
                return segments.findRowsBefore(ingestKey, broadcast.getStreamId(), heads);
            }
        };
    }
}
