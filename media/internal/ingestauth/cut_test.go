package ingestauth

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"slices"
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

// 부수 피해 없음(설계 2-4 729행 · 계획 9-2 B14): 기록에 있는 다른 토큰의 판독 중 연결은 생긴 지 120초여도 끊지 않는다
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

// 시작 표시 뒤 인증 대기(설계 2-4 733행 · 계획 9-2 B13): 기록에 시작 표시만 있고, 가짜 목록에 시작 표시 뒤에 생긴
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
			f.mtx.set(func(m *fakeMediaMTX) { m.fail["list"] = http.StatusNotFound })
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

// 확인 도중 부르는 쪽 ctx 가 확인 시한보다 먼저 끝나면, 남은 id 와 그 ctx 의 에러다 — 확인 시한(ErrVerifyTimeout)으로
// 바꿔 적지 않는다. cut 하위 명령이 자기 시한과 확인 시한을 가를 수 있게.
func TestCutCallerDeadlineDuringVerify(t *testing.T) {
	f := newCutFixture(t, 1<<20)
	stuck := testConnID(1)
	f.mtx.add(fakeConn{id: stuck, created: f.at(500 * time.Millisecond)})
	f.clock.moveTo(time.Second)
	f.mustAllow(t, testTokenA, stuck)
	f.mtx.set(func(m *fakeMediaMTX) { m.sticky = true })
	c := f.cutter()
	c.verifyWithin = cutVerifyWithin // 2초 — 부르는 쪽 시한(200ms)이 먼저 끝나게
	ctx, cancel := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer cancel()

	rep, err := c.cut(ctx, testTokenA, NewDenyList(f.denyPath, discardLogger()), f.connDir, f.client)

	if !errors.Is(err, context.DeadlineExceeded) || errors.Is(err, ErrVerifyTimeout) {
		t.Errorf("Cut(X) whose caller deadline ends during the verify = %v, want context.DeadlineExceeded and not ErrVerifyTimeout", err)
	}
	if !slices.Equal(rep.Remaining, []string{stuck}) {
		t.Errorf("Remaining = %v, want [%s]", rep.Remaining, stuck)
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
