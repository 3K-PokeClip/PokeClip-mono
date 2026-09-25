package index

// 되감기 캐시의 부팅 재구성 읽기(POK-195 M4 PR ⓑ — 설계 4.1 「부팅 재구성」 · 계획 2.3 ⑸ⓕ).
//
// 읽기 전용이다 — 장부 쓰기 0 이고 INSERT 경로(store.go)를 건드리지 않는다. 행을 가져올 뿐 settled
// 판정은 하지 않는다: 판정은 rewind/boundary.Settled 하나이고 캐시·재구성·정합성 감사가 모두 그것을
// 부른다(착수 점검 숨은 가정 6). 판정이 SQL 에도 있으면 캐시와 감사가 서로 다른 접두를 본다.

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// RewindRow 는 되감기 캐시가 싣는 장부 한 행이다 — 설계 4.1 settled 정본 SQL 의 열에서
// is_discontinuity 를 뺀 것이다. 끊김 표시는 그 열을 근거로 쓰지 않으므로(계획 부기 29) 싣지 않는다 —
// 실리지 않은 값은 표시 규칙이 잘못 읽을 수도 없다(계획 6.3 #7·#42 의 방어가 타입에 선다).
//
// NULL 은 영값이다(SeedResult 와 같은 규약). 세 carrier 열에는 NULL 아니면 실제 값이 들어가므로
// 영값과 겹치지 않는다.
type RewindRow struct {
	Seq int64
	// SessionID 는 session_id 다. "" 면 NULL(비귀속)이다.
	SessionID  string
	DurationMS int32
	// PlaybackPDT 는 playback_pdt 다. 영값이면 NULL 이다.
	PlaybackPDT time.Time
	// PlaybackS3Key 는 playback_s3_key 다. "" 면 NULL 이다.
	PlaybackS3Key string
	// PlaybackUploaded 는 playback_upload_state = 'uploaded' 인가다 — ② 가 아니라 ③ 축이다.
	PlaybackUploaded bool
	// IsGap 은 (stream_id, seq) 가 발행된 GAP 원장(stream_published_gaps)에 있는가다.
	IsGap bool
}

// RewindSession 은 되감기 캐시가 싣는 회차(stream_sessions) 한 행의 여덟 열이다(계획 2.3 ⑸ⓕ⑵ ·
// ④ 착수 메모의 target_duration 추가). NULL 은 영값이다.
type RewindSession struct {
	SessionID string
	// State 는 state 다 — live·ending·ended(DDL CHECK 가 정본).
	State string
	// EndReason 은 end_reason 이다. "" 면 NULL 이다.
	EndReason string
	// InitUploaded 는 init_uploaded_at 이 있는가다 — 그 회차의 MAP 이 가리키는 init 이 올라가 있다.
	InitUploaded      bool
	DiscontinuityBase int64
	// FirstPDT 는 first_pdt 다. 영값이면 NULL 이다.
	FirstPDT time.Time
	// InheritsSession 은 inherits_session 이다. "" 면 NULL 이다.
	InheritsSession string
	TargetDuration  int32
}

// RewindLedger 는 한 스트림의 부팅 재구성 적재분이다 — 되감기 캐시(rewind/cache)가 통째로 갈아
// 끼우는 입력이다.
type RewindLedger struct {
	// CutoffSeq 는 stream_cutoffs.cutoff_seq 다. HasCutoff 가 거짓이면 컷오프가 없고, 그러면
	// Rows·Sessions 도 비어 있다(되감기를 제공하지 않는 스트림 — 설계 4.2 ⓑ).
	CutoffSeq int64
	HasCutoff bool
	// Rows 는 seq ≥ CutoffSeq 인 행 전량이다(seq 오름차순).
	Rows []RewindRow
	// Sessions 는 Rows 가 참조하는 회차 전부다(개시 순). 창 경계가 아니라 적재한 행이 기준이다 —
	// 창 밖 행의 회차가 없으면 목록을 고를 때 모르는 회차를 만난다.
	Sessions []RewindSession
}

// rewindCutoffSQL 은 스트림의 활성화 컷오프다(stream_id 가 PK 라 많아야 1행).
const rewindCutoffSQL = `SELECT cutoff_seq FROM stream_cutoffs WHERE stream_id = $1`

// rewindRowsSQL 은 재구성의 조각 축이다 — 설계 4.1 settled 정본 SQL 의 형상에서 범위를 `seq ≥ 컷오프`
// 로 연 것이다(설계 4.1 「부팅 재구성: WHERE stream_id=$1 AND seq >= $cutoff」). is_discontinuity 는
// 읽지 않는다. GAP 원장은 발행 상태(recorded·put_confirmed·vod_abandoned)와 무관하게 소속만 본다 —
// settled 술어의 「(stream_id,k) ∈ stream_published_gaps」 그대로다.
const rewindRowsSQL = `
SELECT s.seq, s.session_id, s.duration_ms, s.playback_pdt, s.playback_s3_key,
       (s.playback_upload_state = 'uploaded') AS pb_uploaded,
       (g.seq IS NOT NULL)                    AS is_gap
  FROM stream_segments s
  LEFT JOIN stream_published_gaps g ON g.stream_id = s.stream_id AND g.seq = s.seq
 WHERE s.stream_id = $1 AND s.seq >= $2
 ORDER BY s.seq`

// rewindSessionsSQL 은 재구성의 세션 축이다 — 조각 축이 참조한 회차의 여덟 열(PK 조회).
const rewindSessionsSQL = `
SELECT session_id, state, end_reason, init_uploaded_at IS NOT NULL, discontinuity_base,
       first_pdt, inherits_session, target_duration
  FROM stream_sessions
 WHERE session_id = ANY($1)
 ORDER BY started_at, session_id`

// LoadRewindLedger 는 한 스트림의 부팅 재구성 적재분을 읽는다(설계 4.1). 두 단이다:
//
//	⑴ 조각 축  seq ≥ 컷오프 행 전량 + ③ 상태 + GAP 원장 소속
//	⑵ 세션 축  ⑴ 이 참조하는 모든 회차의 여덟 열
//
// 컷오프가 없으면 ⑴⑵ 를 읽지 않는다. 적재량은 컷오프 이후 행 전량이라 방송이 길수록 커진다(착수
// 점검 숨은 가정 12 — 조립 쪽의 확인 거리). 읽기에 실패하면 오류다 — 빈 결과로 삼키면 캐시가 「컷오프
// 없음」으로 굳어 그 스트림의 되감기가 조용히 사라진다.
func LoadRewindLedger(ctx context.Context, pool *pgxpool.Pool, streamID string) (RewindLedger, error) {
	var l RewindLedger
	err := pool.QueryRow(ctx, rewindCutoffSQL, streamID).Scan(&l.CutoffSeq)
	if errors.Is(err, pgx.ErrNoRows) {
		return RewindLedger{}, nil
	}
	if err != nil {
		return RewindLedger{}, fmt.Errorf("되감기 재구성 컷오프 조회 실패 stream_id=%q: %w", streamID, err)
	}
	l.HasCutoff = true
	if l.Rows, err = loadRewindRows(ctx, pool, streamID, l.CutoffSeq); err != nil {
		return RewindLedger{}, err
	}
	if l.Sessions, err = loadRewindSessions(ctx, pool, streamID, referencedSessions(l.Rows)); err != nil {
		return RewindLedger{}, err
	}
	return l, nil
}

// loadRewindRows 는 조각 축(⑴)이다.
func loadRewindRows(ctx context.Context, pool *pgxpool.Pool, streamID string, cutoff int64) ([]RewindRow, error) {
	rows, err := pool.Query(ctx, rewindRowsSQL, streamID, cutoff)
	if err != nil {
		return nil, fmt.Errorf("되감기 재구성 조각 조회 실패 stream_id=%q: %w", streamID, err)
	}
	defer rows.Close()

	var out []RewindRow
	for rows.Next() {
		var (
			r         RewindRow
			sessionID *string
			pdt       *time.Time
			key       *string
		)
		if err := rows.Scan(&r.Seq, &sessionID, &r.DurationMS, &pdt, &key, &r.PlaybackUploaded, &r.IsGap); err != nil {
			return nil, fmt.Errorf("되감기 재구성 조각 스캔 실패 stream_id=%q: %w", streamID, err)
		}
		r.SessionID, r.PlaybackPDT, r.PlaybackS3Key = deref(sessionID), derefTime(pdt), deref(key)
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("되감기 재구성 조각 조회 중 오류 stream_id=%q: %w", streamID, err)
	}
	return out, nil
}

// referencedSessions 는 행들이 참조하는 회차 ID 다(처음 나온 순서 · 중복 없음 · 비귀속 행 제외).
func referencedSessions(rows []RewindRow) []string {
	var ids []string
	seen := map[string]bool{}
	for _, r := range rows {
		if r.SessionID != "" && !seen[r.SessionID] {
			seen[r.SessionID] = true
			ids = append(ids, r.SessionID)
		}
	}
	return ids
}

// loadRewindSessions 는 세션 축(⑵)이다.
func loadRewindSessions(ctx context.Context, pool *pgxpool.Pool, streamID string, ids []string) ([]RewindSession, error) {
	rows, err := pool.Query(ctx, rewindSessionsSQL, ids)
	if err != nil {
		return nil, fmt.Errorf("되감기 재구성 회차 조회 실패 stream_id=%q: %w", streamID, err)
	}
	defer rows.Close()

	var out []RewindSession
	for rows.Next() {
		var (
			s                  RewindSession
			endReason, inherit *string
			firstPDT           *time.Time
		)
		if err := rows.Scan(&s.SessionID, &s.State, &endReason, &s.InitUploaded, &s.DiscontinuityBase,
			&firstPDT, &inherit, &s.TargetDuration); err != nil {
			return nil, fmt.Errorf("되감기 재구성 회차 스캔 실패 stream_id=%q: %w", streamID, err)
		}
		s.EndReason, s.FirstPDT, s.InheritsSession = deref(endReason), derefTime(firstPDT), deref(inherit)
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("되감기 재구성 회차 조회 중 오류 stream_id=%q: %w", streamID, err)
	}
	return out, nil
}

// deref 는 NULL 가능 text 를 영값 규약("" = NULL)으로 편다.
func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// derefTime 은 NULL 가능 timestamptz 를 영값 규약(영값 = NULL)으로 편다. 저장은 UTC 강제지만 드라이버가
// 로컬 위치로 실어 올 수 있어 여기서 한 번 못 박는다(LoadCursor 와 같은 이유).
func derefTime(t *time.Time) time.Time {
	if t == nil {
		return time.Time{}
	}
	return t.UTC()
}
