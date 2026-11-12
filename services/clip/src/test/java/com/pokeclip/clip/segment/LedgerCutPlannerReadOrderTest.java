package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.segment.CutDecision.NotReady;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 렌더 주문 컷 판정의 장부 읽기 순서(POK-196 · PR-B2 c1 지적 1). 회차 완결을 회차 행보다 <b>먼저</b> 읽는다 — 완결이 참이면 회차
 * 귀속 행은 이미 전부 장부에 있으므로(생산자: 끝난 세션에는 새 귀속이 없고 ended 는 되돌릴 수 없다) 그 뒤 읽은 행은 완전하다.
 * 거꾸로 읽으면 「옛 행 목록 + 완결 참」 이 생겨 아직 안 읽힌 꼬리를 녹화 밖 422 로 종국 거절한다.
 *
 * <p>장부는 Mockito 가짜다 — 두 읽기 사이에 생산자 마무리 사슬이 끝나는 꼴을 「행을 읽는 순간 마지막 행이 들어오고 회차가 닫힌다」로
 * 흉내 낸다. 진짜 SQL 은 {@code CutLedgerReadTest} 가 잰다.
 */
class LedgerCutPlannerReadOrderTest {

    private static final String STREAM = "S-order";
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void 행을_읽은_뒤_회차가_닫혀도_옛_행으로_녹화_밖을_내지_않는다() {
        Broadcast broadcast = Broadcast.startedNow(STREAM, "1", "K-order", 1L, NOW, null);
        Ledger ledger = new Ledger();
        RoundRows rounds = mock(RoundRows.class);
        StreamSegmentReader segments = mock(StreamSegmentReader.class);
        given(rounds.roundOf(broadcast))
                .willReturn(Optional.of(new RoundRows.Members("K-order", List.of(STREAM))));
        given(rounds.roundComplete(anyString())).willAnswer(inv -> ledger.read("complete", ledger.closed));
        given(segments.findRoundRows(any())).willAnswer(inv -> {
            List<RoundRow> before = ledger.read("rows", List.of(row(1, 0)));
            ledger.closed = true;
            return before;
        });

        CutDecision decision = new LedgerCutPlanner(rounds, segments).plan(broadcast, 7L, 1_000, 6_000, List.of(), NOW);

        assertThat(decision).isEqualTo(new NotReady("not_yet_recorded"));
        assertThat(ledger.reads).containsExactly("complete", "rows");
    }

    private static RoundRow row(long seq, long ms) {
        return new RoundRow(seq, ms, ms, 4_000, false, false, RoundRow.UPLOADED, null, "k" + seq);
    }

    /** 읽은 차례를 적는 가짜 장부 상태. {@code closed} 는 회차 완결이다. */
    private static final class Ledger {
        private final List<String> reads = new ArrayList<>();
        private boolean closed;

        <T> T read(String what, T value) {
            reads.add(what);
            return value;
        }
    }
}
