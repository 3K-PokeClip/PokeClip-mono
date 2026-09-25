// Package cache 는 되감기 목록의 입력을 메모리에 든다 — 장부(stream_segments)·GAP 원장
// (stream_published_gaps)·컷오프(stream_cutoffs)의 읽기 뷰다(설계 3.2 rewind/cache).
//
// 채우는 길은 둘이다. 부팅 재구성(Reload — 장부에서 다시 읽은 값으로 한 스트림의 뷰를 통째로 갈아
// 끼운다)과 push(장부가 바뀐 직후 그 사실을 넘겨받는다 — INSERT·꼬리 교정은 인덱서가, ③ 확정·GAP
// 원장 등록은 발행 루프가 넘긴다). 그래서 목록을 만들 때 DB 를 묻지 않는다(프로필 4절 「매니페스트
// 합성은 평시 DB·S3 조회 0」). 파일 시스템을 보지 않고 무효화 연산이 없다 — 뷰가 장부와 어긋났을 때의
// 유일한 되돌림은 Reload 다.
//
// 캐시는 한 고루틴(인덱서와 같은 D10 루프)이 소유한다. 락이 없고 읽기도 그 고루틴에서만 한다 — 발행
// 워커에는 Playlist 가 만든 값(새 슬라이스)만 넘긴다(설계 3.3).
package cache

import (
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// Cache 는 스트림별 읽기 뷰의 모음이다. 영값을 그대로 쓴다. nil 이면 모든 메서드가 아무것도 하지
// 않는다 — 캐시를 조립하지 않은 프로세스에서도 인덱서가 INSERT 마다 부르기 때문이다(upload.Dirty 와
// 같은 nil 규약). 고루틴 안전하지 않다(패키지 설명).
type Cache struct {
	streams map[string]*view
}

// view 는 한 스트림의 읽기 뷰다.
type view struct {
	// cutoff 는 stream_cutoffs.cutoff_seq 다. hasCutoff 가 거짓이면 컷오프가 없다. 한 번 정해지면
	// 바뀌지 않는다.
	cutoff    int64
	hasCutoff bool
	// rows 는 컷오프부터 seq 가 1씩 이어지는 장부 행이다. 컷오프 아래 행은 목록에 실릴 수 없어(설계
	// 4.2 ⓐ) 싣지 않는다. 이어져 있으므로 seq 로 자리를 바로 센다.
	rows []boundary.Row
	// sessions 는 회차 축이다(session_id → 회차).
	sessions map[string]Session
}

// Session 은 캐시가 드는 회차 한 행이다 — 장부의 여덟 열(index.RewindSession)과 MinSeq.
type Session struct {
	index.RewindSession
	// MinSeq 는 장부에서 이 회차에 귀속된 행의 가장 작은 seq 다 — 끊김 표시 술어의 재료다
	// (rewind.Session.MinSeq). 실행 중 개시면 그 개시 행의 seq(컷오프 아래일 수 있다)이고, 재구성이면
	// 적재한 행 가운데 최솟값이다. 둘이 달라도 술어가 컷오프로 잘라 같은 표시가 난다.
	MinSeq int64
}

// Reload 는 streamID 의 뷰를 장부에서 다시 읽은 값(index.LoadRewindLedger)으로 통째로 갈아 끼운다 —
// 부팅 재구성이자 유일한 되돌림이다(설계 3.2 · 4.1). 갈아 끼우기 전에 들고 있던 행·회차는 남지 않는다.
//
// 행은 컷오프부터 1씩 이어진 데까지만 싣는다. 장부는 seq 를 비우지 않으므로 끊긴 입력은 읽기 결함이고,
// 끊긴 뒤를 실으면 경계가 빈자리를 모르고 넘어간다. MinSeq 는 적재한 행 전부에서 센다.
func (c *Cache) Reload(streamID string, l index.RewindLedger) {
	if c == nil {
		return
	}
	v := &view{sessions: map[string]Session{}}
	if l.HasCutoff {
		v.cutoff, v.hasCutoff = l.CutoffSeq, true
		for _, r := range l.Rows {
			if r.Seq != v.nextSeq() {
				break
			}
			// 두 행 타입은 필드(이름·타입·순서)가 같다 — 한쪽에만 열이 생기면 이 변환이 컴파일되지 않는다.
			v.rows = append(v.rows, boundary.Row(r))
		}
	}
	minSeq := map[string]int64{}
	for _, r := range l.Rows {
		if cur, ok := minSeq[r.SessionID]; !ok || r.Seq < cur {
			minSeq[r.SessionID] = r.Seq
		}
	}
	for _, s := range l.Sessions {
		v.sessions[s.SessionID] = Session{RewindSession: s, MinSeq: minSeq[s.SessionID]}
	}
	c.set(streamID, v)
}

// ApplyInsert 는 장부에 방금 커밋된 행 하나를 반영한다 — 인덱서가 INSERT 가 들어간 뒤 그 결과
// (index.SeedResult)를 그대로 넘긴다(INSERT push).
//
//	주조(Seeded)          장부가 이 행에서 컷오프를 만들었다 — 이 행의 seq 가 컷오프다(스트림당 한 번
//	                      뿐이다 — 주조 CTE 의 ON CONFLICT DO NOTHING 이 둘째 주조를 막는다)
//	개시(SessionOpened)   회차 축을 세운다 — 개시가 쓴 base·계승·TD·first_pdt, state live, init 미확정,
//	                      MinSeq = 이 seq. 컷오프 전에 열린 회차도 적는다(그 회차의 행도 컷오프부터 실린다)
//	행                    컷오프부터(seq ≥ 컷오프) 싣는다 — 커밋 값 그대로이고, ③ 전이며 GAP 원장 밖이다
//
// 이 행이 뷰의 다음 seq 가 아니면 아무것도 바꾸지 않는다 — 캐시가 장부 행을 놓쳤다(단일 쓰기자 전제가
// 깨져 다른 쓰기자가 그 seq 를 먼저 쓴 국면 — 인덱서의 seq 충돌 재적재). 놓친 뒤로는 뷰가 거기서 멈추고,
// 머리가 장부보다 뒤처지므로 정합성 감시(설계 4.1 (a) 캐시 드리프트)가 Reload 로 되돌린다. 빈자리를
// 두고 이어 붙이면 경계가 빈자리를 모르고 넘어가 목록 안 seq 가 끊긴다.
//
// Reload 하지 않은 스트림은 빈 뷰(컷오프 없음)에서 시작한다 — 부팅 때 장부에 이미 있던 스트림은
// 조립 쪽이 먼저 Reload 해야 그 컷오프와 회차를 안다.
func (c *Cache) ApplyInsert(streamID string, seq int64, res index.SeedResult) {
	if c == nil {
		return
	}
	v := c.lookup(streamID)
	if v == nil {
		v = &view{sessions: map[string]Session{}}
		c.set(streamID, v)
	}
	if res.Seeded {
		v.cutoff, v.hasCutoff = seq, true
	}
	if v.hasCutoff && seq != v.nextSeq() {
		return
	}
	if res.SessionOpened {
		// state 는 개시 문장이 쓰는 'live' 이고 init·종료 사유는 개시 때 비어 있다(session.openSessionSQL).
		v.sessions[res.SessionID] = Session{MinSeq: seq, RewindSession: index.RewindSession{
			SessionID: res.SessionID, State: "live", DiscontinuityBase: res.DiscontinuityBase,
			FirstPDT: res.PlaybackPDT, InheritsSession: res.InheritsSession, TargetDuration: res.TargetDuration,
		}}
	}
	if v.hasCutoff {
		v.rows = append(v.rows, boundary.Row{
			Seq: seq, SessionID: res.SessionID, DurationMS: res.DurationMS,
			PlaybackPDT: res.PlaybackPDT, PlaybackS3Key: res.PlaybackS3Key,
		})
	}
}

// ApplyTailCorrection 은 꼬리 교정(index.Store.UpdateTail 성공)을 반영한다 — 인덱서가 교정이 장부에
// 들어간 뒤 넘긴다(교정 push). 그 행의 길이만 바뀐다: PDT·키는 장부 불변 트리거가 지키고, 회차 TD 는
// 개시 때 굳었다. 뷰에 없는 행이면 아무것도 하지 않는다.
func (c *Cache) ApplyTailCorrection(streamID string, seq int64, durationMS int32) {
	if r := c.row(streamID, seq); r != nil {
		r.DurationMS = durationMS
	}
}

// ApplyPlaybackUploaded 는 ③ 확정(playback_upload_state = 'uploaded' CAS 성공)을 반영한다 — 그 행이
// settled 가 될 수 있다(설계 4.1 트리거 표 「③ 업로드 확인 CAS 성공」). 넘기는 쪽은 발행 루프의 Dirty
// 처리(PR ⓒ)다. 뷰에 없는 행이면 아무것도 하지 않는다 — 실시간 ③ 는 컷오프와 무관하게 돌아(계약
// 세그먼트인덱스 5-5 6항) 컷오프 아래 행의 확정도 온다.
func (c *Cache) ApplyPlaybackUploaded(streamID string, seq int64) {
	if r := c.row(streamID, seq); r != nil {
		r.PlaybackUploaded = true
	}
}

// ApplyPublishedGap 은 GAP 원장 등록(stream_published_gaps INSERT 성공)을 반영한다 — ③ 가 안 올라간
// 행도 settled 가 되어 목록에 GAP 줄로 실린다(설계 4.1 「GAP 발행 확정 — 원장 INSERT 성공 후 캐시
// 반영」). 넘기는 쪽은 발행 루프(PR ⓒ)다. 뷰에 없는 행이면 아무것도 하지 않는다.
func (c *Cache) ApplyPublishedGap(streamID string, seq int64) {
	if r := c.row(streamID, seq); r != nil {
		r.IsGap = true
	}
}

// Snapshot 은 streamID 의 경계 입력이다(boundary.Snapshot — 설계 3.2 가 이 캐시에 준 인터페이스).
// 모르는 스트림이면 컷오프가 없는 입력이다. RowsFrom 은 뷰를 복사 없이 내주고(그 인터페이스의 약속)
// 뒤의 push 가 그 행을 바꿀 수 있으므로, 캐시를 소유한 고루틴에서만 쓰고 워커로 넘기지 않는다.
// 컷오프가 있는지(Cutoff 의 ok)는 부르는 쪽이 본다.
func (c *Cache) Snapshot(streamID string) boundary.Snapshot {
	return snapshot{c.lookup(streamID)}
}

// snapshot 은 한 스트림 뷰를 boundary.Snapshot 으로 내보인다. v 가 nil 이면 컷오프도 행도 없다.
type snapshot struct{ v *view }

func (s snapshot) Cutoff() (int64, bool) {
	if s.v == nil {
		return 0, false
	}
	return s.v.cutoff, s.v.hasCutoff
}

func (s snapshot) RowsFrom(from int64) []boundary.Row {
	if s.v == nil {
		return nil
	}
	return s.v.rowsIn(from, s.v.nextSeq()-1)
}

// Session 은 streamID 의 회차 sessionID 다. 캐시가 모르면 거짓이다.
func (c *Cache) Session(streamID, sessionID string) (Session, bool) {
	v := c.lookup(streamID)
	if v == nil {
		return Session{}, false
	}
	s, ok := v.sessions[sessionID]
	return s, ok
}

// Playlist 는 소유 회차 owner 의 목록 입력이다 — 창 w([TailSeq, HeadSeq]) 안의 행을 세션 필터로
// 고른다(계획 「④ 캐시 착수 메모」):
//
//	소유 회차의 행 + 소유 회차가 계승 회차(inherits_session ≠ NULL)면 계승한 직전 회차의 행(접두)
//
// 계승 사슬은 1단계다 — 직전 회차가 또 계승 회차여도 그 앞 회차는 싣지 않는다(계획 2.3 ⑸ⓕ). TD 분할
// 회차(계승 없음 · base 복사)와 비계승 회차에는 앞 회차의 행을 싣지 않는다 — 실으면 MAP 만 바뀌고
// 끊김 표시가 빠진다(표시는 계승 회차의 첫 조각에만 선다 — rewind.HasDiscontinuityTag).
//
// 채우는 것은 StreamID · Cutoff · Owner · Rows · Sessions(행이 실린 접두 회차와 소유 회차)다. BaseURL
// 은 비워 둔다 — 발행 설정(PR ⓒ)이 채운다. 창이 비면 행 0 인 목록이다. 돌려주는 목록은 캐시와
// 저장소를 나누지 않는 새 값이라 발행 워커로 넘겨도 된다.
//
// 스트림 · 소유 회차 · 실린 행의 회차 가운데 하나라도 모르거나 컷오프가 없으면 거짓이다 — 머리(TD ·
// DISC-SEQ)와 MAP 을 정할 수 없다. 뷰가 장부를 다 담지 못했다는 뜻이라 되돌림은 Reload 다.
func (c *Cache) Playlist(streamID, owner string, w boundary.Window) (rewind.Playlist, bool) {
	v := c.lookup(streamID)
	if v == nil || !v.hasCutoff {
		return rewind.Playlist{}, false
	}
	o, ok := v.sessions[owner]
	if !ok {
		return rewind.Playlist{}, false
	}
	p := rewind.Playlist{StreamID: streamID, Cutoff: v.cutoff, Owner: owner}
	prefix, hasPrefix := o.InheritsSession, false
	for _, r := range v.rowsIn(w.TailSeq, w.HeadSeq) {
		switch {
		case r.SessionID == owner:
		case prefix != "" && r.SessionID == prefix:
			hasPrefix = true
		default:
			continue
		}
		p.Rows = append(p.Rows, r)
	}
	if hasPrefix {
		ps, ok := v.sessions[prefix]
		if !ok {
			return rewind.Playlist{}, false
		}
		p.Sessions = append(p.Sessions, ps.forRender())
	}
	p.Sessions = append(p.Sessions, o.forRender())
	return p, true
}

// forRender 는 렌더·발행 전 검사가 읽는 회차 값이다.
func (s Session) forRender() rewind.Session {
	return rewind.Session{
		ID: s.SessionID, InheritsSession: s.InheritsSession, DiscontinuityBase: s.DiscontinuityBase,
		TargetDuration: s.TargetDuration, MinSeq: s.MinSeq, InitUploaded: s.InitUploaded,
	}
}

// lookup 은 streamID 의 뷰다(모르면 nil).
func (c *Cache) lookup(streamID string) *view {
	if c == nil {
		return nil
	}
	return c.streams[streamID]
}

// set 은 streamID 의 뷰를 v 로 둔다.
func (c *Cache) set(streamID string, v *view) {
	if c.streams == nil {
		c.streams = map[string]*view{}
	}
	c.streams[streamID] = v
}

// row 는 streamID 의 seq 행 자리다(뷰에 없으면 nil) — 고치면 뷰의 행이 바뀐다.
func (c *Cache) row(streamID string, seq int64) *boundary.Row {
	v := c.lookup(streamID)
	if v == nil {
		return nil
	}
	if rows := v.rowsIn(seq, seq); len(rows) == 1 {
		return &rows[0]
	}
	return nil
}

// nextSeq 는 뷰에 이어 붙일 다음 행의 seq 다 — 행이 없으면 컷오프 행이다.
func (v *view) nextSeq() int64 {
	if len(v.rows) == 0 {
		return v.cutoff
	}
	return v.rows[len(v.rows)-1].Seq + 1
}

// rowsIn 은 seq 가 [from, to] 인 행이다. 뷰가 이어져 있어 자리로 자른다.
func (v *view) rowsIn(from, to int64) []boundary.Row {
	if len(v.rows) == 0 {
		return nil
	}
	first := v.rows[0].Seq
	lo, hi := max(from-first, 0), min(to-first+1, int64(len(v.rows)))
	if lo >= hi {
		return nil
	}
	return v.rows[lo:hi]
}
