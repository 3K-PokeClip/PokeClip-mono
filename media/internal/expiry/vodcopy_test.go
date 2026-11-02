package expiry

// 만료가 VOD 사본 키도 지운다(POK-525 ①-7 · 설계 r7 4-8 「60일 만료」 · E59 · M65). 사본 키는 장부 저장값(③ 키 ·
// init 키)에서 playback.VODPath 로 파생한다 — 그 행 · 그 세션의 사본만 지운다.

import (
	"fmt"
	"slices"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 조각 행마다 ③ 키의 사본을 같은 묶음에서 지우고, 세 키(② · ③ · 사본)가 다 지워진 행만 장부에서 지운다.
func TestSweepDeletesVODCopyOfEverySegmentRow(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("k", 2)
	r.clip.pages[""] = clipPage{items: []clipItem{item("k")}}
	r.sweep(t)

	want := []string{
		"k/a/0", "dvr/k/seg/000000.m4s", "vod/k/seg/000000.m4s",
		"k/a/1", "dvr/k/seg/000001.m4s", "vod/k/seg/000001.m4s",
	}
	if got := r.objects.deletedKeys(); !slices.Equal(got, want) {
		t.Fatalf("지운 키 %v, want %v", got, want)
	}
	if got := r.ledger.deletedSeqs("k"); !slices.Equal(got, []int64{0, 1}) {
		t.Fatalf("행 삭제 %v, want [0 1]", got)
	}
}

// ③ 키가 없는 행은 사본도 없다 — ② 하나만 지우고 행을 지운다. 앞자리 0 으로 늘린 ③ 키는 그대로 옮긴다(VODPath 는
// 자릿수를 정규화하지 않는다).
func TestSweepCopyKeyFollowsStoredPlaybackKey(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("k", 2)
	r.ledger.rows["k"][0].PlaybackS3Key = ""
	r.ledger.rows["k"][1].PlaybackS3Key = "dvr/k/seg/0000001.m4s"
	r.clip.pages[""] = clipPage{items: []clipItem{item("k")}}
	r.sweep(t)

	want := []string{"k/a/0", "k/a/1", "dvr/k/seg/0000001.m4s", "vod/k/seg/0000001.m4s"}
	if got := r.objects.deletedKeys(); !slices.Equal(got, want) {
		t.Fatalf("지운 키 %v, want %v", got, want)
	}
	if got := r.ledger.deletedSeqs("k"); !slices.Equal(got, []int64{0, 1}) {
		t.Fatalf("행 삭제 %v, want [0 1]", got)
	}
}

// init 문서 묶음에 각 init 키의 사본을 더한다. 걸음 순서(표시 → e1 → 행 → 문서 → 조각 → 행 삭제)와 문서 순서(dvr →
// vod → init)는 그대로이고, 문서가 조각보다 먼저다.
func TestSweepDeletesInitCopyInInitBatch(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("x", 1)
	r.ledger.docs["x"] = []index.ExpiryDocument{
		{Kind: index.ExpiryDocDVR, SessionID: "x-s", Key: "dvr/x"},
		{Kind: index.ExpiryDocVOD, SessionID: "x-s", Key: "vod/x"},
		{Kind: index.ExpiryDocInit, SessionID: "x-s", Key: "dvr/x/init/x-s.mp4"},
	}
	r.clip.pages[""] = clipPage{items: []clipItem{item("x")}}
	r.sweep(t)

	if got, want := r.ledger.callLog(), []string{"x:mark", "x:cutoff", "x:rows", "x:docs", "x:delete"}; !slices.Equal(got, want) {
		t.Fatalf("걸음 %v, want %v", got, want)
	}
	want := []string{
		"dvr/x", "vod/x", "dvr/x/init/x-s.mp4", "vod/x/init/x-s.mp4",
		"x/a/0", "dvr/x/seg/000000.m4s", "vod/x/seg/000000.m4s",
	}
	if got := r.objects.deletedKeys(); !slices.Equal(got, want) {
		t.Fatalf("지운 키 %v, want %v", got, want)
	}
	if got, want := r.objects.batches, []int{1, 1, 2, 3}; !slices.Equal(got, want) {
		t.Fatalf("요청 %v, want %v (종류마다 한 요청 · init 과 사본은 한 묶음)", got, want)
	}
}

// 사본 삭제가 실패하면 그 행은 장부에 남는다 — 지운 뒤에는 사본 키를 파생할 원천이 없다. 다른 행은 지우고, 방송은
// 재시도 집합에 들어간다.
func TestSweepKeepsRowWhenVODCopyDeleteFails(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("k", 3)
	r.objects.fail = func(k string) bool { return k == "vod/k/seg/000001.m4s" }
	r.clip.pages[""] = clipPage{items: []clipItem{item("k")}}
	r.sweep(t)

	if got := r.ledger.deletedSeqs("k"); !slices.Equal(got, []int64{0, 2}) {
		t.Fatalf("행 삭제 %v, want [0 2] (seq 1 의 사본 키 실패)", got)
	}
	if fails := r.log.all("expiry_delete_failed"); len(fails) != 1 || fails[0].attrs["stage"] != "segments" {
		t.Fatalf("expiry_delete_failed %v, want stage=segments 한 줄", fails)
	}
	if _, ok := r.e.retry["S-k"]; !ok {
		t.Fatal("재시도 집합에 없다")
	}
}

// init 사본 삭제가 실패하면 init 묶음 실패다 — 앞 종류 실패와 같이 조각 · 행 단계로 가지 않는다.
func TestSweepInitCopyDeleteFailureStopsBeforeSegments(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("x", 1)
	r.ledger.docs["x"] = []index.ExpiryDocument{{Kind: index.ExpiryDocInit, SessionID: "x-s", Key: "dvr/x/init/x-s.mp4"}}
	r.objects.fail = func(k string) bool { return k == "vod/x/init/x-s.mp4" }
	r.clip.pages[""] = clipPage{items: []clipItem{item("x")}}
	r.sweep(t)

	if got, want := r.objects.deletedKeys(), []string{"dvr/x/init/x-s.mp4"}; !slices.Equal(got, want) {
		t.Fatalf("지운 키 %v, want %v (조각 키는 지우지 않는다)", got, want)
	}
	if got := r.ledger.deletedSeqs("x"); len(got) != 0 {
		t.Fatalf("행 삭제 %v, want 없음", got)
	}
	if fails := r.log.all("expiry_delete_failed"); len(fails) != 1 || fails[0].attrs["stage"] != "documents" {
		t.Fatalf("expiry_delete_failed %v, want stage=documents 한 줄", fails)
	}
}

// ③ 키가 그 행의 조각 키가 아니면 사본 키를 파생하지 않는다. 그 행은 객체도 행도 건드리지 않고 expiry_skipped
// (reason=vod_path_invalid)로 알린다 — 다른 행 · 다른 스트림의 사본을 지우지 않고, 행을 지우면 키를 다시 알 길이 없다.
// 다른 행은 지운다. 다시 해도 같은 판정이라 재시도 집합에 넣지 않는다.
func TestSweepSkipsRowWhosePlaybackKeyIsNotItsSegment(t *testing.T) {
	for _, bad := range []string{
		"k/p/1",                    // ③ 키 꼴이 아니다
		"dvr/k/init/k-s.mp4",       // init 꼴
		"dvr/other/seg/000001.m4s", // 다른 스트림
		"dvr/k/seg/000009.m4s",     // 다른 seq
		"vod/k/seg/000001.m4s",     // 이미 사본 꼴
		"dvr/k/seg/1.m4s",          // seq 6자리 미만
		"dvr/../seg/000001.m4s",    // 상위 경로 성분
	} {
		t.Run(bad, func(t *testing.T) {
			r := newRig(t, nil)
			r.ledger.put("k", 3)
			r.ledger.rows["k"][1].PlaybackS3Key = bad
			r.clip.pages[""] = clipPage{items: []clipItem{item("k")}}
			r.sweep(t)

			if got := r.ledger.deletedSeqs("k"); !slices.Equal(got, []int64{0, 2}) {
				t.Fatalf("행 삭제 %v, want [0 2]", got)
			}
			for _, k := range r.objects.deletedKeys() {
				if k == "k/a/1" || k == bad || k == "vod/"+strings.TrimPrefix(bad, "dvr/") {
					t.Fatalf("건너뛸 행의 키 %q 를 지웠다 (지운 키 %v)", k, r.objects.deletedKeys())
				}
			}
			skips := r.log.all("expiry_skipped")
			if len(skips) != 1 || skips[0].attrs["reason"] != "vod_path_invalid" || skips[0].attrs["rows"] != "1" {
				t.Fatalf("expiry_skipped %v, want reason=vod_path_invalid rows=1 한 줄", skips)
			}
			if fails := r.log.all("expiry_delete_failed"); len(fails) != 0 {
				t.Fatalf("expiry_delete_failed %v, want 없음", fails)
			}
			if len(r.e.retry) != 0 {
				t.Fatal("재시도 집합에 들어갔다")
			}
		})
	}
}

// init 키가 그 세션의 init 키가 아니면 문서 단계를 아무것도 지우기 전에 멈춘다. init 을 건너뛰고 조각을 지우면 조각
// 0 이 된 세션 행이 지워져 init · 사본 키를 다시 알 길이 없다. expiry_delete_failed stage=documents 로 알리고
// 재시도한다.
func TestSweepStopsWhenInitKeyIsNotItsSession(t *testing.T) {
	for _, bad := range []string{
		"init/x",               // init 키 꼴이 아니다
		"dvr/x/seg/000000.m4s", // 조각 꼴
		"dvr/y/init/x-s.mp4",   // 다른 스트림
		"dvr/x/init/other.mp4", // 다른 세션
	} {
		t.Run(bad, func(t *testing.T) {
			r := newRig(t, nil)
			r.ledger.put("x", 1)
			r.ledger.docs["x"] = []index.ExpiryDocument{
				{Kind: index.ExpiryDocDVR, SessionID: "x-s", Key: "dvr/x"},
				{Kind: index.ExpiryDocInit, SessionID: "x-s", Key: bad},
			}
			r.clip.pages[""] = clipPage{items: []clipItem{item("x")}}
			r.sweep(t)

			if got := r.objects.deletedKeys(); len(got) != 0 {
				t.Fatalf("지운 키 %v, want 없음", got)
			}
			if got := r.ledger.deletedSeqs("x"); len(got) != 0 {
				t.Fatalf("행 삭제 %v, want 없음", got)
			}
			if fails := r.log.all("expiry_delete_failed"); len(fails) != 1 || fails[0].attrs["stage"] != "documents" {
				t.Fatalf("expiry_delete_failed %v, want stage=documents 한 줄", fails)
			}
			if _, ok := r.e.retry["S-x"]; !ok {
				t.Fatal("재시도 집합에 없다")
			}
		})
	}
}

// 묶음 상한 — 사본 키를 더해도 한 요청은 objectBatch 를 넘지 않는다. init 600개(키 1200) · 행 400개(키 1200).
func TestSweepCopyKeysStayWithinObjectBatch(t *testing.T) {
	r := newRig(t, nil)
	r.ledger.put("x", 400)
	for i := range 600 {
		session := fmt.Sprintf("x-s%03d", i)
		r.ledger.docs["x"] = append(r.ledger.docs["x"],
			index.ExpiryDocument{Kind: index.ExpiryDocInit, SessionID: session, Key: "dvr/x/init/" + session + ".mp4"})
	}
	r.clip.pages[""] = clipPage{items: []clipItem{item("x")}}
	r.sweep(t)

	if got, want := r.objects.batches, []int{objectBatch, 200, objectBatch, 200}; !slices.Equal(got, want) {
		t.Fatalf("요청 %v, want %v", got, want)
	}
	if got := len(r.ledger.deletedSeqs("x")); got != 400 {
		t.Fatalf("행 삭제 %d, want 400", got)
	}
}
