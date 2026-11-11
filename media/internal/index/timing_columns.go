package index

// 원래 사실 칸(TimingFacts)과 장부 칸 다섯(timing_wall_guess · timing_recorder_id · timing_segment_number ·
// timing_video_first_ns · timing_video_end_ns — 설계 r7 2.3) 사이의 옮김이다. nil · 영값은 SQL NULL 이다.

import "time"

// factColumns 는 칸 다섯의 SQL 값이다(INSERT 인자 · LoadCursor 스캔 대상). 두 ns 칸은 time.Duration 의 ns 그대로다 —
// 저장값과 판정값 사이에 둘째 반올림 규칙이 생기지 않게(설계 2.3 「단위를 ns 로 두는 까닭」).
type factColumns struct {
	wall                 *time.Time
	recorder             *[16]byte
	number               *int64
	videoFirst, videoEnd *int64
}

// factColumnsOf 는 f 를 칸 값으로 옮긴다. w 는 ms 내림 값을 쓴다(설계 2.3 표) — 칸의 뜻을 쓰는 자리에서 한 번 못 박는다.
func factColumnsOf(f TimingFacts) factColumns {
	c := factColumns{recorder: f.Recorder, number: f.Number, videoFirst: nsOf(f.VideoFirst), videoEnd: nsOf(f.VideoEnd)}
	if !f.Wall.IsZero() {
		w := f.Wall.UTC().Truncate(time.Millisecond)
		c.wall = &w
	}
	return c
}

// toFacts 는 칸 값을 사실로 옮긴다. 출시 이미지가 쓴 행은 영값이다.
func (c factColumns) toFacts() TimingFacts {
	f := TimingFacts{Recorder: c.recorder, Number: c.number, VideoFirst: durationOf(c.videoFirst), VideoEnd: durationOf(c.videoEnd)}
	if c.wall != nil {
		f.Wall = c.wall.UTC()
	}
	return f
}

func nsOf(d *time.Duration) *int64 {
	if d == nil {
		return nil
	}
	ns := int64(*d)
	return &ns
}

func durationOf(ns *int64) *time.Duration {
	if ns == nil {
		return nil
	}
	d := time.Duration(*ns)
	return &d
}

// nullIfEmpty 는 빈 글자를 SQL NULL 로 보낸다 — 회차 없음(timing_round_id)이다.
func nullIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
