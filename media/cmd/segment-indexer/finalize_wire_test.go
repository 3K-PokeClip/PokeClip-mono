package main

// 정산 조립의 기동 로그(POK-196 M6-3b · 보안 사후 검수 LOW-2) — L3 · L2′ 스위치의 켬 여부가 기동 로그에 남는다.

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"
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
