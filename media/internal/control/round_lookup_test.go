package control

// 회차 조회 RoundLookup 구현(POK-196 M6-3b 커밋 ⑤ · 설계 M6 11절 종결 판 매핑표)의 시험이다. 국면 → 응답 변환은
// status.FromPlaybackState 하나이고(Drain 과 공용), 이 구현이 더하는 것은 읽기 오류 · 변환 오류를 Unavailable 로 내는
// 두 갈래와 변환 오류의 ERROR 한 줄뿐이다.

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

const lookupBase = "https://m.example"

var vodState = index.PlaybackState{Phase: index.PhaseVOD, RoundID: "R1", LatestSessionID: "S2", VODKey: "vod/s/R1/index.m3u8",
	FirstPDT: pdtFirst, HeadPDT: pdtHead}

// fakeReader 는 회차 읽기 자리다. 부른 횟수를 센다.
type fakeReader struct {
	calls atomic.Int64
	st    index.PlaybackState
	err   error
}

func (r *fakeReader) read(context.Context, string, string) (index.PlaybackState, error) {
	r.calls.Add(1)
	return r.st, r.err
}

// 11절 매핑표 전 행. 읽기 오류 · 변환 오류는 Unavailable 과 오류이고, 그 밖은 FromPlaybackState 가 내는 값 그대로다
// (같은 입력을 그 함수에 넣은 결과와 같다 — 사본 변환을 두면 표 대조가 FAIL). ERROR 줄은 변환 오류에만 있다 — 읽기 오류는
// 처리기가 control_round_lookup_failed 로 묶어 쓴다.
func TestRoundLookupMapsEachVerdictRow(t *testing.T) {
	readErr := errors.New("pool closed")
	for _, tt := range []struct {
		name       string
		st         index.PlaybackState
		err        error
		wantAnswer status.Answer
		wantErr    bool
		wantLogged string // 변환 오류의 reason
	}{
		{name: "읽기_오류", err: readErr, wantAnswer: status.Unavailable, wantErr: true},
		{name: "unknown", st: index.PlaybackState{Phase: index.PhaseUnknown}, wantAnswer: status.NotFound},
		{name: "live", st: index.PlaybackState{Phase: index.PhaseLive, RoundID: "R1", LatestSessionID: "S2"}, wantAnswer: status.Found},
		{name: "ending", st: index.PlaybackState{Phase: index.PhaseEnding, RoundID: "R1", LatestSessionID: "S2"}, wantAnswer: status.Found},
		{name: "ended", st: index.PlaybackState{Phase: index.PhaseEnded, RoundID: "R1", LatestSessionID: "S2"}, wantAnswer: status.Found},
		{name: "vod", st: vodState, wantAnswer: status.Found},
		{name: "빈_vod", st: index.PlaybackState{Phase: index.PhaseVOD, RoundID: "R1"}, wantAnswer: status.Unavailable, wantErr: true,
			wantLogged: status.ReasonVODIncomplete},
		{name: "영값", st: index.PlaybackState{}, wantAnswer: status.Unavailable, wantErr: true, wantLogged: status.ReasonOutOfRange},
	} {
		t.Run(tt.name, func(t *testing.T) {
			logs := &logSink{}
			r := &fakeReader{st: tt.st, err: tt.err}
			st, answer, err := newRoundLookup(r.read, lookupBase, logs.logger())(t.Context(), "s", "R1")
			if answer != tt.wantAnswer || (err != nil) != tt.wantErr {
				t.Fatalf("lookup = (%v, %v), want (%v, 오류 %v)", answer, err, tt.wantAnswer, tt.wantErr)
			}
			if tt.err != nil && !errors.Is(err, readErr) {
				t.Errorf("읽기 오류 = %v, want %v 를 감쌈", err, readErr)
			}
			if !tt.wantErr {
				want, wantAnswer, _ := status.FromPlaybackState(tt.st, lookupBase)
				if st != want || answer != wantAnswer {
					t.Errorf("Status = %+v, want FromPlaybackState 결과 %+v", st, want)
				}
			}
			lines := logs.events("control_round_lookup_invalid")
			switch {
			case tt.wantLogged == "" && len(lines) != 0:
				t.Errorf("control_round_lookup_invalid %v, want 없음", lines)
			case tt.wantLogged != "" && (len(lines) != 1 || lines[0]["level"] != "ERROR" || lines[0]["reason"] != tt.wantLogged ||
				lines[0]["stream_id"] != "s" || lines[0]["round_id"] != "R1"):
				t.Errorf("control_round_lookup_invalid %v, want ERROR 한 줄 reason=%s stream_id=s round_id=R1", lines, tt.wantLogged)
			}
			if r.calls.Load() != 1 {
				t.Errorf("읽기 %d번, want 1", r.calls.Load())
			}
		})
	}
}

// VOD url 은 REWIND_PUBLIC_BASE_URL + "/" + VODKey 다(응답 계약 ③ · 범위 = 그 회차 VOD 문서 범위).
func TestRoundLookupVODURLUsesBaseURL(t *testing.T) {
	r := &fakeReader{st: vodState}
	st, _, err := newRoundLookup(r.read, lookupBase, (&logSink{}).logger())(t.Context(), "s", "R1")
	if err != nil || st.URL != lookupBase+"/vod/s/R1/index.m3u8" || !st.Ready || st.Mode != status.ModeVOD ||
		!st.FirstPDT.Equal(pdtFirst) || !st.HeadPDT.Equal(pdtHead) {
		t.Errorf("vod = %+v(%v), want ready · url = base + / + 키 · 범위 그대로", st, err)
	}
}

// 처리기 갈래(경계 확정 4 ④): Found 200 · NotFound 404 · 읽기 오류 503 · 변환 오류 503. 503 은 캐시하지 않아 다음 요청이
// 다시 읽는다 — 변환 오류를 404 로 접으면 웹이 「다시보기 없음」으로 종국을 내린다.
func TestRoundLookupThroughRouteAnswers(t *testing.T) {
	for _, tt := range []struct {
		name     string
		r        *fakeReader
		wantCode int
		wantBody string
		reread   bool // 1초 안 두 번째 요청이 다시 읽는가(캐시 안 함)
	}{
		{name: "vod", r: &fakeReader{st: vodState}, wantCode: http.StatusOK, wantBody: `"mode":"vod"`},
		{name: "live", r: &fakeReader{st: index.PlaybackState{Phase: index.PhaseLive, RoundID: "R1"}}, wantCode: http.StatusOK,
			wantBody: `{"ready":false,"mode":"live","url":null,"firstPdt":null,"headPdt":null}`},
		{name: "unknown", r: &fakeReader{st: index.PlaybackState{Phase: index.PhaseUnknown}}, wantCode: http.StatusNotFound,
			wantBody: `"not_found"`},
		{name: "읽기_오류", r: &fakeReader{err: errors.New("boom")}, wantCode: http.StatusServiceUnavailable, wantBody: `"unavailable"`,
			reread: true},
		{name: "변환_오류", r: &fakeReader{st: index.PlaybackState{Phase: index.PhaseVOD, RoundID: "R1"}},
			wantCode: http.StatusServiceUnavailable, wantBody: `"unavailable"`, reread: true},
	} {
		t.Run(tt.name, func(t *testing.T) {
			f := newFixture(10)
			f.store(true, nil)
			base := f.start(t, RoundRoute(f.holder, f.opt, newRoundLookup(tt.r.read, lookupBase, f.opt.Log)))
			for range 2 {
				got := get(t, roundURL(base, "s", "R1"))
				if got.code != tt.wantCode || !strings.Contains(got.body, tt.wantBody) {
					t.Fatalf("회차 경로 = (%d, %s), want (%d, %s 포함)", got.code, got.body, tt.wantCode, tt.wantBody)
				}
			}
			want := int64(1)
			if tt.reread {
				want = 2
			}
			if n := tt.r.calls.Load(); n != want {
				t.Errorf("읽기 %d번, want %d(503 은 캐시 안 함 · 그 밖은 1초 캐시)", n, want)
			}
		})
	}
}
