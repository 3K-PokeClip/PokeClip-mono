package publish

// 판 NULL U 와 VOD 동결의 정합 시험(POK-196 PDT 생산자 c4a · r6 c4a 「VOD 동결 S7(cc Y-M1)」 989–991 승계). 장부 행은 실물
// store(index.NewPGStore + session.Registry)가 쓴다 — 시각 칸을 손으로 심으면 식을 시험하지 못한다.

import (
	"context"
	"fmt"
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// registryDecider 는 조립 지점(cmd/segment-indexer/session_wire.go)의 어댑터를 이 시험에서 재현한 것이다 — 매핑이
// 어긋나지 않는지는 cmd 쪽 시험이 지킨다.
type registryDecider struct{ reg *session.Registry }

func (d registryDecider) Decide(ctx context.Context, tx pgx.Tx, in index.SessionInput, now time.Time) (index.SessionDecision, error) {
	ops := map[index.SessionOp]session.Op{
		index.SessionOpenOrCurrent:               session.OpenOrCurrent,
		index.SessionCurrentOrOpenIfCorroborated: session.CurrentOrOpenIfCorroborated,
		index.SessionCurrentOnly:                 session.CurrentOnly,
	}
	op, ok := ops[in.Op]
	if !ok {
		return index.SessionDecision{}, fmt.Errorf("시험 어댑터: 알 수 없는 연산 %d", in.Op)
	}
	dec, err := d.reg.Decide(ctx, tx, session.Input{StreamID: in.StreamID, Seq: in.Seq, StartWallUTC: in.StartWallUTC,
		DurationMS: in.DurationMS, Op: op}, now)
	if err != nil {
		return index.SessionDecision{}, err
	}
	return index.SessionDecision{
		Opens:     dec.Outcome == session.OutcomeOpen || dec.Outcome == session.OutcomeOpenFresh,
		SessionID: dec.SessionID, BaseSessionID: dec.BaseSessionID, EndingSessionID: dec.EndingSessionID,
		PrevFirstLocalPath: dec.PrevFirstLocalPath, RoundID: dec.RoundID, NewRound: dec.NewRound, FloorRejected: dec.FloorRejected, Plan: dec,
	}, nil
}

// legacySegKey 는 실물 키 파생(playback.SegKey)을 index.PlaybackKeyFunc 에 입힌 옛 꼴 어댑터다 — 회차를 버린다(스위치 끔
// 상자와 같은 꼴 · indexer phase_pg_test.go 와 같다).
func legacySegKey(streamID, _ string, seq int64) (string, error) {
	return playback.SegKey(streamID, seq)
}

func (d registryDecider) Open(ctx context.Context, tx pgx.Tx, dec index.SessionDecision, firstPDT time.Time) (string, error) {
	plan, ok := dec.Plan.(session.Decision)
	if !ok {
		return "", fmt.Errorf("시험 어댑터: 개시 계획이 session.Decision 이 아니다(%T)", dec.Plan)
	}
	return d.reg.Open(ctx, tx, plan, firstPDT)
}

// 판 NULL U — 비귀속 행(세션 사이 · offline 뒤 CurrentOnly)이 낀 계승 사슬 P ← S 를 VOD 로 동결하면 비귀속 행은 GAP 줄로
// 실리고(fillGap — PDT = 앞 행 PDT + 길이) S 의 첫 행 PDT 는 그 사이 비귀속 길이 U 만큼 더 가 있어 발행 전 PDT 검사(S7)를
// 지난다. 벽시계가 정체해 누적항이 이기는 입력이다. 잡는 결함(변이 「U 제거」): U 가 없으면 S 첫 행이 P 끝에 붙어
// GAP 줄과 겹치고 S7 이 동결을 막는다.
func TestFreezeVODPassesPDTCheckAcrossUnattributedRows(t *testing.T) {
	pool := newPool(t)
	ctx := context.Background()
	reg := session.New(session.Options{FloorSlack: time.Second, ObsFresh: 30 * time.Second, ReconnectWindow: 300 * time.Second})
	store := index.NewPGStore(pool, registryDecider{reg: reg}, legacySegKey)
	closeOpts := index.CloseOptions{Window: 300 * time.Second, Slack: 60 * time.Second, Log: slog.New(slog.DiscardHandler)}
	insert := func(seq int64, at time.Duration, src index.SessionSource) index.InsertCommit {
		t.Helper()
		wall := fxDay.Add(at)
		_, res, err := store.Insert(ctx, index.Record{
			StreamID: fxStream, Seq: seq, StartWallUTC: wall, DurationMS: 4000,
			S3Key: index.S3Key(fxStream, seq, wall), LocalPath: fmt.Sprintf("/recordings/%s/%d.mp4", fxStream, seq),
			UploadState: index.UploadStatePending, Bytes: 1000,
		}, index.Seed{}, src)
		if err != nil {
			t.Fatalf("Insert seq %d 오류: %v", seq, err)
		}
		return res
	}
	live := index.SessionSource{Op: index.SessionOpenOrCurrent}
	idle := index.SessionSource{Op: index.SessionCurrentOnly}
	endLive := func() {
		t.Helper()
		if _, err := index.EndLive(ctx, pool, fxStream, closeOpts, nil); err != nil {
			t.Fatalf("EndLive 오류: %v", err)
		}
	}

	head := insert(0, 0, live)
	for seq := int64(1); seq < 4; seq++ {
		insert(seq, time.Duration(seq)*4*time.Second, live)
	}
	endLive()
	insert(4, 16*time.Second, idle)
	insert(5, 20*time.Second, idle)
	tail := insert(6, 20*time.Second, live) // 벽시계 정체 — 누적항(P 끝 16초 + U 8초)이 이긴다
	for seq := int64(7); seq < 10; seq++ {
		insert(seq, time.Duration(seq)*4*time.Second, live)
	}
	endLive()
	if tail.InheritsSession != head.SessionID {
		t.Fatalf("S 개시 계승 = %q, want %q(사슬 P ← S)", tail.InheritsSession, head.SessionID)
	}
	// Errorf 다 — U 를 잃은 PDT 도 동결까지 흘려 S7 이 막는지 함께 본다(c4a cx L · cc L1 — 사전 단언에서 끝나면
	// 「S7 이 잡는다」 는 이 시험의 주장이 확인되지 않는다).
	if want := fxDay.Add(24 * time.Second); !tail.PlaybackPDT.Equal(want) {
		t.Errorf("S 첫 행 PDT = %v, want %v(P 끝 + U)", tail.PlaybackPDT, want)
	}

	ids := []string{head.SessionID, tail.SessionID}
	exec(t, pool, `UPDATE stream_sessions SET vod_state = 'freezing', dvr_state = 'frozen', init_uploaded_at = now()
	                WHERE session_id = ANY($1)`, ids)
	exec(t, pool, `UPDATE stream_segments SET playback_upload_state = 'uploaded' WHERE session_id IS NOT NULL`)
	insertCutoff(t, pool, 0)
	p := newPublisher(t, pool, newFakeStore(t), "w-me", &logRecorder{})
	chain := index.Chain{StreamID: fxStream, Head: head.SessionID, Tail: tail.SessionID, SessionIDs: ids}

	out := p.FreezeVOD(ctx, chain, false)
	wantOutcome(t, out, AxisVOD, ResultFrozenWithGaps, false)
}
