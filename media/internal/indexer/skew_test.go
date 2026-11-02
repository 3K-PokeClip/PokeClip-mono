package indexer

import (
	"log/slog"
	"reflect"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// G7 skew 기록(chain.go recordSkew — 계획 부기 30)이 재는 쌍은 「같은 회차의 인접 쌍 중 보정값이
// 그대로인 쌍」뿐이다. TestPDTMediaSkewRecordedOnlyForUnstitchedAdjacentPairs 는 리셋 이음 쌍과 회차
// 경계 쌍을 잰다. 이 파일은 남은 두 제외를 잰다 — 같은 녹화기 안 구멍을 이어 붙인 쌍, 그리고 사이에
// 비귀속 행이 낀 쌍.

// skewRecord 는 기록된 skew 한 줄이다.
type skewRecord struct {
	seq     int64
	seconds float64
}

// skewRecords 는 기록된 skew 를 기록 순서대로 모은다. 수준은 Debug 여야 한다(분포 신호 전례).
func (f *fixture) skewRecords() []skewRecord {
	f.t.Helper()
	var got []skewRecord
	for _, a := range f.logs.attrsAll("rewind_pdt_media_skew_seconds") {
		got = append(got, skewRecord{seq: a["seq"].(int64), seconds: a["seconds"].(float64)})
	}
	if n := f.logs.count(slog.LevelDebug, "rewind_pdt_media_skew_seconds"); n != len(got) {
		f.t.Errorf("skew 기록 %d건 중 Debug %d건 — 분포 신호는 Debug 다", len(got), n)
	}
	return got
}

// 같은 녹화기 안 구멍(mtxi 가 예상 끝보다 50ms 넘게 앞섬)을 이어 붙인 쌍은 재지 않는다 — 그 쌍의 PDT
// 차에는 압축한 유실 시간이 들어 있어 벽시계와 미디어 시계의 어긋남이 아니다. 이어 붙인 뒤의 인접
// 쌍부터는 다시 잰다.
//
// 잡는 결함: 제외 조건을 「녹화기 표식 변경·역행」(리셋)으로만 좁히는 변경 — 구멍 이음 쌍의 유실
// 2초가 skew 로 기록돼 G7 분포가 유실 크기로 오염된다.
func TestPDTMediaSkewSkipsGapStitchedPair(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1", "S1") // PDT = max(직전 PDT + 4초, 벽시계): 0 · 4 · 10 · 14초
	f.row(r, 0, mtxiOf(recA, 10*time.Second))
	f.row(r, 4*time.Second, mtxiOf(recA, 13_990*time.Millisecond))  // 미디어가 10ms 덜 갔다
	f.row(r, 10*time.Second, mtxiOf(recA, 20*time.Second))          // 같은 녹화기 안 2.01초 구멍 — 벽시계도 2초 늦게 왔다
	f.row(r, 14*time.Second, mtxiOf(recA, 24_020*time.Millisecond)) // 구멍 뒤 인접 쌍 — 미디어가 20ms 더 갔다

	// 픽스처 전제: seq 2 는 구멍 이음이다(보정값 = 13.99 + 4 − 20 = −2.01초).
	if got := f.playbackOf(2); got.StitchOffset != -2_010*time.Millisecond {
		t.Fatalf("seq 2 보정값 = %v, want -2.01s — 픽스처가 구멍 이음을 만들지 못했다", got.StitchOffset)
	}
	want := []skewRecord{
		{seq: 1, seconds: (10 * time.Millisecond).Seconds()},  // (4 − 0) − (13.99 − 10)
		{seq: 3, seconds: (-20 * time.Millisecond).Seconds()}, // (14 − 10) − (22.01 − 17.99)
	}
	if got := f.skewRecords(); !reflect.DeepEqual(got, want) {
		t.Errorf("skew 기록 = %v\nwant %v — 구멍 이음 쌍(seq 2)은 기록하지 않는다", got, want)
	}
}

// 사이에 비귀속 행이 낀 쌍은 보정값이 우연히 그대로여도 재지 않는다 — 그 쌍의 PDT 차에는 끼어든 행의
// 벽시계가 들어간다. 픽스처는 끼어든 행이 다른 녹화기의 파일이라 이 회차의 녹화기 타임라인이 끊기지
// 않은 경우다: 저장된 마지막 회차 행 끝(10 + 4 = 14초)에 이으면 보정값이 0 그대로다.
//
// 잡는 결함: 「비인접이면 어차피 보정값이 바뀐다」고 보고 인접 조건을 빼는 변경 — 끼어든 행의 벽시계
// 4초가 skew 로 기록된다.
func TestPDTMediaSkewSkipsPairAcrossUnattributedRowEvenWithSameOffset(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "", "S1", "S1") // 회차 행 PDT: 0 · 8 · 12초(비귀속 행은 PDT 재귀식 밖)
	s0 := f.row(r, 0, mtxiOf(recA, 10*time.Second))
	r.ends[s0.Path] = playback.Ends{Video: 4 * time.Second, Audio: 4 * time.Second}
	f.row(r, 4*time.Second, mtxiOf(recB, 0))                        // 비귀속 — 다른 녹화기의 파일
	f.row(r, 8*time.Second, mtxiOf(recA, 14*time.Second))           // 비인접 — 이음 보정값 = 14 − 14 = 0
	f.row(r, 12*time.Second, mtxiOf(recA, 18_010*time.Millisecond)) // 다시 인접 — 미디어가 10ms 더 갔다

	// 픽스처 전제: seq 2 는 비인접 이음인데 보정값이 그대로 0 이다.
	if got := f.playbackOf(2); got.PlaybackPos != 14*time.Second || got.StitchOffset != 0 {
		t.Fatalf("seq 2 도장 = pos %v · offset %v, want pos 14s · offset 0 — 픽스처 전제가 깨졌다",
			got.PlaybackPos, got.StitchOffset)
	}
	want := []skewRecord{
		{seq: 3, seconds: (-10 * time.Millisecond).Seconds()}, // (12 − 8) − (18.01 − 14)
	}
	if got := f.skewRecords(); !reflect.DeepEqual(got, want) {
		t.Errorf("skew 기록 = %v\nwant %v — 비귀속 행을 사이에 둔 쌍(seq 2)은 기록하지 않는다", got, want)
	}
}
