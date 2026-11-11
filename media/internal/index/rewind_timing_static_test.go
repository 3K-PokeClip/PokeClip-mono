package index

// 목록 읽기 질의의 PDT 칸(POK-196 PDT 생산자 c7a — 계획 c7a 시험 6 · 설계 r7 2.12 · 7.1). 되감기 적재(rewindRowsSQL)와
// 사슬 장부(chainRowsSQL)는 판(timing_version)만 읽고 원래 사실 칸(timing_wall_guess · timing_recorder_id 등)은 읽지
// 않는다 — 그 칸은 생산자 기록이라 뜻을 통지로 바꿀 수 있고(계약 B10), 목록이 읽기 시작하면 그 자유가 사라진다. PG 없이
// 문장 글자로 본다.

import (
	"regexp"
	"slices"
	"testing"
)

// timingColumn 은 문장 안의 PDT 칸 이름이다(stream_segments 의 timing_ 칸 일곱 — ddl.go).
var timingColumn = regexp.MustCompile(`\btiming_[a-z_]+`)

func TestListReadQueriesReadOnlyTimingVersion(t *testing.T) {
	for name, sql := range map[string]string{"rewindRowsSQL": rewindRowsSQL, "chainRowsSQL": chainRowsSQL} {
		got := timingColumn.FindAllString(sql, -1)
		slices.Sort(got)
		if got = slices.Compact(got); !slices.Equal(got, []string{"timing_version"}) {
			t.Errorf("%s 의 PDT 칸 = %q, want [timing_version] 하나뿐", name, got)
		}
	}
}
