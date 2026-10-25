package upload

import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// ③ 조각 · init 의 재추출 결정성을 잰다(M5 설계 10절 R13 — PR ②b 머지 조건).
//
// 두 객체는 1년 immutable 머리로 올라간다(설계 4.5). 같은 키를 다시 올리는 재수거(스위퍼)가 첫 PUT 과 다른
// 바이트를 만들면 엣지 · 브라우저가 옛 바이트를 1일 · 1년 내는데, 정규 URL 은 영구 고정이라 키를 바꿔 피할 수도
// 없다. 그래서 같은 ② 녹화 조각 · 같은 회차 입력(보정값 표 · 고정 줄)이면 실시간 PUT 과 재수거 PUT 의 본문이
// 바이트까지 같아야 한다. 재포장은 기본 생산자(Go 재포장 — 운영과 같은 구현)이고 녹화 조각은 실물이다.
//
// 재기동 뒤의 ③ 재수거는 여기서 재지 않는다 — 표가 비어 도장을 합성하지 않고 거부한다(결정 B′ ·
// TestSweeperRefusesWithoutTableAfterReset). init 은 표와 무관해 새 업로더(재기동)에서도 잰다.

// ③ 실시간 PUT 과 같은 조각의 스위퍼 재수거 PUT 이 같은 바이트다. 도장 위치를 정하는 두 길(보정값 줄 ·
// 고정 줄)을 다 잰다 — 실시간은 실려 온 위치를, 스위퍼는 회차 표로 다시 만든 위치를 쓴다(playbackPos).
func TestPlaybackReextractionIsByteIdentical(t *testing.T) {
	cases := []struct {
		name string
		// live 는 인덱서가 보낸 실시간 요청이다 — 회차 표에 줄을 남기고, 그 위치로 첫 PUT 을 한다.
		live func(t *testing.T, path string, size int64) index.UploadTarget
	}{
		{
			name: "보정값 줄",
			live: func(t *testing.T, path string, size int64) index.UploadTarget {
				tgt := playbackTarget(t, "demo", 12, "S-1", path, size)
				tgt.StitchOffset = stitch
				tgt.PlaybackPos = fixture4sMtxi + stitch
				return tgt
			},
		},
		{
			name: "고정 줄",
			live: func(t *testing.T, path string, size int64) index.UploadTarget {
				tgt := playbackTarget(t, "demo", 12, "S-1", path, size)
				tgt.PosPinned = true
				tgt.PlaybackPos = 42 * time.Second
				return tgt
			},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			put := &fakePutter{}
			u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, put, nil, nil)
			path, size := copyFixture(t, dir, "demo", "seg12.mp4")
			live := tc.live(t, path, size)
			request(u, live) // 회차 표에 줄을 남긴다 — 접수된 작업은 버린다
			live.ExpectedInitSHA = mustHexBytes(t, goldenInitSHA)
			if got := runLive(u, live); got != outcomeSuccess {
				t.Fatalf("실시간 outcome = %v, want success (%s)", got, cap.dump())
			}

			sweep := playbackTarget(t, "demo", 12, "S-1", path, size)
			sweep.ExpectedInitSHA = mustHexBytes(t, goldenInitSHA)
			if got := runTarget(t, u, sweep); got != outcomeSuccess {
				t.Fatalf("재수거 outcome = %v, want success (%s)", got, cap.dump())
			}

			assertSameBodies(t, put, "dvr/demo/seg/000012.m4s")
		})
	}
}

// init 의 첫 PUT 과 재수거 PUT 이 같은 바이트다 — 같은 업로더의 스위퍼 재수거와, 재기동한 새 업로더의 재추출
// 둘 다다. init 바이트는 도장 위치와 무관하다(payloadFor).
func TestInitReextractionIsByteIdentical(t *testing.T) {
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, &fakeUploadStore{}, put, nil, nil)
	path, size := copyFixture(t, dir, "demo", "first.mp4")
	target := index.UploadTarget{StreamID: "demo", Axis: index.AxisInit, SessionID: "S-1", LocalPath: path, Bytes: size}

	if got := runLive(u, target); got != outcomeSuccess {
		t.Fatalf("첫 init outcome = %v, want success (%s)", got, cap.dump())
	}
	if got := runTarget(t, u, target); got != outcomeSuccess {
		t.Fatalf("재수거 init outcome = %v, want success (%s)", got, cap.dump())
	}
	restarted, cap2, dir2, _ := newPlaybackUploader(t, &fakeUploadStore{}, put, nil, nil)
	path2, size2 := copyFixture(t, dir2, "demo", "first.mp4")
	again := index.UploadTarget{StreamID: "demo", Axis: index.AxisInit, SessionID: "S-1", LocalPath: path2, Bytes: size2}
	if got := runTarget(t, restarted, again); got != outcomeSuccess {
		t.Fatalf("재기동 뒤 init outcome = %v, want success (%s)", got, cap2.dump())
	}

	assertSameBodies(t, put, "dvr/demo/init/S-1.mp4")
}

// assertSameBodies 는 put 이 받은 PUT 이 모두 key 에 갔고, 둘 이상이며, 본문이 비지 않고 서로 바이트까지 같은지
// 본다. 판정은 sha256 이다(R13 판별 신호).
func assertSameBodies(t *testing.T, put *fakePutter, key string) {
	t.Helper()
	calls := put.putCalls()
	if len(calls) < 2 {
		t.Fatalf("PUT = %d회, want 2회 이상(첫 PUT + 재추출)", len(calls))
	}
	first := sha256Hex(calls[0].body)
	for i, c := range calls {
		if c.key != key {
			t.Errorf("PUT[%d] 키 = %q, want %q", i, c.key, key)
		}
		if len(c.body) == 0 {
			t.Errorf("PUT[%d] 본문이 비었다", i)
		}
		if got := sha256Hex(c.body); got != first {
			t.Errorf("PUT[%d] sha256 = %s, want %s(첫 PUT) — 재추출이 다른 바이트를 만들었다", i, got, first)
		}
	}
}
