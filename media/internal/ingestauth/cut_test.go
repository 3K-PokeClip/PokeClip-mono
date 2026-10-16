package ingestauth

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
)

// 시험의 X 는 끊는 토큰(testTokenA — 고정물의 거부 목록에 있다), Y 는 다른 토큰(testTokenB)이다. 시계는 고정물을 연
// 때(시작 표시)가 0 이고, 연결의 created 와 목록을 받은 시각(T_l)은 그 0 에서 잰다.

// 기록 갈래(설계 2-4 727행 · 착수 체크리스트 C2 210행): 기록에 있는 그 토큰의 id 를 모두 kick 한다. 판독 중 연결(가짜
// 목록에서 idle · path 빈 값 — 기록에만 있는 대응)도, 등록된 연결도, 이미 닫힌 연결(404 — 이미 없음)도 기록의 id 로
// 가른다. 등록된 연결은 목록 갈래에도 들지만 앞 출처(기록)에서 센다. 둘째 판독은 같은 id 를 다시 kick 하지도 세지도
// 않는다. 기록에 있는 다른 토큰의 연결은 그대로다.
func TestCutLogBranch(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	reading, registered, closed, other := testConnID(1), testConnID(2), testConnID(3), testConnID(4)
	f.mtx.add(fakeConn{id: reading, created: f.at(500 * time.Millisecond)})
	f.mtx.add(fakeConn{id: registered, created: f.at(500 * time.Millisecond), state: statePublish, path: testTokenA})
	f.mtx.add(fakeConn{id: other, created: f.at(500 * time.Millisecond)})
	f.clock.moveTo(time.Second)
	for _, id := range []string{reading, registered, closed} {
		f.mustAllow(t, testTokenA, id)
	}
	f.mustAllow(t, testTokenB, other)
	f.clock.moveTo(10 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if err != nil {
		t.Errorf("Cut(X) returned error: %v", err)
	}
	checkReport(t, rep, CutReport{FromLog: 3, Kicked: 2, AlreadyGone: 1, Covered: 10 * time.Second})
	if got, want := slices.Sorted(slices.Values(f.mtx.kicked200())), []string{reading, registered}; !slices.Equal(got, want) {
		t.Errorf("kicked %v, want %v (the reading and the registered connection — ids from the log)", got, want)
	}
	if n := f.mtx.countRequests("POST /v3/srtconns/kick/" + closed); n != 1 {
		t.Errorf("kick requests for the closed connection = %d, want 1 (the second read does not kick it again)", n)
	}
	if got, want := f.mtx.live(), []string{other}; !slices.Equal(got, want) {
		t.Errorf("live connections after Cut = %v, want %v (Y's connection stays)", got, want)
	}
}

// 목록 갈래(설계 3-4 2.4): 기록에 없어도 목록에서 path == 토큰 ∧ state == publish 인 연결을 kick 한다 — 줄이 밀려난
// 등록 연결이다. 다른 토큰에 등록된 연결은 그대로다.
func TestCutListBranch(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	mine, theirs := testConnID(1), testConnID(2)
	f.mtx.add(fakeConn{id: mine, created: f.at(-100 * time.Second), state: statePublish, path: testTokenA})
	f.mtx.add(fakeConn{id: theirs, created: f.at(-100 * time.Second), state: statePublish, path: testTokenB})
	f.clock.moveTo(10 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if err != nil {
		t.Errorf("Cut(X) returned error: %v", err)
	}
	checkReport(t, rep, CutReport{FromList: 1, Kicked: 1, Covered: 10 * time.Second})
	if got, want := f.mtx.kicked200(), []string{mine}; !slices.Equal(got, want) {
		t.Errorf("kicked %v, want %v", got, want)
	}
	if got, want := f.mtx.live(), []string{theirs}; !slices.Equal(got, want) {
		t.Errorf("live connections after Cut = %v, want %v", got, want)
	}
}

// 기록 밖 갈래(설계 2-4 728행): 기록이 두 번 회전해 줄이 밀려난 판독 중 연결 — created 가 Since 앞이고 목록을 받은
// 때 생긴 지 31초 — 을 끊는다. 회전은 OpenConnLog 의 maxBytes 를 작게 줘(1바이트 — Append 마다 회전) 일으킨다.
func TestCutUnmappedBranch(t *testing.T) {
	f := newCutFixture(t, 1)
	lost := testConnID(1)
	f.mtx.add(fakeConn{id: lost, created: f.at(-21 * time.Second)})
	f.clock.moveTo(time.Second)
	f.mustAllow(t, testTokenA, lost) // 세대 G1(시작 표시 1초)
	f.clock.moveTo(2 * time.Second)
	f.mustAllow(t, testTokenB, testConnID(2)) // 회전 1 — G2(시작 표시 2초), 직전 세대 G1
	f.clock.moveTo(3 * time.Second)
	f.mustAllow(t, testTokenB, testConnID(3)) // 회전 2 — G3(시작 표시 3초), 직전 세대 G2. G1 은 사라졌다
	if a, err := LoadAllowed(f.connDir); err != nil || a.Tokens[lost] != "" || !a.Since.Equal(f.at(2*time.Second)) {
		t.Fatalf("Setup: LoadAllowed = %+v, %v, want %s pushed out and Since %v", a, err, lost, f.at(2*time.Second))
	}
	f.clock.moveTo(10 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if err != nil {
		t.Errorf("Cut(X) returned error: %v", err)
	}
	checkReport(t, rep, CutReport{FromUnmapped: 1, Kicked: 1, Covered: 8 * time.Second})
	if got, want := f.mtx.kicked200(), []string{lost}; !slices.Equal(got, want) {
		t.Errorf("kicked %v, want %v (the reading connection pushed out of the log, 31s old)", got, want)
	}
}

// 부수 피해 없음(설계 2-4 729행): 기록에 있는 다른 토큰의 판독 중 연결은 생긴 지 120초여도 끊지 않는다
// — 기록 밖 갈래는 「어느 토큰으로도 기록에 없는」 연결만 본다. 그 연결의 created 는 Since 앞이다(기록이 담기 전에
// 생겨 기다리다 허용됐다). 다른 토큰에 등록된 연결과, 끊는 토큰의 경로를 읽는 연결(state read)도 그대로다 — 목록
// 갈래는 송출(publish)만 본다.
func TestCutLeavesOtherTokensAlone(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	waiting, publishing, reader := testConnID(1), testConnID(2), testConnID(3)
	f.mtx.add(fakeConn{id: waiting, created: f.at(-110 * time.Second)})
	f.mtx.add(fakeConn{id: publishing, created: f.at(-200 * time.Second), state: statePublish, path: testTokenB})
	f.mtx.add(fakeConn{id: reader, created: f.at(-300 * time.Second), state: stateRead, path: testTokenA})
	f.clock.moveTo(time.Second)
	f.mustAllow(t, testTokenB, waiting)
	f.mustAllow(t, testTokenB, publishing)
	f.clock.moveTo(10 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if err != nil {
		t.Errorf("Cut(X) returned error: %v", err)
	}
	checkReport(t, rep, CutReport{Covered: 10 * time.Second})
	if got := f.mtx.kicked200(); len(got) != 0 {
		t.Errorf("kicked %v, want none (Y's connections and the reader of X's path)", got)
	}
	if got, want := f.mtx.live(), slices.Sorted(slices.Values([]string{waiting, publishing, reader})); !slices.Equal(got, want) {
		t.Errorf("live connections after Cut = %v, want %v", got, want)
	}
}

// 미완(설계 2-4 730행): created 가 Since 앞이고 생긴 지 20초인 기록 밖 판독 중 연결은 끊지 않고 미완이다 —
// unmapped_young 1 · ErrConnLogWindowShort(종료 코드 1 connlog_window_short). 끊을 것은 끝까지 끊는다(3-4 2.5).
func TestCutUnmappedYoungIsIncomplete(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	young, mine := testConnID(1), testConnID(2)
	f.mtx.add(fakeConn{id: young, created: f.at(-10 * time.Second)})
	f.mtx.add(fakeConn{id: mine, created: f.at(500 * time.Millisecond)})
	f.clock.moveTo(time.Second)
	f.mustAllow(t, testTokenA, mine)
	f.clock.moveTo(10 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if !errors.Is(err, ErrConnLogWindowShort) {
		t.Errorf("Cut(X) with a 20s-old unmapped reading connection = %v, want ErrConnLogWindowShort", err)
	}
	checkReport(t, rep, CutReport{FromLog: 1, Kicked: 1, UnmappedYoung: 1, Covered: 10 * time.Second})
	if got, want := f.mtx.kicked200(), []string{mine}; !slices.Equal(got, want) {
		t.Errorf("kicked %v, want %v (the young one stays, the rest is cut)", got, want)
	}
	if got, want := f.mtx.live(), []string{young}; !slices.Equal(got, want) {
		t.Errorf("live connections after Cut = %v, want %v", got, want)
	}
}

// 기록 밖 미완의 셈(UnmappedYoung)은 두 판독의 합집합이다(설계 3-4 2.8 — 둘째 판독의 미완 조건도 첫 판독과 같다). 첫
// 판독에서 어렸던 기록 밖 판독 중 연결이 둘째 판독 전에 닫혀도(인증 거부 · 송출자의 끊음) 첫 판독이 그 연결을 끊지 않고
// 지나갔다는 것은 보고에 남는다 — 에러(ErrConnLogWindowShort)와 셈이 어긋나지 않게. 연결은 첫 목록을 만든 뒤
// 닫는다(가짜의 고리).
func TestCutUnmappedYoungCountsBothReads(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	young := testConnID(1)
	f.mtx.add(fakeConn{id: young, created: f.at(-10 * time.Second)})
	f.clock.moveTo(10 * time.Second)
	f.mtx.set(func(m *fakeMediaMTX) {
		m.onList = func(call int) {
			if call == 1 {
				m.close(young)
			}
		}
	})

	rep, err := f.cut(t, testTokenA)

	if !errors.Is(err, ErrConnLogWindowShort) {
		t.Errorf("Cut(X) = %v, want ErrConnLogWindowShort (the first read left a young connection)", err)
	}
	checkReport(t, rep, CutReport{UnmappedYoung: 1, Covered: 10 * time.Second})
	if got := f.mtx.live(); len(got) != 0 {
		t.Errorf("live connections after Cut = %v, want none (the young one closes before the second read — else the scenario did not happen)", got)
	}
}

// 기록 밖이어도 created 가 Since 뒤면(새로 인증을 기다리는 연결) 끊지 않는다 — 생긴 지 95초여도(설계 2-4 731행). 나이가
// 아니라 created 와 Since 의 절대 시각이 가른다.
func TestCutLeavesConnsCreatedAfterSince(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	awaiting := testConnID(1)
	f.mtx.add(fakeConn{id: awaiting, created: f.at(5 * time.Second)})
	f.clock.moveTo(100 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if err != nil {
		t.Errorf("Cut(X) returned error: %v", err)
	}
	checkReport(t, rep, CutReport{Covered: 100 * time.Second})
	if got := f.mtx.kicked200(); len(got) != 0 {
		t.Errorf("kicked %v, want none (created after Since)", got)
	}
}

// 상태 디렉터리를 비우고 응답기를 다시 띄운 뒤(설계 2-4 732행): Since 는 새 시작 표시의 at 이다. 그 전부터 있던 판독 중
// 연결은 — 지워진 기록에 그 토큰으로 있었더라도 — 기록 밖 갈래에 들고(30초 넘음 — 끊음), 그 토큰에 등록된 연결은
// 목록 갈래가 끊는다.
func TestCutAfterStateDirRecreated(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	wasLogged, waiting, publishing := testConnID(1), testConnID(2), testConnID(3)
	f.mtx.add(fakeConn{id: wasLogged, created: f.at(500 * time.Millisecond)})
	f.mtx.add(fakeConn{id: waiting, created: f.at(50 * time.Second)})
	f.mtx.add(fakeConn{id: publishing, created: f.at(30 * time.Second), state: statePublish, path: testTokenA})
	f.clock.moveTo(time.Second)
	f.mustAllow(t, testTokenA, wasLogged)
	// 운영자가 상태 디렉터리를 비우고 응답기를 다시 띄운다 — 새 기록이 새 시작 표시(100초)로 열린다.
	closeConnLog(f.conns)
	removeFile(t, filepath.Join(f.connDir, connLogName))
	f.clock.moveTo(100 * time.Second)
	conns, err := OpenConnLog(f.connDir, 1<<20, f.clock.now)
	if err != nil {
		t.Fatalf("Setup: reopening the connection log returned error: %v", err)
	}
	t.Cleanup(func() { closeConnLog(conns) })
	f.conns = conns
	f.clock.moveTo(101 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if err != nil {
		t.Errorf("Cut(X) returned error: %v", err)
	}
	checkReport(t, rep, CutReport{FromList: 1, FromUnmapped: 2, Kicked: 3, Covered: time.Second})
	if got := f.mtx.live(); len(got) != 0 {
		t.Errorf("live connections after Cut = %v, want none", got)
	}
}

// 시작 표시 뒤 인증 대기(설계 2-4 733행): 기록에 시작 표시만 있고, 가짜 목록에 시작 표시 뒤에 생긴
// path 빈 연결이 있으면 unmapped_young 은 0, 에러는 nil(종료 코드 0)이다. Since 를 읽은 시각으로 두면 이 연결이 미완으로
// 잡혀 기록이 처음 열린 뒤 30초 동안 미완이 되풀이된다(설계 3-4 1055행).
func TestCutStartMarkOnlyAwaitingAuth(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	awaiting := testConnID(1)
	f.mtx.add(fakeConn{id: awaiting, created: f.at(2 * time.Second)})
	f.clock.moveTo(5 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if err != nil {
		t.Errorf("Cut(X) with only the start mark = %v, want nil", err)
	}
	checkReport(t, rep, CutReport{Covered: 5 * time.Second})
	if got := f.mtx.kicked200(); len(got) != 0 {
		t.Errorf("kicked %v, want none", got)
	}
}

// 기록 밖 갈래는 생긴 지 UnmappedMinAge 를 「넘은」 연결만 끊는다(설계 3-4 2.4). 정확히 30초는 미완, 30초 + 1ns 는
// 끊는다.
func TestCutUnmappedAgeBoundary(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	exact, over := testConnID(1), testConnID(2)
	f.clock.moveTo(10 * time.Second)
	f.mtx.add(fakeConn{id: exact, created: f.at(10*time.Second - UnmappedMinAge)})
	f.mtx.add(fakeConn{id: over, created: f.at(10*time.Second - UnmappedMinAge - time.Nanosecond)})

	rep, err := f.cut(t, testTokenA)

	if !errors.Is(err, ErrConnLogWindowShort) {
		t.Errorf("Cut(X) = %v, want ErrConnLogWindowShort (exactly 30s is young)", err)
	}
	checkReport(t, rep, CutReport{FromUnmapped: 1, Kicked: 1, UnmappedYoung: 1, Covered: 10 * time.Second})
	if got, want := f.mtx.kicked200(), []string{over}; !slices.Equal(got, want) {
		t.Errorf("kicked %v, want %v", got, want)
	}
}

// kick 차례(설계 3-4 2.6): 판독마다 그 판독의 목록에 있는 id(기록 갈래 가운데 목록에 있는 것 · 목록 갈래 · 기록 밖
// 갈래)를 먼저 kick 하고, 기록에만 있는 id(이미 닫힌 연결의 줄)는 그 뒤에 한다. 낡은 줄이 수천이어도(재접속 · 틀린
// passphrase 시도 — 줄은 허용 때 쓰인다) cut 의 시한 안에 살아 있는 연결에 먼저 닿게. 낡은 id 는 id 순으로 살아 있는 id
// 보다 앞서게 둔다.
func TestCutKicksListedConnsFirst(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	stale := []string{testConnID(1), testConnID(2), testConnID(3)} // 기록에만 있다 — 이미 닫힌 연결의 줄
	// live 는 기록과 목록에, pushed 는 목록에만(줄이 밀려난 등록 연결) 있고, lost 는 기록 밖 판독 중(41초)이다.
	live, pushed, lost := testConnID(7), testConnID(8), testConnID(9)
	f.mtx.add(fakeConn{id: live, created: f.at(500 * time.Millisecond), state: statePublish, path: testTokenA})
	f.mtx.add(fakeConn{id: pushed, created: f.at(-100 * time.Second), state: statePublish, path: testTokenA})
	f.mtx.add(fakeConn{id: lost, created: f.at(-31 * time.Second)})
	f.clock.moveTo(time.Second)
	for _, id := range append(slices.Clone(stale), live) {
		f.mustAllow(t, testTokenA, id)
	}
	f.clock.moveTo(10 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if err != nil {
		t.Errorf("Cut(X) returned error: %v", err)
	}
	checkReport(t, rep, CutReport{FromLog: 4, FromList: 1, FromUnmapped: 1, Kicked: 3, AlreadyGone: 3, Covered: 10 * time.Second})
	var kicks []string
	for _, r := range f.mtx.requestLog() {
		if id, ok := strings.CutPrefix(r, "POST /v3/srtconns/kick/"); ok {
			kicks = append(kicks, id)
		}
	}
	listed := []string{live, pushed, lost}
	lastListed, firstStale := -1, len(kicks)
	for i, id := range kicks {
		if slices.Contains(listed, id) {
			lastListed = i
		}
		if slices.Contains(stale, id) {
			firstStale = min(firstStale, i)
		}
	}
	if len(kicks) != len(listed)+len(stale) || lastListed > firstStale {
		t.Errorf("kick order %v, want the listed ids %v first and the log-only ids %v after", kicks, listed, stale)
	}
}

// 둘째 판독(설계 2-4 735행 · 3-4 2.8): 첫 판독 뒤 기록된 그 토큰의 줄의 id 를 끊는다 — 마지막 확인에서 거부될 연결이라
// 끊어도 무해하다. 연결이 아직 목록에 있으면 kick 이 200, 이미 없으면 404(이미 없음)다. 줄은 첫 목록을 만든 뒤에
// 적는다(가짜 MediaMTX 의 고리).
func TestCutSecondRead(t *testing.T) {
	for _, tc := range []struct {
		name string
		live bool
		want CutReport
	}{
		{name: "연결이_목록에_있음", live: true, want: CutReport{FromLog: 1, Kicked: 1, Covered: 10 * time.Second}},
		{name: "연결이_이미_없음", live: false, want: CutReport{FromLog: 1, AlreadyGone: 1, Covered: 10 * time.Second}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newCutFixture(t, 1<<20)
			late := testConnID(1)
			f.clock.moveTo(10 * time.Second)
			f.mtx.set(func(m *fakeMediaMTX) {
				m.onList = func(call int) {
					if call != 1 {
						return
					}
					if tc.live {
						m.add(fakeConn{id: late, created: f.at(9 * time.Second)})
					}
					if err := f.allow(testTokenA, late); err != nil {
						t.Errorf("fake MediaMTX: %v", err)
					}
				}
			})

			rep, err := f.cut(t, testTokenA)

			if err != nil {
				t.Errorf("Cut(X) returned error: %v", err)
			}
			checkReport(t, rep, tc.want)
			if n := f.mtx.countRequests("POST /v3/srtconns/kick/" + late); n != 1 {
				t.Errorf("kick requests for the line written after the first read = %d, want 1", n)
			}
			if got := f.mtx.live(); len(got) != 0 {
				t.Errorf("live connections after Cut = %v, want none", got)
			}
		})
	}
}

// 거부 목록에 없는 토큰은 끊지 않는다(설계 3-4 2.1 — 종료 코드 2 not_denied). 기록 · 목록 · kick 을 하나도 하지 않는다
// — 끊고 다시 붙는 일을 막는다.
func TestCutRequiresDenylistedToken(t *testing.T) {
	for _, tc := range []struct {
		name    string
		content string
		remove  bool
	}{
		{name: "다른_토큰만", content: testTokenB + "\n"},
		{name: "빈_파일", content: ""},
		{name: "파일_없음", remove: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newCutFixture(t, 1<<20)
			f.mtx.add(fakeConn{id: testConnID(1), created: f.at(-100 * time.Second), state: statePublish, path: testTokenA})
			if tc.remove {
				removeFile(t, f.denyPath)
			} else {
				writeFile(t, f.denyPath, tc.content)
			}

			rep, err := f.cut(t, testTokenA)

			if !errors.Is(err, ErrNotDenied) {
				t.Errorf("Cut(X) with X not in the deny list = %v, want ErrNotDenied", err)
			}
			checkReport(t, rep, CutReport{})
			if reqs := f.mtx.requestLog(); len(reqs) != 0 {
				t.Errorf("Control API requests = %v, want none", reqs)
			}
		})
	}
}

// 차례(설계 2-4 608행 · 3-4 증명 T_d < T_0 < T_c): 거부 목록 확인이 기록 판독보다 먼저다. 기록 판독 도중에 적힌 거부는
// 보지 않는다 — 판독을 먼저 하면, 운영자의 쓰기와 겹친 cut 이 판독에 없는 연결을 둔 채 끝까지 간다.
func TestCutChecksDenyListBeforeReadingLog(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	writeFile(t, f.denyPath, "")
	betweenCalls := 0
	f.between = func() {
		betweenCalls++
		writeFile(t, f.denyPath, testTokenA+"\n")
	}

	_, err := f.cut(t, testTokenA)

	if !errors.Is(err, ErrNotDenied) {
		t.Errorf("Cut(X) whose deny line is written during the log read = %v, want ErrNotDenied", err)
	}
	if betweenCalls != 0 {
		t.Errorf("the log was read %d times before the deny list check, want 0", betweenCalls)
	}
	if reqs := f.mtx.requestLog(); len(reqs) != 0 {
		t.Errorf("Control API requests = %v, want none", reqs)
	}
}

// 거부 목록을 읽지 못하면 끊지 않는다(설계 3-4 2.1 — 종료 코드 1 denylist_unreadable). 기록 · 목록 · kick 을 하나도
// 하지 않는다.
func TestCutDenyListUnreadable(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	f := newCutFixture(t, 1<<20)
	f.mtx.add(fakeConn{id: testConnID(1), created: f.at(-100 * time.Second), state: statePublish, path: testTokenA})
	chmod(t, f.denyPath, 0o000)

	rep, err := f.cut(t, testTokenA)

	if !errors.Is(err, ErrDenyListUnreadable) || errors.Is(err, ErrNotDenied) {
		t.Errorf("Cut(X) with an unreadable deny list = %v, want ErrDenyListUnreadable", err)
	}
	checkReport(t, rep, CutReport{})
	if reqs := f.mtx.requestLog(); len(reqs) != 0 {
		t.Errorf("Control API requests = %v, want none", reqs)
	}
}

// 기록을 읽지 못하거나 줄이 하나도 없으면(시작 표시도 없음) 목록 갈래만 끝까지 하고 ErrConnLogUnreadable(종료 코드 1
// connlog_unreadable)이다(설계 3-4 2.2). 그 판독은 기록 갈래 · 기록 밖 갈래를 가를 수 없어 하지 않는다.
func TestCutConnLogUnreadable(t *testing.T) {
	for _, tc := range []struct {
		name     string
		spoilLog func(t *testing.T, f *cutFixture)
	}{
		{name: "줄이_하나도_없음", spoilLog: func(t *testing.T, f *cutFixture) { f.connDir = t.TempDir() }},
		{name: "읽기_권한_없음", spoilLog: func(t *testing.T, f *cutFixture) {
			if os.Geteuid() == 0 {
				t.Skip("root 는 권한 검사를 우회한다")
			}
			chmod(t, filepath.Join(f.connDir, connLogName), 0o000)
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newCutFixture(t, 1<<20)
			publishing, waiting, logged := testConnID(1), testConnID(2), testConnID(3)
			f.mtx.add(fakeConn{id: publishing, created: f.at(-100 * time.Second), state: statePublish, path: testTokenA})
			f.mtx.add(fakeConn{id: waiting, created: f.at(-100 * time.Second)})
			f.mtx.add(fakeConn{id: logged, created: f.at(500 * time.Millisecond)})
			f.clock.moveTo(time.Second)
			f.mustAllow(t, testTokenA, logged)
			f.clock.moveTo(10 * time.Second)
			tc.spoilLog(t, f)

			rep, err := f.cut(t, testTokenA)

			if !errors.Is(err, ErrConnLogUnreadable) {
				t.Errorf("Cut(X) with an unreadable log = %v, want ErrConnLogUnreadable", err)
			}
			checkReport(t, rep, CutReport{FromList: 1, Kicked: 1})
			if got, want := f.mtx.kicked200(), []string{publishing}; !slices.Equal(got, want) {
				t.Errorf("kicked %v, want %v (the list branch only)", got, want)
			}
		})
	}
}

// Control API 가 실패하면 nil 로 끝나지 않는다(설계 2-6 777행 — 종료 코드 1). 상태 코드를 담은 *mtxctl.Error 가 그대로
// 와서 cut 이 api_auth(401 — 응답기 부재 · 자격 불일치)와 api_unreachable 을 가를 수 있다. 목록을 받지 못하면 그
// 자리에서 멈추고(kick · 조회 0), kick 하나가 실패해도 나머지는 끝까지 끊는다(3-4 2.5). 조회 실패는 사라진 것으로
// 세지 않는다. 에러여도 보고는 그때까지 한 일을 담는다.
func TestCutAPIFailures(t *testing.T) {
	a, b := testConnID(1), testConnID(2)
	tests := []struct {
		name      string
		setup     func(t *testing.T, f *cutFixture)
		cancelCtx bool
		wantOp    string // *mtxctl.Error 의 Op
		wantKind  string
		wantCode  int // Kind 가 status 일 때
		want      CutReport
		wantKicks []string
		wantNoGet bool
	}{
		{name: "목록_401_응답기_부재", setup: func(t *testing.T, f *cutFixture) {
			f.mtx.set(func(m *fakeMediaMTX) { m.authorize = func(string, string) bool { return false } })
		}, wantOp: "list", wantKind: "status", wantCode: http.StatusUnauthorized, wantNoGet: true},
		{name: "목록_404_경로_없음", setup: func(t *testing.T, f *cutFixture) {
			f.mtx.set(func(m *fakeMediaMTX) { m.missing["list"] = true })
		}, wantOp: "list", wantKind: "status", wantCode: http.StatusNotFound, wantNoGet: true},
		{name: "닿지_않음", setup: func(t *testing.T, f *cutFixture) { f.mtx.srv.Close() }, wantOp: "list", wantKind: "unreachable", wantNoGet: true},
		{name: "ctx_끝남", cancelCtx: true, wantOp: "list", wantKind: "timeout", wantNoGet: true},
		{name: "kick_500_뒤에도_다음_id_를_끊음", setup: func(t *testing.T, f *cutFixture) {
			f.mtx.set(func(m *fakeMediaMTX) { m.failKick[a] = http.StatusInternalServerError })
		}, wantOp: "kick", wantKind: "status", wantCode: http.StatusInternalServerError,
			want: CutReport{FromLog: 2, Kicked: 1, Remaining: []string{a}, Covered: 10 * time.Second}, wantKicks: []string{b}},
		{name: "확인_조회_500", setup: func(t *testing.T, f *cutFixture) {
			f.mtx.set(func(m *fakeMediaMTX) { m.fail["get"] = http.StatusInternalServerError })
		}, wantOp: "get", wantKind: "status", wantCode: http.StatusInternalServerError,
			want: CutReport{FromLog: 2, Kicked: 2, Remaining: []string{a, b}, Covered: 10 * time.Second}, wantKicks: []string{a, b}},
		{name: "둘째_목록_500", setup: func(t *testing.T, f *cutFixture) {
			f.mtx.set(func(m *fakeMediaMTX) {
				m.onList = func(call int) {
					m.set(func(m *fakeMediaMTX) { m.fail["list"] = http.StatusInternalServerError })
				}
			})
		}, wantOp: "list", wantKind: "status", wantCode: http.StatusInternalServerError,
			want: CutReport{FromLog: 2, Kicked: 2, Covered: 10 * time.Second}, wantKicks: []string{a, b}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newCutFixture(t, 1<<20)
			f.mtx.add(fakeConn{id: a, created: f.at(500 * time.Millisecond)})
			f.mtx.add(fakeConn{id: b, created: f.at(500 * time.Millisecond)})
			f.clock.moveTo(time.Second)
			f.mustAllow(t, testTokenA, a)
			f.mustAllow(t, testTokenA, b)
			f.clock.moveTo(10 * time.Second)
			if tc.setup != nil {
				tc.setup(t, f)
			}
			ctx := t.Context()
			if tc.cancelCtx {
				var cancel context.CancelFunc
				ctx, cancel = context.WithCancel(ctx)
				cancel()
			}

			rep, err := f.cutter().cut(ctx, testTokenA, NewDenyList(f.denyPath, discardLogger()), f.connDir, f.client)

			var e *mtxctl.Error
			if !errors.As(err, &e) || e.Op != tc.wantOp || e.Kind != tc.wantKind || e.Status != tc.wantCode {
				t.Errorf("Cut(X) = %v, want *mtxctl.Error{Op: %s, Kind: %s, Status: %d}", err, tc.wantOp, tc.wantKind, tc.wantCode)
			}
			if errors.Is(err, ErrVerifyTimeout) && tc.wantOp == "get" {
				t.Errorf("Cut(X) = %v, want the lookup failure rather than ErrVerifyTimeout", err)
			}
			checkReport(t, rep, tc.want)
			if got := slices.Sorted(slices.Values(f.mtx.kicked200())); !slices.Equal(got, tc.wantKicks) {
				t.Errorf("kicked %v, want %v", got, tc.wantKicks)
			}
			if tc.wantNoGet {
				if n := f.mtx.countRequests("POST ") + f.mtx.countRequests("GET /v3/srtconns/get/"); n != 0 {
					t.Errorf("kick and lookup requests after the list failed = %d, want 0", n)
				}
			}
		})
	}
}

// 미완의 까닭은 갈래마다 처음 하나씩 남는다 — Control API 실패는 401(api_auth 의 재료)과 그 밖으로, 확인 중에 끝난
// 부르는 쪽 ctx 와 모르는 꼴의 실패도 따로다. 앞의 실패가 뒤의 다른 까닭을 가리지 않는다 — kick 500 뒤에 응답기가 죽어
// 401 이 와도 cut 하위 명령이 api_auth 를 고를 수 있다(설계 3-4 3 의 운영 순서). 같은 갈래가 id 마다 되풀이돼도 하나만
// 남는다.
func TestCutKeepsEachKindOfFailure(t *testing.T) {
	a, b := testConnID(1), testConnID(2)
	// upper 는 기록을 손으로 고친 줄의 id 다 — MediaMTX 연결 id 꼴이 아니라 kick · 조회가 모르는 꼴의 실패를 낸다.
	upper := strings.ToUpper(testConnID(0xabc))
	tests := []struct {
		name      string
		setup     func(t *testing.T, f *cutFixture, cancel context.CancelFunc)
		want      CutReport
		wantKinds []string // cutFailureKinds
	}{
		{
			name: "kick_500_뒤_응답기가_죽어_401",
			setup: func(_ *testing.T, f *cutFixture, _ context.CancelFunc) {
				f.mtx.set(func(m *fakeMediaMTX) {
					m.failKick[a], m.failKick[b] = http.StatusInternalServerError, http.StatusUnauthorized
					m.fail["get"] = http.StatusUnauthorized
				})
			},
			want:      CutReport{FromLog: 2, Remaining: []string{a, b}, Covered: 10 * time.Second},
			wantKinds: []string{"kick status 401", "kick status 500"},
		},
		{
			name: "kick_500_뒤_확인_중_ctx_끝",
			setup: func(_ *testing.T, f *cutFixture, cancel context.CancelFunc) {
				f.mtx.set(func(m *fakeMediaMTX) {
					m.failKick[a] = http.StatusInternalServerError
					m.onGet = func(string) { cancel() }
				})
			},
			want:      CutReport{FromLog: 2, Kicked: 1, Remaining: []string{a, b}, Covered: 10 * time.Second},
			wantKinds: []string{"ctx canceled", "kick status 500"},
		},
		{
			name: "kick_500_과_모르는_꼴",
			setup: func(t *testing.T, f *cutFixture, _ context.CancelFunc) {
				f.mtx.set(func(m *fakeMediaMTX) { m.failKick[a] = http.StatusInternalServerError })
				appendRawConnLine(t, f, connLine{At: f.at(time.Second), Path: testTokenA, ID: upper})
			},
			want:      CutReport{FromLog: 3, Kicked: 1, Remaining: []string{a, upper}, Covered: 10 * time.Second},
			wantKinds: []string{"kick status 500", "unknown"},
		},
		{
			name: "네_갈래가_함께", // 어느 두 갈래를 한 칸으로 합쳐도 하나가 빠진다
			setup: func(t *testing.T, f *cutFixture, cancel context.CancelFunc) {
				f.mtx.set(func(m *fakeMediaMTX) {
					m.failKick[a], m.failKick[b] = http.StatusInternalServerError, http.StatusUnauthorized
					m.onGet = func(string) { cancel() }
				})
				appendRawConnLine(t, f, connLine{At: f.at(time.Second), Path: testTokenA, ID: upper})
			},
			want:      CutReport{FromLog: 3, Remaining: []string{a, b, upper}, Covered: 10 * time.Second},
			wantKinds: []string{"ctx canceled", "kick status 401", "kick status 500", "unknown"},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newCutFixture(t, 1<<20)
			f.mtx.add(fakeConn{id: a, created: f.at(500 * time.Millisecond)})
			f.mtx.add(fakeConn{id: b, created: f.at(500 * time.Millisecond)})
			f.clock.moveTo(time.Second)
			f.mustAllow(t, testTokenA, a)
			f.mustAllow(t, testTokenA, b)
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			tc.setup(t, f, cancel)
			f.clock.moveTo(10 * time.Second)

			rep, err := f.cutter().cut(ctx, testTokenA, NewDenyList(f.denyPath, discardLogger()), f.connDir, f.client)

			if got := cutFailureKinds(err); !slices.Equal(got, tc.wantKinds) {
				t.Errorf("Cut(X) with %s failed with %v, want %v", tc.name, got, tc.wantKinds)
			}
			checkReport(t, rep, tc.want)
		})
	}
}

// kick 의 404 는 Control API 의 에러 꼴(연결 없음)일 때만 「이미 없음」이다(설계 3-4 2.6). 판 올림으로 kick 경로가
// 사라지면 원본(gin)은 text/plain 404 를 낸다 — 그것을 「이미 없음」으로 세면 끊지 못한 송출이 남는데 nil(종료 0)이다.
// 그런 404 는 Control API 실패이고 그 id 는 확인한다. 에러 꼴 404 여도 그 판독의 목록에 있던 id 는 확인한다 — 목록에
// 없던 id(이미 닫힌 연결의 줄)는 목록과 같은 뜻이라 확인하지 않는다. 확인 조회가 오면 가짜가 부르는 쪽 ctx 를 끊어,
// 연결이 남은 채 확인을 끝낸다(실제로 기다리지 않는다).
func TestCutReadsOnlyConnNotFoundAsGone(t *testing.T) {
	live, closed := testConnID(1), testConnID(2)
	tests := []struct {
		name       string
		setup      func(m *fakeMediaMTX)
		closedLine bool // 기록에 이미 닫힌 연결(목록에 없음)의 줄을 더한다
		cancelGet  bool // 확인 조회가 오면 부르는 쪽 ctx 를 끊는다
		want       CutReport
		wantKinds  []string // cutFailureKinds(끊긴 ctx 의 둘째 목록은 Control API 칸이 비었을 때만 list timeout)
	}{
		{
			name:      "kick_경로_없음",
			setup:     func(m *fakeMediaMTX) { m.missing["kick"] = true },
			cancelGet: true,
			want:      CutReport{FromLog: 1, Remaining: []string{live}, Covered: 10 * time.Second},
			wantKinds: []string{"ctx canceled", "kick status 404"},
		},
		{
			name:      "kick_조회_경로_모두_없음",
			setup:     func(m *fakeMediaMTX) { m.missing["kick"], m.missing["get"] = true, true },
			want:      CutReport{FromLog: 1, Remaining: []string{live}, Covered: 10 * time.Second},
			wantKinds: []string{"kick status 404"},
		},
		{
			name:       "목록에_있던_id_의_에러_꼴_404",
			setup:      func(m *fakeMediaMTX) { m.failKick[live] = http.StatusNotFound },
			closedLine: true,
			cancelGet:  true,
			want:       CutReport{FromLog: 2, AlreadyGone: 2, Remaining: []string{live}, Covered: 10 * time.Second},
			wantKinds:  []string{"ctx canceled", "list timeout"},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newCutFixture(t, 1<<20)
			f.mtx.add(fakeConn{id: live, created: f.at(500 * time.Millisecond), state: statePublish, path: testTokenA})
			f.clock.moveTo(time.Second)
			f.mustAllow(t, testTokenA, live)
			if tc.closedLine {
				f.mustAllow(t, testTokenA, closed)
			}
			f.clock.moveTo(10 * time.Second)
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			f.mtx.set(func(m *fakeMediaMTX) {
				tc.setup(m)
				if tc.cancelGet {
					m.onGet = func(string) { cancel() }
				}
			})

			rep, err := f.cutter().cut(ctx, testTokenA, NewDenyList(f.denyPath, discardLogger()), f.connDir, f.client)

			if err == nil {
				t.Errorf("Cut(X) with %s = nil error, want an error (X's connection is still live)", tc.name)
			}
			if got := cutFailureKinds(err); !slices.Equal(got, tc.wantKinds) {
				t.Errorf("Cut(X) with %s failed with %v, want %v", tc.name, got, tc.wantKinds)
			}
			checkReport(t, rep, tc.want)
			if n := f.mtx.countRequests("GET /v3/srtconns/get/" + live); n < 1 {
				t.Errorf("lookups of the listed connection = %d, want at least 1", n)
			}
			if n := f.mtx.countRequests("GET /v3/srtconns/get/" + closed); tc.closedLine && n != 0 {
				t.Errorf("lookups of the closed connection = %d, want 0 (it was not on the list)", n)
			}
			if got, want := f.mtx.live(), []string{live}; !slices.Equal(got, want) {
				t.Errorf("live connections after Cut = %v, want %v", got, want)
			}
		})
	}
}

// 목록 본문을 해석하지 못하면(판 올림 — 모르는 꼴의 항목) 목록 없이 가를 수 있는 기록 갈래만 끊고 확인한 뒤 그 목록
// 에러로 멈춘다(종료 코드 1 · api_incompatible). 목록 갈래 · 기록 밖 갈래와 둘째 판독은 하지 않는다 — 목록을 믿을 수
// 없다. 401 · 전송 실패 · ctx 끝이면 kick 도 같은 까닭으로 실패하므로 아무것도 하지 않고 멈춘다(TestCutAPIFailures).
func TestCutUnparsableListStillCutsLogBranch(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	logged, pushed := testConnID(1), testConnID(2)
	f.mtx.add(fakeConn{id: logged, created: f.at(500 * time.Millisecond), state: statePublish, path: testTokenA})
	f.mtx.add(fakeConn{id: pushed, created: f.at(-100 * time.Second), state: statePublish, path: testTokenA})
	f.mtx.add(fakeConn{id: strings.ToUpper(testConnID(0xabc)), created: f.at(-100 * time.Second)}) // 해석되지 않는 항목
	f.clock.moveTo(time.Second)
	f.mustAllow(t, testTokenA, logged)
	f.clock.moveTo(10 * time.Second)

	rep, err := f.cut(t, testTokenA)

	if got, want := cutFailureKinds(err), []string{"list parse"}; !slices.Equal(got, want) {
		t.Errorf("Cut(X) with an unparsable list failed with %v, want %v", got, want)
	}
	checkReport(t, rep, CutReport{FromLog: 1, Kicked: 1})
	if got, want := f.mtx.kicked200(), []string{logged}; !slices.Equal(got, want) {
		t.Errorf("kicked %v, want %v (the log branch only)", got, want)
	}
	if n := f.mtx.countRequests("GET /v3/srtconns/get/" + logged); n < 1 {
		t.Errorf("lookups of the kicked connection = %d, want at least 1", n)
	}
	if n := f.mtx.countRequests("GET /v3/srtconns/list"); n != 1 {
		t.Errorf("list requests = %d, want 1 (no second read after the list failed)", n)
	}
}

// 확인 도중 부르는 쪽 ctx 가 확인 시한보다 먼저 끝나면, 남은 id 와 그 ctx 의 에러다 — 확인 시한(ErrVerifyTimeout)으로
// 바꿔 적지 않는다. cut 하위 명령이 자기 시한과 확인 시한을 가를 수 있게. ctx 는 가짜 MediaMTX 가 첫 조회를 받을 때
// 끊는다 — 확인 단계에 들어선 것이 보장되고 실제 시간에 기대지 않는다. 확인 상한은 1시간이라 그쪽이 먼저 끝나지 않는다.
func TestCutCallerCancelDuringVerify(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	stuck := testConnID(1)
	f.mtx.add(fakeConn{id: stuck, created: f.at(500 * time.Millisecond)})
	f.clock.moveTo(time.Second)
	f.mustAllow(t, testTokenA, stuck)
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	f.mtx.set(func(m *fakeMediaMTX) {
		m.sticky = true
		m.onGet = func(string) { cancel() }
	})
	c := f.cutter()
	c.verifyWithin = time.Hour

	rep, err := c.cut(ctx, testTokenA, NewDenyList(f.denyPath, discardLogger()), f.connDir, f.client)

	if !errors.Is(err, context.Canceled) || errors.Is(err, ErrVerifyTimeout) {
		t.Errorf("Cut(X) whose caller ctx ends during the verify failed with %v, want context.Canceled and not ErrVerifyTimeout", cutFailureKinds(err))
	}
	if !slices.Equal(rep.Remaining, []string{stuck}) {
		t.Errorf("Remaining = %v, want [%s]", rep.Remaining, stuck)
	}
}

// 확인 상한은 조회 사이의 기다림에서도 끝난다 — 조회가 연결이 남았다고 답한 뒤 다음 조회 때가 오기 전에 상한이 지나면 남은
// id 와 ErrVerifyTimeout 이다. 다음 조회 때(1시간)는 오지 않게 두어 조회 중이 아니라 기다림에서 끝나게 한다(상한 200ms —
// 첫 조회가 느린 러너에서도 상한 안에 끝날 만큼 둔다. 확인 상한은 실제 시계의 ctx 시한이라 그만큼은 실제로 기다린다).
func TestCutVerifyTimeoutBetweenLookups(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	stuck := testConnID(1)
	f.mtx.add(fakeConn{id: stuck, created: f.at(500 * time.Millisecond)})
	f.clock.moveTo(time.Second)
	f.mustAllow(t, testTokenA, stuck)
	f.mtx.set(func(m *fakeMediaMTX) { m.sticky = true })
	c := f.cutter()
	c.verifyWithin, c.verifyEvery = 200*time.Millisecond, time.Hour

	rep, err := c.cut(t.Context(), testTokenA, NewDenyList(f.denyPath, discardLogger()), f.connDir, f.client)

	if got, want := cutFailureKinds(err), []string{"verify timeout"}; !slices.Equal(got, want) {
		t.Errorf("Cut(X) whose kicked connection stays failed with %v, want %v", got, want)
	}
	checkReport(t, rep, CutReport{FromLog: 1, Kicked: 1, Remaining: []string{stuck}, Covered: time.Second})
	if n := f.mtx.countRequests("GET /v3/srtconns/get/" + stuck); n != 1 {
		t.Errorf("lookups of the stuck connection = %d, want 1 (the limit ends the wait for the next one)", n)
	}
}

// 공개 Cut 은 운영 설정으로 돈다 — 확인 상한 2초(설계 3-4 2.7 「최대 2초」). 시험의 고정물은 짧은 확인 상한을 쓰고, 공개
// Cut 을 부르는 시험은 연결이 곧바로 사라져 상한에 닿지 않으므로 운영 값은 이 시험이 본다. 길면 cut 하위 명령의 전체
// 시한까지 확인이 늘어 verify_timeout 대신 api_unreachable 로 끝난다.
func TestCutOperatingVerifyLimit(t *testing.T) {
	if got, want := operatingCutter().verifyWithin, 2*time.Second; got != want {
		t.Errorf("operatingCutter().verifyWithin = %v, want %v", got, want)
	}
}

// 확인(설계 3-4 2.7): kick 한 id 가 확인 시한 안에 목록에서 사라지지 않으면 남은 id(Remaining)와 ErrVerifyTimeout(종료
// 코드 1 verify_timeout)이다. 시한까지 되풀이해 조회하고, 시한을 크게 넘겨 기다리지 않는다.
func TestCutVerifyTimeout(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	stuck := testConnID(1)
	f.mtx.add(fakeConn{id: stuck, created: f.at(500 * time.Millisecond)})
	f.clock.moveTo(time.Second)
	f.mustAllow(t, testTokenA, stuck)
	f.mtx.set(func(m *fakeMediaMTX) { m.sticky = true })

	start := time.Now()
	rep, err := f.cut(t, testTokenA)
	took := time.Since(start)

	if !errors.Is(err, ErrVerifyTimeout) {
		t.Errorf("Cut(X) whose kicked connection stays = %v, want ErrVerifyTimeout", err)
	}
	checkReport(t, rep, CutReport{FromLog: 1, Kicked: 1, Remaining: []string{stuck}, Covered: time.Second})
	if took < cutVerifyWithinInTests || took > cutVerifyWithinInTests+2*time.Second {
		t.Errorf("Cut(X) took %v, want about the verify limit %v", took, cutVerifyWithinInTests)
	}
	if n := f.mtx.countRequests("GET /v3/srtconns/get/" + stuck); n < 2 {
		t.Errorf("lookups of the stuck connection = %d, want repeated lookups until the limit", n)
	}
}
