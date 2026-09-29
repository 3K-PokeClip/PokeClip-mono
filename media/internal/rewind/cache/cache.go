// Package cache 는 되감기 목록의 입력을 메모리에 든다 — 장부(stream_segments)·GAP 원장
// (stream_published_gaps)·컷오프(stream_cutoffs)의 읽기 뷰다(설계 3.2 rewind/cache).
//
// 채우는 길은 둘이다. 적재(BeginLoad → 워커의 index.LoadRewindLedger → CompleteLoad — 장부에서 다시 읽은
// 값으로 한 스트림의 뷰를 통째로 갈아 끼운다)와 push(장부가 바뀐 직후 그 사실을 넘겨받는다 — INSERT·꼬리
// 교정은 인덱서가, ③ 확정·GAP 원장 등록·회차 사건은 발행 루프가 넘긴다). 그래서 목록을 만들 때 DB 를 묻지
// 않는다(프로필 4절 「매니페스트 합성은 평시 DB·S3 조회 0」). 파일 시스템을 보지 않고 무효화 연산이 없다 —
// 뷰가 장부와 어긋났을 때의 유일한 되돌림은 적재다.
//
// 적재 순서는 캐시가 소유한다(계획 4.5 A3 결정 2). 적재 중인 스트림의 push 는 뷰에 닿지 않고 로그에 쌓였다가
// CompleteLoad 가 뷰를 갈아 끼운 뒤 차례대로 재생된다 — push 는 그 사실의 커밋 뒤에만 오고 BeginLoad 는 워커의
// 스냅숏보다 먼저라, 스냅숏 밖의 사실은 전부 로그에 있다(A3 증명 Q1–Q4).
//
// 캐시는 한 고루틴(인덱서와 같은 D10 루프)이 소유한다. 락이 없고 읽기도 그 고루틴에서만 한다 — 발행
// 워커에는 Playlist 가 만든 값(새 슬라이스)만 넘긴다(설계 3.3). 적재 워커도 캐시를 만지지 않고 적재분을
// 값(index.RewindLedger)으로만 돌려준다.
package cache

import (
	"slices"
	"strings"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// Cache 는 스트림별 읽기 뷰의 모음이다. 영값을 그대로 쓴다. nil 이면 모든 메서드가 아무것도 하지
// 않는다 — 캐시를 조립하지 않은 프로세스에서도 인덱서가 INSERT 마다 부르기 때문이다(upload.Dirty 와
// 같은 nil 규약). 고루틴 안전하지 않다(패키지 설명).
type Cache struct {
	// Options 는 적재의 튜너블 셋이다. 영값인 칸은 기본값을 쓴다(Options.WithDefaults).
	Options Options

	streams map[string]*stream
	// demands 는 요구 적재 목록이다 — 스트림마다 하나(합친다) · 오른 차례대로(DemandedLoads).
	demands []LoadDemand
	// lastToken 은 마지막으로 낸 적재 토큰이다. 토큰은 1 부터라 0 은 어느 적재도 가리키지 않는다.
	lastToken LoadToken
}

// Options 는 적재의 튜너블 셋이다(계획 4.5 A3 결정 3 · 판단 J43 — r40 산정값). 칸이 영값이면 그 칸의 기본값을
// 쓴다 — 캐시는 영값으로 쓰는 타입이라, 규칙이 없으면 LoadPushBuffer 0 에서 적재 중 push 한 번에 넘쳐 스스로
// 다시 연다(장부 421 P-10). 0 을 「제한 없음」으로 읽지 않는다. 값을 넣는 조립은 커밋 7 이다.
type Options struct {
	// LedgerLookback 은 적재의 되짚기 길이다(기본 2 × boundary.WindowMS = 두 시간).
	LedgerLookback time.Duration
	// LedgerRowCap 은 적재의 행 수 상한이다(기본 10,000 — 되짚기 합산과 행 적재 모두).
	LedgerRowCap int
	// LoadPushBuffer 는 적재 중 push 로그의 상한이다(기본 1,024). 넘으면 캐시가 그 적재를 스스로 다시 연다.
	LoadPushBuffer int
}

// WithDefaults 는 영값인 칸을 기본값으로 채운 사본이다 — 캐시 안에서 읽는 값이자, 루프가 적재 작업의 범위
// (index.LedgerBounds)를 만들 때 읽는 값이다.
func (o Options) WithDefaults() Options {
	if o.LedgerLookback == 0 {
		o.LedgerLookback = 2 * time.Duration(boundary.WindowMS) * time.Millisecond
	}
	if o.LedgerRowCap == 0 {
		o.LedgerRowCap = 10000
	}
	if o.LoadPushBuffer == 0 {
		o.LoadPushBuffer = 1024
	}
	return o
}

// LoadToken 은 적재 한 번의 표지다 — BeginLoad 가 내고 CompleteLoad 가 견준다. 토큰이 다른 결과는 버린다
// (다시 연 적재가 있었다). 발행 층은 캐시를 임포트하지 않아 같은 값을 정수(uint64)로 나른다(판단 J40).
type LoadToken uint64

// LoadDemand 는 요구 적재 한 건이다(계획 4.5 A3 결정 6).
type LoadDemand struct {
	StreamID string
	// Hint 는 적재 하한을 이 seq 까지 내린다(A1 무결성 대조가 실패한 발행본의 MSN). nil 이면 없다.
	Hint *int64
}

// stream 은 한 스트림의 캐시 상태다 — 뷰 · 부정 표식 · 적재 가운데 무엇을 드는가. 항목은 BeginLoad 가 만들고
// Forget 이 지운다 — 항목이 있으면 적재 중이거나 뷰나 표식이 있다.
type stream struct {
	// v 는 컷오프가 있는 뷰다. nil 이면 뷰가 없다.
	v *view
	// noCutoff 는 부정 표식이다 — 적재 결과가 재생 뒤에도 컷오프 없음이었다(계획 4.5 A3 결정 5). v 가 nil 일 때만
	// 뜻이 있다. 표식이 있는 동안 push 는 무시하고, 주조 push 만 표식을 풀고 적재를 연다.
	noCutoff bool
	// load 는 진행 중인 적재다. nil 이면 적재 중이 아니다.
	load *load
}

// load 는 진행 중인 적재 하나다.
type load struct {
	token LoadToken
	// hint 는 BeginLoad 가 받은 힌트다 — 캐시가 적재를 다시 열어 요구 목록에 올릴 때 잇는다.
	hint *int64
	// drift 는 드리프트 대조가 연 적재인가다 — CompleteLoad 가 돌려준다(장부 421 P-5).
	drift bool
	// log 는 적재 중에 온 push 다(온 차례). CompleteLoad 가 뷰를 갈아 끼운 뒤 재생한다.
	log []func()
}

// view 는 한 스트림의 읽기 뷰다.
type view struct {
	// cutoff 는 stream_cutoffs.cutoff_seq 다. 한 번 정해지면 바뀌지 않는다.
	cutoff int64
	// floor 는 뷰 하한이다 — 행이 있으면 첫 행의 seq, 없으면 다음에 실릴 seq 다. 컷오프 이상이다(적재 하한
	// max(컷오프, min(되짚기 하한, 힌트)) — 계획 4.5 A3 결정 3).
	floor int64
	// rows 는 floor 부터 seq 가 1씩 이어지는 장부 행이다. 컷오프 아래 행은 목록에 실릴 수 없어(설계
	// 4.2 ⓐ) 싣지 않는다. 이어져 있으므로 seq 로 자리를 바로 센다.
	rows []boundary.Row
	// sessions 는 회차 축이다(session_id → 회차).
	sessions map[string]Session
	// mismatched 는 직전 감시의 드리프트 대조가 불일치였는가다 — 가드 단위는 스트림이다(장부 421 P-4). 적재가 뷰를
	// 갈아 끼우면 새로 센다.
	mismatched bool
	// driftDue 는 드리프트 대조가 이 뷰의 적재를 요구했다는 표지다 — 다음 BeginLoad 가 적재로 옮긴다(장부 421 P-5).
	driftDue bool
}

// Session 은 캐시가 드는 회차 한 행이다 — 장부의 여덟 열과 회차 최소 seq(index.RewindSession). 회차 최소 seq 는
// 적재면 장부 값(적재 하한과 무관 — 계획 4.5 A3 결정 3)이고 실행 중 개시면 그 개시 행의 seq 다. 컷오프 아래일
// 수 있다 — 끊김 표시 술어가 컷오프로 자른다(rewind.Session.MinSeq).
type Session struct {
	index.RewindSession
}

// BeginLoad 는 streamID 를 적재 중으로 두고 새 토큰을 낸다 — 그때부터 오는 push 는 로그에 쌓인다(계획 4.5 A3
// 결정 2). 루프는 이 토큰과 힌트로 적재 작업을 차선에 낸다(커밋 7). 적재 중인 스트림이면 적재를 다시 연다 — 새
// 토큰 · 빈 로그이고 옛 토큰의 결과는 버려진다. 다시 연 적재의 스냅숏이 로그의 사실을 담으므로 로그를 비워도
// 잃지 않는다(A3 증명 끝줄). 드리프트 대조가 요구한 적재면 그 까닭을 적재로 옮긴다(장부 421 P-5 — 다시 연 적재도
// 까닭을 잇는다).
func (c *Cache) BeginLoad(streamID string, hint *int64) LoadToken {
	if c == nil {
		return 0
	}
	st := c.ensure(streamID)
	c.lastToken++
	ld := &load{token: c.lastToken, hint: hint}
	if st.load != nil {
		ld.drift = st.load.drift
	}
	if st.v != nil && st.v.driftDue {
		ld.drift, st.v.driftDue = true, false
	}
	st.load = ld
	return ld.token
}

// CompleteLoad 는 적재 작업의 결과 l 을 반영한다 — token 이 그 스트림의 지금 적재일 때만 l 로 뷰를 통째로 갈아
// 끼우고(갈아 끼우기 전에 들고 있던 행·회차는 남지 않는다) 로그를 차례대로 재생한 뒤 적재를 끝낸다. 토큰이
// 다르거나 적재 중이 아니면 l 을 버리고 아무것도 바꾸지 않는다(applied 거짓).
//
// 재생은 멱등 · 단조다 — 행 추가는 seq 관문, ③ · GAP 은 참으로만, 교정은 같은 seq 의 마지막 값, 회차 state 는
// live → ending 한 방향, init 은 거짓 → 참, 계승 해제는 지우는 방향만이다. l 에 컷오프가 없으면 뷰 대신 부정
// 표식을 든다 — 재생 가운데 주조 push 가 있으면 그 push 가 표식을 풀고 적재를 다시 연다(재생 뒤에도 컷오프가
// 없을 때만 표식이 남는다).
//
// drift 는 적용한 적재가 드리프트 대조가 연 적재였는가다 — 발행 층의 로그 래퍼가 그때
// cache_drift_repaired(kind=head)를 남긴다(장부 421 P-5 — 감지가 아니라 적용이 교정이다).
func (c *Cache) CompleteLoad(streamID string, token LoadToken, l index.RewindLedger) (applied, drift bool) {
	st := c.lookup(streamID)
	if st == nil || st.load == nil || st.load.token != token {
		return false, false
	}
	ld := st.load
	st.load = nil
	st.reload(l)
	for _, p := range ld.log {
		p()
	}
	return true, ld.drift
}

// reload 는 적재분 l 로 뷰를 통째로 갈아 끼운다 — 부르는 곳은 CompleteLoad 하나다(계획 4.5 A3 결정 2 — 뷰를
// 바꾸는 입구의 열거를 컴파일이 보증한다). 행은 하한부터 1씩 이어진 데까지만 싣는다. 장부는 seq 를 비우지
// 않으므로 끊긴 입력은 읽기 결함이고, 끊긴 뒤를 실으면 경계가 빈자리를 모르고 넘어간다.
func (st *stream) reload(l index.RewindLedger) {
	if !l.HasCutoff {
		st.v, st.noCutoff = nil, true
		return
	}
	v := &view{cutoff: l.CutoffSeq, floor: max(l.FloorSeq, l.CutoffSeq), sessions: map[string]Session{}}
	for _, r := range l.Rows {
		if r.Seq != v.nextSeq() {
			break
		}
		// 두 행 타입은 필드(이름·타입·순서)가 같다 — 한쪽에만 열이 생기면 이 변환이 컴파일되지 않는다.
		v.rows = append(v.rows, boundary.Row(r))
	}
	for _, s := range l.Sessions {
		v.sessions[s.SessionID] = Session{RewindSession: s}
	}
	st.v, st.noCutoff = v, false
}

// Loading 은 streamID 가 적재 중인가다 — 적재 중에는 읽기가 「모른다」를 돌려준다(Snapshot 컷오프 없음 ·
// Playlist 거짓). 루프는 적재 중인 스트림에 틱을 내지 않고, 적재 중에 넘긴 Dirty 원소는 로그에 적힌 것을
// 반영으로 본다(커밋 7).
func (c *Cache) Loading(streamID string) bool {
	st := c.lookup(streamID)
	return st != nil && st.load != nil
}

// DemandLoad 는 streamID 의 적재를 요구 목록에 올린다 — 올리기만 하고 적재를 열지 않는다(판단 J38 — 발사는
// 루프가 DemandedLoads 를 비우며 한다). 같은 스트림의 요구가 겹치면 하나로 합치고 힌트는 가장 낮은 것을 쓴다
// (두 요구의 하한을 모두 덮는다). 적재 중인 스트림이면 그 적재의 힌트와도 합친다 — 적재가 실패해 다시 올릴 때
// (판단 J46) 힌트를 잃지 않는다.
func (c *Cache) DemandLoad(streamID string, hint *int64) {
	if c == nil {
		return
	}
	if st := c.lookup(streamID); st != nil && st.load != nil {
		hint = lowerHint(hint, st.load.hint)
	}
	c.demand(streamID, hint)
}

// DemandedLoads 는 쌓인 요구를 오른 차례대로 돌려주고 목록을 비운다. 루프는 요구마다 BeginLoad 로 토큰을 받아
// 적재 작업을 낸다(커밋 7).
func (c *Cache) DemandedLoads() []LoadDemand {
	if c == nil {
		return nil
	}
	d := c.demands
	c.demands = nil
	return d
}

// ApplyInsert 는 장부에 방금 커밋된 행 하나를 반영한다 — 인덱서가 INSERT 가 들어간 뒤 그 결과
// (index.SeedResult)를 그대로 넘긴다(INSERT push).
//
//	개시(SessionOpened)   회차 축을 세운다 — 개시가 쓴 base·계승·TD·first_pdt, state live, init 미확정,
//	                      회차 최소 seq = 이 seq. 계승 회차인데 뷰가 접두 회차를 모르면 적재를 요구한다(요구 ①).
//	                      TD 분할 개시면 같은 트랜잭션이 끝낸 옛 회차(EndingSessionID)를 ending 으로 둔다
//	행                    뷰의 다음 seq 면 싣는다 — 커밋 값 그대로이고, ③ 전이며 GAP 원장 밖이다
//
// 뷰가 없으면 뷰를 push 하나로 만들지 않는다(계획 4.5 A3 결정 6 ⑤). 주조 push(Seeded)는 부정 표식이 있어도,
// 회차 개시 push 는 뷰도 표식도 없을 때 캐시가 그 push 를 로그에 담고 스스로 적재를 열어 요구 목록에 올린다 —
// 부팅 때 live 회차가 없던 스트림 · Forget 된 스트림의 다음 방송 · 비개시 행에서 주조된 스트림이 이 길로 캐시에
// 들어온다. 그 밖의 push 는 할 일이 없다(뷰가 생기면 적재가 그 행을 싣는다). 뷰가 있는 스트림의 주조 표시는
// 뜻이 없다 — 컷오프는 적재가 준다.
//
// 이 행이 뷰의 다음 seq 가 아니면 아무것도 바꾸지 않는다 — 캐시가 장부 행을 놓쳤다(단일 쓰기자 전제가
// 깨져 다른 쓰기자가 그 seq 를 먼저 쓴 국면 — 인덱서의 seq 충돌 재적재). 놓친 뒤로는 뷰가 거기서 멈추고,
// 머리가 장부보다 뒤처지므로 정합성 감시(설계 4.1 (a) 캐시 드리프트)가 적재로 되돌린다. 빈자리를
// 두고 이어 붙이면 경계가 빈자리를 모르고 넘어가 목록 안 seq 가 끊긴다.
func (c *Cache) ApplyInsert(streamID string, seq int64, res index.SeedResult) {
	p := func() { c.ApplyInsert(streamID, seq, res) }
	if c == nil || c.logged(streamID, p) {
		return
	}
	st := c.lookup(streamID)
	if st == nil || st.v == nil {
		c.loadViewless(streamID, st, res, p)
		return
	}
	v := st.v
	if seq != v.nextSeq() {
		return
	}
	if res.SessionOpened {
		// TD 분할이면 같은 트랜잭션이 옛 회차를 ending 으로 보냈다(계획 4.5 B #1 · 판단 J62) — 새 회차 축을 세우는 이
		// 호출에서 그 전이도 반영한다. 반영은 ApplySessionEnding 그대로다(한 방향 · 모르는 회차면 할 일 없음 · 요구 0).
		if res.EndingSessionID != "" {
			c.ApplySessionEnding(streamID, res.EndingSessionID)
		}
		// state 는 개시 문장이 쓰는 'live' 이고 init·종료 사유는 개시 때 비어 있다(session.openSessionSQL).
		v.sessions[res.SessionID] = Session{RewindSession: index.RewindSession{
			SessionID: res.SessionID, State: "live", DiscontinuityBase: res.DiscontinuityBase,
			FirstPDT: res.PlaybackPDT, InheritsSession: res.InheritsSession, TargetDuration: res.TargetDuration,
			MinSeq: seq,
		}}
		if _, known := v.sessions[res.InheritsSession]; res.InheritsSession != "" && !known {
			c.demand(streamID, nil)
		}
	}
	v.rows = append(v.rows, boundary.Row{
		Seq: seq, SessionID: res.SessionID, DurationMS: res.DurationMS,
		PlaybackPDT: res.PlaybackPDT, PlaybackS3Key: res.PlaybackS3Key,
	})
}

// loadViewless 는 뷰가 없는 스트림(st 는 nil 일 수 있다)의 INSERT push 를 받는다(계획 4.5 A3 결정 6 ⑤) — 주조 push 는
// 부정 표식이 있어도, 회차 개시 push 는 표식이 없을 때 그 push p 를 로그에 담고 스스로 적재를 열어 요구 목록에
// 올린다. 그 밖의 push 는 할 일이 없어 상태를 만들지 않는다 — 모르는 스트림의 빈 항목이 쌓이지 않는다.
func (c *Cache) loadViewless(streamID string, st *stream, res index.SeedResult, p func()) {
	tombstone := st != nil && st.noCutoff
	if !res.Seeded && (!res.SessionOpened || tombstone) {
		return
	}
	c.BeginLoad(streamID, nil)
	c.streams[streamID].noCutoff = false
	c.logged(streamID, p)
	c.demand(streamID, nil)
}

// ApplyTailCorrection 은 꼬리 교정(index.Store.UpdateTail 성공)을 반영한다 — 인덱서가 교정이 장부에
// 들어간 뒤 넘긴다(교정 push). 그 행의 길이만 바뀐다: PDT·키는 장부 불변 트리거가 지키고, 회차 TD 는
// 개시 때 굳었다. 뷰에 없는 행이면 아무것도 하지 않는다.
func (c *Cache) ApplyTailCorrection(streamID string, seq int64, durationMS int32) {
	if c.logged(streamID, func() { c.ApplyTailCorrection(streamID, seq, durationMS) }) {
		return
	}
	if r := c.row(streamID, seq); r != nil {
		r.DurationMS = durationMS
	}
}

// ApplyPlaybackUploaded 는 ③ 확정(playback_upload_state = 'uploaded' CAS 성공)을 반영한다 — 그 행이
// settled 가 될 수 있다(설계 4.1 트리거 표 「③ 업로드 확인 CAS 성공」). 넘기는 쪽은 발행 루프의 Dirty
// 처리(커밋 7)다. 뷰에 없는 행이면 아무것도 하지 않는다 — 실시간 ③ 는 컷오프와 무관하게 돌아(계약
// 세그먼트인덱스 5-5 6항) 컷오프 아래 행의 확정도 온다.
func (c *Cache) ApplyPlaybackUploaded(streamID string, seq int64) {
	if c.logged(streamID, func() { c.ApplyPlaybackUploaded(streamID, seq) }) {
		return
	}
	if r := c.row(streamID, seq); r != nil {
		r.PlaybackUploaded = true
	}
}

// ApplyPublishedGap 은 GAP 원장 등록(stream_published_gaps INSERT 성공)을 반영한다 — ③ 가 안 올라간
// 행도 settled 가 되어 목록에 GAP 줄로 실린다(설계 4.1 「GAP 발행 확정 — 원장 INSERT 성공 후 캐시
// 반영」). 넘기는 쪽은 발행 루프(커밋 7)다. 뷰에 없는 행이면 아무것도 하지 않는다.
func (c *Cache) ApplyPublishedGap(streamID string, seq int64) {
	if c.logged(streamID, func() { c.ApplyPublishedGap(streamID, seq) }) {
		return
	}
	if r := c.row(streamID, seq); r != nil {
		r.IsGap = true
	}
}

// ApplyInitUploaded 는 회차 sessionID 의 init 확정(init CAS 성공)을 반영한다 — revoked 면 같은 CAS 가 stsd
// 비호환으로 계승을 풀었다(계획 4.5 A2 결정 8 — 해제는 init 확정과 한 push 로 닿는다). init 확정과 계승 해제
// (InheritsSession "" · base 0)가 한 호출에서 함께 바뀌어, 어느 렌더도 「init 확정 ∧ 낡은 계승」을 보지 못한다.
// 넘기는 쪽은 발행 루프의 Dirty 처리(커밋 6 · 7)와 감시 화해(ReconcileWatch)다. 모르는 회차면 아무것도 하지 않는다.
func (c *Cache) ApplyInitUploaded(streamID, sessionID string, revoked bool) {
	if c.logged(streamID, func() { c.ApplyInitUploaded(streamID, sessionID, revoked) }) {
		return
	}
	c.updateSession(streamID, sessionID, func(s *index.RewindSession) {
		s.InitUploaded = true
		if revoked {
			s.InheritsSession, s.DiscontinuityBase = "", 0
		}
	})
}

// ApplySessionEnding 은 회차 sessionID 의 live → ending 전이를 반영한다 — 넘기는 쪽은 종료 전이(커밋 6 — 4.1
// EndLive 1행)와 TD 분할 개시 push(ApplyInsert — 계획 4.5 B #1)다. 한 방향이다: ending · ended 인 회차는 그대로
// 둔다. 모르는 회차면 아무것도 하지 않는다.
func (c *Cache) ApplySessionEnding(streamID, sessionID string) {
	if c.logged(streamID, func() { c.ApplySessionEnding(streamID, sessionID) }) {
		return
	}
	c.updateSession(streamID, sessionID, func(s *index.RewindSession) {
		if s.State == "live" {
			s.State = "ending"
		}
	})
}

// ApplyInheritanceRevoked 는 회차 sessionID 의 계승 취소(P2′ 1행 — 계획 4.5 A2 결정 2)를 반영한다 —
// InheritsSession "" · base 0 이다(P2′ 문장이 쓴 값). 지우는 방향만이다. 넘기는 쪽은 발행 결과의 Revoked 를 받은
// 루프(커밋 7)다. 모르는 회차면 아무것도 하지 않는다.
func (c *Cache) ApplyInheritanceRevoked(streamID, sessionID string) {
	if c.logged(streamID, func() { c.ApplyInheritanceRevoked(streamID, sessionID) }) {
		return
	}
	c.updateSession(streamID, sessionID, func(s *index.RewindSession) {
		s.InheritsSession, s.DiscontinuityBase = "", 0
	})
}

// Trim 은 streamID 의 뷰에서 min(tail, publishedMSN) 앞의 행을 버린다(계획 4.5 A3 결정 5 · 뮤테이션 86) — tail 은
// 마지막으로 계산한 창 꼬리, publishedMSN 은 live 소유 회차 발행본의 MSN 이다. 부르는 자리는 발행 성공 뒤의
// 루프다(커밋 7). 다음 창 계산(prevTail = 꼬리)과 발행본 재구성(A1 무결성 대조 — [P.MSN, P 끝])이 둘 다 뷰 안에서
// 서도록 둘 가운데 앞쪽부터 남긴다. 뷰 하한 앞으로는 되돌아가지 않고 뷰 끝 너머까지 버려도 다음 행 자리는 그대로다.
// 뷰가 없거나 적재 중이면 아무것도 하지 않는다(적재가 뷰를 갈아 끼운다).
func (c *Cache) Trim(streamID string, tail, publishedMSN int64) {
	v := c.readable(streamID)
	if v == nil {
		return
	}
	from := min(tail, publishedMSN, v.nextSeq())
	if from <= v.floor {
		return
	}
	v.rows, v.floor = v.rowsIn(from, v.nextSeq()-1), from
}

// Forget 은 streamID 의 뷰나 부정 표식을 버린다 — live 회차가 없고 마지막 행 끝 lastEnd 가 now 보다 window
// (ReconnectWindow 300초 — config 한 자리)를 넘게 앞일 때만이다(계획 4.5 A3 결정 5 · 뮤테이션 87). lastEnd 는
// 루프가 댄다 — 인덱서 커서 Tail 끝, 커서가 없으면 그 스트림의 적재 완료 시각(커밋 7). 적재 중인 스트림은
// 건너뛴다. 버렸으면 참이다. 버린 스트림의 다음 방송은 뷰 없는 개시 push 로 다시 적재된다(A3 결정 6 ⑤).
func (c *Cache) Forget(streamID string, lastEnd, now time.Time, window time.Duration) bool {
	st := c.lookup(streamID)
	if st == nil || st.load != nil || !now.After(lastEnd.Add(window)) {
		return false
	}
	if st.v != nil {
		for _, s := range st.v.sessions {
			if s.State == "live" {
				return false
			}
		}
	}
	delete(c.streams, streamID)
	return true
}

// DriftProbes 는 30초 감시의 드리프트 대조 입력이다 — 뷰가 있고 적재 중이 아닌 스트림마다 캐시 머리 H 의 다음
// seq(H+1)다(스트림 순 · 설계 4.1 (a) · 판단 J39). 적재 중 · 부정 표식 스트림은 대조하지 않는다(계획 4.5 A3 결정 5 —
// 적재 중 뷰는 「모른다」 상태 · 표식은 주조 push 가 푼다). 루프가 감시 작업에 싣는다(커밋 7).
func (c *Cache) DriftProbes() []index.WatchProbe {
	if c == nil {
		return nil
	}
	var out []index.WatchProbe
	for id := range c.streams {
		if v := c.readable(id); v != nil {
			out = append(out, index.WatchProbe{StreamID: id, NextSeq: v.head() + 1})
		}
	}
	slices.SortFunc(out, func(a, b index.WatchProbe) int { return strings.Compare(a.StreamID, b.StreamID) })
	return out
}

// AuditDrift 는 (a) 드리프트 대조다(설계 4.1 (a) · 계획 부기 43 · 판단 J39) — 감시 결과의 drift 행마다 그 스트림의
// 뷰와 견준다. 불일치는 DB 컷오프 ≠ 캐시 컷오프(DB 에 없음 포함) 또는 DB 의 H+1 행이 settled 다(판정은
// boundary.Settled 한 곳). 캐시가 settled 로 아는 행은 DB 에서도 settled 라(push 는 커밋 뒤에만 · settled 단조 —
// A3 Q2) 캐시 머리가 뒤처졌는지는 H+1 한 행이 가른다. 결과가 닿은 시점의 캐시 머리가 그 H+1 이상이면 머리 쪽은
// 일치다 — 입력을 뜬 뒤 push 가 닿은 경합을 불일치로 세지 않는다(〔r53b — Q-2〕).
//
// 가드 단위는 스트림이다(장부 421 P-4) — 같은 스트림이 연속 두 감시에서 불일치면 요구 ③ 을 등재하고(까닭 드리프트
// · 로그 0 — P-5) 후보 seq 가 달라도 그렇다. 일치하면 연속이 풀린다. 감시가 실패한 틱에는 루프가 이 메서드를 부르지
// 않아 세지도 풀지도 않는다. 적재 중 · 뷰가 없는 스트림은 건너뛴다(A3 결정 5).
func (c *Cache) AuditDrift(rows []index.WatchDrift) {
	for _, r := range rows {
		v := c.readable(r.StreamID)
		if v == nil {
			continue
		}
		if !v.drifted(r) {
			v.mismatched = false
			continue
		}
		if v.mismatched {
			v.driftDue = true
			c.demand(r.StreamID, nil)
		}
		v.mismatched = true
	}
}

// drifted 는 감시 결과 r 이 이 뷰와 어긋나는가다(AuditDrift 의 불일치).
func (v *view) drifted(r index.WatchDrift) bool {
	if !r.HasCutoff || r.CutoffSeq != v.cutoff {
		return true
	}
	if v.head() >= r.NextSeq {
		return false
	}
	return r.HasNext && boundary.Settled(boundary.Row(r.Next), r.CutoffSeq)
}

// head 는 캐시 머리 H 다 — 뷰 첫 행부터 settled 로 이어진 마지막 seq 다(boundary.Compute 의 HeadSeq — 스캔을 뷰
// 하한에서 시작한다). 〔r53b — Q-1〕 접두가 비면(뷰 첫 행이 미settled · 행이 없음) 하한 − 1 이다 — 컷오프 − 1 이
// 아니다. 뷰가 모르는 하한 앞 행을 대조하지 않는다.
func (v *view) head() int64 {
	w, _ := boundary.Compute(snapshot{v}, v.floor)
	return w.HeadSeq
}

// ReconcileWatch 는 감시 화해다(계획 4.5 A2 결정 11) — 감시 결과의 live 행마다, DB 는 init 확정인데 캐시는 아니면
// init 확정과 계승 해제(DB 에 계승이 없고 캐시가 계승을 들었을 때만)를 한 호출(ApplyInitUploaded)로 반영하고, 반영한
// (스트림, 회차, revoked) 목록을 돌려준다 — 발행 층의 로그 래퍼가 cache_drift_repaired(kind=init_uploaded)로 남긴다.
// 그 밖에는 아무것도 하지 않는다. DB 쪽 두 값(init_uploaded_at NULL → 값 · inherits_session 값 → NULL)과 캐시 쪽이
// 모두 한 방향이라 push 와 화해가 어떤 순서로 와도 결과가 같다. 적재 중인 스트림 · 캐시가 모르는 회차는 건너뛴다.
func (c *Cache) ReconcileWatch(live []index.WatchLive) []index.InitRepair {
	var out []index.InitRepair
	for _, r := range live {
		v := c.readable(r.StreamID)
		if v == nil {
			continue
		}
		s, ok := v.sessions[r.SessionID]
		if !ok || !r.InitUploaded || s.InitUploaded {
			continue
		}
		revoked := r.InheritAbsent && s.InheritsSession != ""
		c.ApplyInitUploaded(r.StreamID, r.SessionID, revoked)
		out = append(out, index.InitRepair{StreamID: r.StreamID, SessionID: r.SessionID, Revoked: revoked})
	}
	return out
}

// Snapshot 은 streamID 의 경계 입력이다(boundary.Snapshot — 설계 3.2 가 이 캐시에 준 인터페이스).
// 모르는 스트림 · 뷰가 없는 스트림 · 적재 중인 스트림이면 컷오프가 없는 입력이다. RowsFrom 은 뷰를 복사 없이
// 내주고(그 인터페이스의 약속) 뒤의 push 가 그 행을 바꿀 수 있으므로, 캐시를 소유한 고루틴에서만 쓰고 워커로
// 넘기지 않는다. 컷오프가 있는지(Cutoff 의 ok)는 부르는 쪽이 본다.
func (c *Cache) Snapshot(streamID string) boundary.Snapshot {
	return snapshot{c.readable(streamID)}
}

// snapshot 은 한 스트림 뷰를 boundary.Snapshot 으로 내보인다. v 가 nil 이면 컷오프도 행도 없다.
type snapshot struct{ v *view }

func (s snapshot) Cutoff() (int64, bool) {
	if s.v == nil {
		return 0, false
	}
	return s.v.cutoff, true
}

func (s snapshot) RowsFrom(from int64) []boundary.Row {
	if s.v == nil {
		return nil
	}
	return s.v.rowsIn(from, s.v.nextSeq()-1)
}

// Session 은 streamID 의 회차 sessionID 다. 캐시가 모르면 거짓이다. 적재 중에도 옛 뷰의 회차를 읽는다 — 틱을
// 막는 것은 발행 게이트의 적재 중 칸이다(publish.ShouldTick · 판단 J37).
func (c *Cache) Session(streamID, sessionID string) (Session, bool) {
	st := c.lookup(streamID)
	if st == nil || st.v == nil {
		return Session{}, false
	}
	s, ok := st.v.sessions[sessionID]
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
// 스트림 · 소유 회차 · 실린 행의 회차 가운데 하나라도 모르거나 뷰가 없거나 적재 중이면 거짓이다 — 머리(TD ·
// DISC-SEQ)와 MAP 을 정할 수 없다. 뷰가 장부를 다 담지 못했다는 뜻이라 되돌림은 적재다(요구 ②).
func (c *Cache) Playlist(streamID, owner string, w boundary.Window) (rewind.Playlist, bool) {
	v := c.readable(streamID)
	if v == nil {
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

// logged 는 push 의 관문이다 — streamID 가 적재 중이면 p 를 그 로그에 적고 참을 돌려준다(push 메서드의 첫 줄 —
// 계획 4.5 A3 결정 2 · 증명 Q1). 로그가 LoadPushBuffer 를 넘으면 그 적재를 스스로 다시 열고 요구 목록에 올린다 —
// 새 토큰 · 빈 로그이고 옛 토큰의 결과는 버려진다(체크리스트 A-2 4). 넘친 push 는 다시 연 적재의 스냅숏에 든다.
func (c *Cache) logged(streamID string, p func()) bool {
	st := c.lookup(streamID)
	if st == nil || st.load == nil {
		return false
	}
	ld := st.load
	ld.log = append(ld.log, p)
	if len(ld.log) > c.Options.WithDefaults().LoadPushBuffer {
		c.BeginLoad(streamID, ld.hint)
		c.demand(streamID, ld.hint)
	}
	return true
}

// demand 는 요구 목록에 streamID 를 올린다 — 같은 스트림이 이미 있으면 힌트만 합친다.
func (c *Cache) demand(streamID string, hint *int64) {
	for i := range c.demands {
		if c.demands[i].StreamID == streamID {
			c.demands[i].Hint = lowerHint(c.demands[i].Hint, hint)
			return
		}
	}
	c.demands = append(c.demands, LoadDemand{StreamID: streamID, Hint: hint})
}

// lowerHint 는 두 힌트 가운데 낮은 것이다 — 없는 쪽은 하한을 내리지 않는다. 돌려주는 값은 새 포인터다(부른 쪽이
// 넘긴 포인터를 요구 목록이 나눠 들지 않는다).
func lowerHint(a, b *int64) *int64 {
	if a == nil {
		a, b = b, a
	}
	if a == nil {
		return nil
	}
	m := *a
	if b != nil {
		m = min(m, *b)
	}
	return &m
}

// updateSession 은 streamID 의 회차 sessionID 를 f 로 고친다(뷰나 그 회차가 없으면 아무것도 하지 않는다).
func (c *Cache) updateSession(streamID, sessionID string, f func(*index.RewindSession)) {
	st := c.lookup(streamID)
	if st == nil || st.v == nil {
		return
	}
	s, ok := st.v.sessions[sessionID]
	if !ok {
		return
	}
	f(&s.RewindSession)
	st.v.sessions[sessionID] = s
}

// lookup 은 streamID 의 상태다(모르면 nil).
func (c *Cache) lookup(streamID string) *stream {
	if c == nil {
		return nil
	}
	return c.streams[streamID]
}

// ensure 는 streamID 의 상태다 — 모르면 빈 상태를 만든다.
func (c *Cache) ensure(streamID string) *stream {
	if st := c.lookup(streamID); st != nil {
		return st
	}
	if c.streams == nil {
		c.streams = map[string]*stream{}
	}
	st := &stream{}
	c.streams[streamID] = st
	return st
}

// readable 은 streamID 의 읽을 수 있는 뷰다 — 뷰가 없거나 적재 중이면 nil 이다(적재 중 읽기는 「모른다」).
func (c *Cache) readable(streamID string) *view {
	st := c.lookup(streamID)
	if st == nil || st.load != nil {
		return nil
	}
	return st.v
}

// row 는 streamID 의 seq 행 자리다(읽을 수 있는 뷰에 없으면 nil) — 고치면 뷰의 행이 바뀐다.
func (c *Cache) row(streamID string, seq int64) *boundary.Row {
	v := c.readable(streamID)
	if v == nil {
		return nil
	}
	if rows := v.rowsIn(seq, seq); len(rows) == 1 {
		return &rows[0]
	}
	return nil
}

// nextSeq 는 뷰에 이어 붙일 다음 행의 seq 다 — 행이 없으면 뷰 하한이다.
func (v *view) nextSeq() int64 {
	if len(v.rows) == 0 {
		return v.floor
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
