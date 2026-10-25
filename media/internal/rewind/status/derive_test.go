package status_test

// Derive(4.1절 모드 매핑 — 위에서부터 처음 맞는 행이 이긴다)의 표 시험이다. 여덟 행 각각과 설계가 짚은 경계
// (R38 새 방송 시작 구간 · R49 행 7 · 「같은 회차」 · 강등 + 종료 항목)를 한 표에 둔다. 거짓은 404 다 — 503 은
// Derive 밖(Board · 처리기)에서만 정한다.

import (
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

func TestDeriveTable(t *testing.T) {
	published := &status.Range{URL: base + "/dvr/str/B/index.m3u8", First: first, Head: head}
	liveB := &status.OwnerSession{SessionID: "B", State: "live", InitUploaded: true}
	vodA := &status.Final{RoundID: "RA", LatestSessionID: "A",
		Status: status.Status{Ready: true, Mode: status.ModeVOD, URL: base + "/vod/a", FirstPDT: first, HeadPDT: head}}
	endedA := &status.Final{RoundID: "RA", LatestSessionID: "A", Status: status.Status{Mode: status.ModeEnded}}
	readyLive := status.Status{Ready: true, Mode: status.ModeLive, URL: published.URL, FirstPDT: first, HeadPDT: head}
	notReadyLive := status.Status{Mode: status.ModeLive}
	null := status.Status{}
	ending := status.Status{Mode: status.ModeEnding}

	for _, c := range []struct {
		name string
		in   status.Input
		want status.Status
		ok   bool
	}{
		{"행1_live_준비", status.Input{CacheKnown: true, HasView: true, Owner: liveB, Published: published}, readyLive, true},
		// R4: 발행 판이 없으면 url 이 아직 S3 에 없다 — ready 를 내지 않는다.
		{"행2_live_발행_판_없음", status.Input{CacheKnown: true, HasView: true, Owner: liveB}, notReadyLive, true},
		{"행2_live_init_미확정", status.Input{CacheKnown: true, HasView: true,
			Owner: &status.OwnerSession{SessionID: "B", State: "live"}, Published: published}, notReadyLive, true},
		// R11: 적재 중 · 컷오프 없음은 오류 경로가 아니라 200 미준비다.
		{"행3_적재_중", status.Input{CacheKnown: true, Loading: true}, null, true},
		{"행3_적재_중이_종료_항목보다_먼저", status.Input{CacheKnown: true, Loading: true, Final: vodA}, null, true},
		{"행4_컷오프_없는_뷰", status.Input{CacheKnown: true}, null, true},
		{"행4_컷오프_없음이_종료_항목보다_먼저", status.Input{CacheKnown: true, Final: vodA}, null, true},
		{"행5_종료_항목_소유_회차_없음", status.Input{CacheKnown: true, HasView: true, Final: vodA}, vodA.Status, true},
		{"행5_같은_회차_ended", status.Input{CacheKnown: true, HasView: true,
			Owner: &status.OwnerSession{SessionID: "A", State: "ended"}, Final: vodA}, vodA.Status, true},
		// 강등은 캐시가 없다 — 종료 항목만으로 답한다.
		{"행5_강등_캐시_없음", status.Input{Final: endedA}, endedA.Status, true},
		// R38: 새 방송 B 가 live 면 지난 회차 A 의 vod 를 내지 않는다(행 1 이 먼저).
		{"행1_새_방송이_지난_종료_항목보다_먼저", status.Input{CacheKnown: true, HasView: true, Owner: liveB,
			Published: published, Final: vodA}, readyLive, true},
		// 「같은 회차」 — 캐시가 다른 회차 B 를 들면 A 의 종료 항목을 내지 않는다.
		{"행6_다른_회차_ending", status.Input{CacheKnown: true, HasView: true,
			Owner: &status.OwnerSession{SessionID: "B", State: "ending"}, Final: vodA}, ending, true},
		{"행6_소유_회차_ended", status.Input{CacheKnown: true, HasView: true,
			Owner: &status.OwnerSession{SessionID: "B", State: "ended"}}, ending, true},
		// R49: 뷰는 있는데 소유 회차 · 종료 항목이 없으면 404 가 아니라 200 null 이다.
		{"행7_뷰만_있음", status.Input{CacheKnown: true, HasView: true}, null, true},
		{"행8_아무것도_없음", status.Input{}, null, false},
	} {
		got, ok := status.Derive(c.in)
		if got != c.want || ok != c.ok {
			t.Errorf("%s: Derive = (%+v, %v), want (%+v, %v)", c.name, got, ok, c.want, c.ok)
		}
	}
}
