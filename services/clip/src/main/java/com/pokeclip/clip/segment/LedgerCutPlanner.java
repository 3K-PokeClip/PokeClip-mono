package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.recipe.RecipeDocument;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 렌더 주문의 컷 판정을 장부에서 읽어 돌린다(POK-196 · 설계 r7 9.1). 판정 자체는 {@link CutPlanner} 한 곳이고, 여기는 판정 입력을
 * 장부에서 모으는 일만 한다 — 회차 고르기 · 회차 완결 · 회차 행 전부(가장자리는 판정이 메모리 F 로 고른다) · 방송 상태, 그리고 판정
 * 중에 묻는 장부 이웃(확보 후보 · 준비 후보). 편집기 소리 주소 API 의 끝 소리 확보도 같은 읽기로 돌린다({@link #audioCapture}).
 *
 * <p><b>왕복은 회차 길이와 무관하다</b>: 회차 고르기 1–2(완결이 참이면 한 번 더) · 회차 완결 0–1(회차 세션이 있을 때만 · 회차
 * 행보다 먼저) · 회차 행 1 · 확보 후보 0–1(판정이 필요할 때만) · 준비 후보 0–1(구간 시작과 같은 왕복). 회차 행 읽기는 회차 길이에
 * 비례하고 키 이력은 훑지 않는다.
 */
@Component
public class LedgerCutPlanner {

    private final RoundRows rounds;
    private final StreamSegmentReader segments;

    public LedgerCutPlanner(RoundRows rounds, StreamSegmentReader segments) {
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
        Optional<RoundRows.Round> picked = rounds.roundOf(broadcast);
        // 회차 완결 술어는 회차 칸이 있는 세션에만 있다 — 옛 방송 시간창은 술어가 없다(거짓 · RoundRows.ROUND_COMPLETE).
        // 행보다 먼저 읽는다: 완결이 참이면 귀속 행은 이미 전부 장부에 있다(끝난 세션에는 새 귀속이 없다). 거꾸로 읽으면 두
        // 읽기 사이에 회차가 닫힐 때 옛 행 목록 + 완결 참이 되어 아직 안 읽힌 꼬리를 녹화 밖 422 로 거절한다.
        boolean roundComplete = picked.orElse(null) instanceof RoundRows.Members
                && rounds.roundComplete(broadcast.getStreamId());
        // 세션 목록도 같다 — 완결 전에 고른 목록에는 그 뒤 합류한 재접속 세션이 없을 수 있다. 완결이 참이면 다시 고른다(닫힌
        // 회차에는 새 세션이 합류하지 않는다 · 왕복 +1).
        Optional<RoundRows.Round> round = roundComplete ? rounds.roundOf(broadcast) : picked;
        List<RoundRow> rows = round.map(segments::findRoundRows).orElse(List.of());
        CutPlanner.CutInput input = new CutPlanner.CutInput(broadcast.getStreamId(), recipeId, rows, inMs, outMs,
                subtitles, roundComplete, broadcast.getStatus() != BroadcastStatus.LIVE, now);
        return CutPlanner.plan(input, ledgerOf(broadcast));
    }

    /**
     * 편집기 소리 주소 API 의 끝 소리 확보(설계 r7 9.6) — {@link #plan} 과 같은 회차 고르기 · 회차 행 · 방송 상태 · 장부 이웃으로
     * {@link CutPlanner#audioCaptureOf} 를 부른다. 같은 창을 컷으로 받은 렌더 주문과 같은 판정이다(계약 6-6-8 8항).
     *
     * <p>회차 완결은 판정이 물을 때만 읽는다(범위 갈래 · 확보 후보가 장부에 없고 방송 중일 때) — 그래서 회차 행 <b>뒤</b>에 읽힌다.
     * 완결이 참으로 나오면 세션 목록 · 회차 행을 다시 읽어 다시 판정한다(R28 · kty 결정 (나)) — 두 읽기 사이에 생산자가 마지막 행을
     * 넣고 회차를 닫았으면 옛 행 목록 + 완결 참이 확보할 수 있는 창을 {@code SKIPPED} 로 접기 때문이다. 닫힌 회차는 더 바뀌지
     * 않아 다시 읽은 판정이 마지막이다. 왕복은 회차 고르기 1 · 회차 행 1 · 확보 후보 0–1 · 회차 완결 0–1 · (완결 참이면) 회차
     * 고르기 · 회차 행 하나씩 더 · 확보 후보 0–1 이다.
     *
     * @param fromMs 창 시작(목록 PDT 축 epoch ms)
     * @param toMs 창 끝(반개) — 컷 끝으로 본다
     */
    public CutPlanner.AudioCapture audioCapture(Broadcast broadcast, long fromMs, long toMs, Instant now) {
        Optional<RoundRows.Round> round = rounds.roundOf(broadcast);
        List<RoundRow> rows = round.map(segments::findRoundRows).orElse(List.of());
        boolean ended = broadcast.getStatus() != BroadcastStatus.LIVE;
        AtomicBoolean completeSeen = new AtomicBoolean();
        CutPlanner.AudioCapture first = CutPlanner.audioCaptureOf(rows, fromMs, toMs, () -> {
            boolean complete = round.orElse(null) instanceof RoundRows.Members
                    && rounds.roundComplete(broadcast.getStreamId());
            completeSeen.set(complete);
            return complete;
        }, ended, now, ledgerOf(broadcast));
        if (!completeSeen.get()) {
            return first;
        }
        List<RoundRow> settled = rounds.roundOf(broadcast).map(segments::findRoundRows).orElse(List.of());
        return CutPlanner.audioCaptureOf(settled, fromMs, toMs, () -> true, ended, now, ledgerOf(broadcast));
    }

    /** 판정이 정한 행에 대해서만 묻는 장부 이웃. 둘 다 회차로 자르지 않는다(계약 6-6-8 · 설계 r7 9.4). */
    private CutPlanner.Ledger ledgerOf(Broadcast broadcast) {
        String ingestKey = broadcast.ingestKey();
        return new CutPlanner.Ledger() {
            @Override
            public Optional<RoundRow> rowAfter(long seq) {
                return segments.findRowAfter(ingestKey, seq);
            }

            @Override
            public CutPlanner.Preceding rowsBefore(List<Long> heads) {
                return segments.findRowsBefore(ingestKey, broadcast.getStreamId(), heads);
            }
        };
    }
}
