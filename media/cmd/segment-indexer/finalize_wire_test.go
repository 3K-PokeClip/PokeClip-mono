package main

// 정산 조립의 기동 로그(POK-196 M6-3b · 보안 사후 검수 LOW-2) — L3 · L2′ 스위치의 켬 여부가 기동 로그에 남는다.

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// 두 스위치(REWIND_GAP_RUN_END · REWIND_STALL_SEAL)의 값을 INFO rewind_end_switches 한 줄로 낸다 — 운영자가 켬 여부를
// 기동 로그로 확인한다. 변이 = 한쪽 칸을 빼거나 값을 고정 → FAIL.
func TestLogRewindEndSwitchesReportsBothSwitches(t *testing.T) {
	for _, tc := range []struct {
		gapRun, stallSeal bool
		want              string
	}{
		{false, false, "gap_run_end=false stall_seal=false"},
		{true, false, "gap_run_end=true stall_seal=false"},
		{false, true, "gap_run_end=false stall_seal=true"},
	} {
		var buf bytes.Buffer
		logRewindEndSwitches(slog.New(slog.NewTextHandler(&buf, nil)), tc.gapRun, tc.stallSeal)
		got := buf.String()
		if !strings.Contains(got, "level=INFO") || !strings.Contains(got, "msg=rewind_end_switches") ||
			!strings.Contains(got, tc.want) {
			t.Errorf("(%t, %t) 기동 줄 = %q, want INFO rewind_end_switches · %s", tc.gapRun, tc.stallSeal, got, tc.want)
		}
	}
}

// 정산 차선 키(scan:finalize · final: · playback:)는 스트림 차선 키와 겹치지 않는다 — 셋 다 「:」 를 담고 stream_id
// 화이트리스트는 「:」 를 받지 않는다(보안 LOW-1). 변이 = 앞머리에서 「:」 를 뺌 · 화이트리스트에 「:」 를 더함 → FAIL.
func TestFinalizeLaneKeysCannotBeStreamIDs(t *testing.T) {
	for _, k := range []string{scanLaneKey, finalLanePrefix + "S-1", playbackLanePrefix + "demo"} {
		if !strings.Contains(k, ":") || recording.ValidStreamID(k) {
			t.Errorf("차선 키 %q 가 stream_id 로 읽힐 수 있다(「:」 있음 %t · 화이트리스트 통과 %t)",
				k, strings.Contains(k, ":"), recording.ValidStreamID(k))
		}
	}
}

// 조립은 설정의 상태 읽기 상한(REWIND_PLAYBACK_READ_MAX)을 정산 몫에 넘긴다. 변이 = 넘기지 않음(0 — 읽기가 하나도 안 나감)
// 또는 상수 고정 → FAIL.
func TestFinalizeWirePassesPlaybackReadMax(t *testing.T) {
	r := &rewindLoop{}
	cfg := config.Config{RewindPlaybackReadMax: 3}
	newFinalizeAssembly(cfg, slog.Default()).wire(r, cfg, nil, session.NewPlaybackInbox(), session.NewIntentInbox())
	if r.fin.readMax != 3 {
		t.Fatalf("readMax = %d, want 3(설정값)", r.fin.readMax)
	}
}
