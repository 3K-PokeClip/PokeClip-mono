package upload

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 스위퍼 회차의 축 3벌(계획 2.1 sweep.go 행 · 설계 5.5.4 #2·#7·#8 · 5.5.6 T12)을 잰다.
//
// ② 회차 계약(커서·정체·요약 — B-1·R3·M-2·H-1·L-4·G16⁗)은 sweep_test 가 ② 축에서 그대로 잰다.
// 이 파일은 세 축이 **각자의 조회·커서·잔량으로 한 회차에 도는가**와 **스위퍼가 집은 ③·init 작업이
// 워커의 축 본문을 지나 장부 확정까지 가는가**를 본다. 조회의 자격 술어(컷오프·ended)는 장부
// 쪽이라 실 PG 로 재는 sweep_pg_test 가 맡는다.

// pagesByAxis 는 축마다 한 페이지를 내는 조회 훅이다. 영값 커서(1단계)에는 그 축의 대상을 전부
// 주고 이어 보는 커서에는 빈 페이지(끝)를 준다. 돌려주는 커서는 그 축의 마지막 대상을 가리킨다.
func pagesByAxis(pages map[index.Axis][]index.UploadTarget) func(context.Context, index.Axis, float64, int, index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
	return func(_ context.Context, a index.Axis, _ float64, _ int, after index.SweepCursor) ([]index.UploadTarget, index.SweepCursor, error) {
		rows := pages[a]
		if !after.IsZero() || len(rows) == 0 {
			return nil, after, nil
		}
		last := rows[len(rows)-1]
		return rows, index.SweepCursor{StartWall: fixtureWall, StreamID: last.StreamID, Seq: last.Seq, SessionID: last.SessionID}, nil
	}
}

// initSweepTarget 은 스위퍼 init 조회가 만드는 모양의 작업이다 — 키를 비워 오고(워커가
// playback.InitKey 로 파생) 입력은 세션 최신 조각이며 꼬리 예외가 없다(IsTail=false).
func initSweepTarget(sessionID, path string, size int64) index.UploadTarget {
	return index.UploadTarget{StreamID: "demo", Axis: index.AxisInit, SessionID: sessionID, LocalPath: path, Bytes: size}
}

// uploadedFrom 은 segment_uploaded 로그에서 (축, 출처)가 맞는 것의 seq 를 모은다 — 작업이 스위퍼에서
// 왔는지(OriginSweep) 보류 재요청으로 다시 들었는지(OriginLive)를 가른다.
func uploadedFrom(cap *logCapture, a index.Axis, o Origin) []int64 {
	var seqs []int64
	for _, rec := range ofAxis(cap.find("segment_uploaded"), a) {
		if rec.attrs["origin"] == o.String() {
			seqs = append(seqs, rec.attrs["seq"].(int64))
		}
	}
	return seqs
}

// T12 — 한 회차가 ②·③·init 세 축의 실패분을 모두 다시 넣고, 워커가 축마다 제 장부를 확정한다
// (설계 5.5.6 · 계약 5-5 재수거 규범 1항). ③ 은 스위퍼 작업이라 도장 위치를 회차 표로 다시 만든다(계획 4.2-R
// R3 「스위퍼 재생성」). 잡는 결함: 회차가 한 축이라도 빠뜨리면 그 축의 failed 는 종국 상태가 되어
// 영원히 안 올라간다 — ③ 이면 되감기 접두가, init 이면 그 세션의 ③ 전부가 선다.
func TestSweepRoundRecoversEveryAxis(t *testing.T) {
	st := &fakeUploadStore{}
	put := &fakePutter{}
	prod := &fakeProducer{}
	u, cap, dir, _ := newPlaybackUploader(t, st, put, prod, nil)
	request(u, liveRequest(t, 10, "S-1", 0))
	request(u, liveRequest(t, 12, "S-1", stitch)) // 12 부터 이어 붙인 회차

	archive := newTarget("demo", 9, writeSegment(t, dir, "demo", "seg9.mp4", 64), 64, false)
	playback := sweepTarget(t, dir, 13, "S-1") // 세션 init 이 확정돼 조회가 해시를 싣는다
	initJob := initSweepTarget("S-2", writeSegment(t, dir, "demo", "first-S-2.mp4", 64), 64)
	st.onPending = pagesByAxis(map[index.Axis][]index.UploadTarget{
		index.AxisArchive:  {archive},
		index.AxisPlayback: {playback},
		index.AxisInit:     {initJob},
	})

	u.sweepOnce(context.Background(), nil)
	runQueue(u)

	if uploaded, _ := st.markCalls(); len(uploaded) != 1 || uploaded[0] != (markCall{"demo", 9, 64}) {
		t.Errorf("② MarkUploaded = %v, want 1회 {demo 9 64}", uploaded)
	}
	if marked, _ := st.playbackCalls(); len(marked) != 1 || marked[0].seq != 13 {
		t.Errorf("③ MarkPlaybackUploaded = %+v, want seq 13 1회", marked)
	}
	// 재포장은 ③ 조각(스위퍼 — 표로 정한 도장)과 init(도장 무관) 두 번이다.
	if calls := prod.produced(); len(calls) != 2 || calls[0].pos != fixture4sMtxi+stitch {
		t.Errorf("재포장 = %+v, want 2회 · 첫 번(③ seq 13) pos = mtxi + 5s = %v", calls, fixture4sMtxi+stitch)
	}
	want := initMarkCall{sessionID: "S-2", sha256: fakeInitSHA(), s3Key: "dvr/demo/init/S-2.mp4", bytes: int64(len(fakeInit))}
	if got := st.initCalls(); len(got) != 1 || !sameInitCall(got[0], want) {
		t.Errorf("MarkInitUploaded = %+v, want 1회 %+v", got, want)
	}
	for _, a := range []index.Axis{index.AxisArchive, index.AxisPlayback, index.AxisInit} {
		if got := uploadedFrom(cap, a, OriginSweep); len(got) != 1 {
			t.Errorf("%s 축 segment_uploaded(origin=sweep) = %v, want 1건 (%s)", a, got, cap.dump())
		}
	}
}

// ⑦-1(계획 r22→r23 처분표 — 커밋 4 통합 테스트 필수): 첫 init 작업이 확정 없이 끝나도 세션의
// init_uploaded_at 은 NULL 로 남아 스위퍼 init 벌이 그 세션을 다시 집는다. 세션 최신 조각에서 init 을
// 다시 만들어 올리고 CAS 가 확정하면 sessionInit 이 채워지고, 그 회차에서 대조를 기다리던 실시간 ③
// 이 그 자리에서 다시 들어 올라간다. 잡는 결함: 스위퍼가 init 축을 안 돌면 그 회차의 ③ 은 끝내
// 대조 보류에 갇힌다(실시간 init 재요청은 접수된 뒤의 실패를 모른다).
//
// 첫 init 이 끝나는 원인은 둘로 잰다 — 접수 거부(큐 포화: RequestUpload=false)와 init CAS 의 DB
// 오류(mark_error — 키에 백오프가 걸린다). 꼬리 성장으로 멈추는 갈래는 없다: init 은 머리말만의 순수
// 함수라 워커가 크기 불일치를 경고로만 남긴다(init_tail_test).
func TestSweepInitAxisConfirmsSessionAndReleasesHeldPlayback(t *testing.T) {
	for _, c := range []struct {
		name string
		// firstInit 은 실시간 init 요청이 확정 없이 끝나게 만든다.
		firstInit func(t *testing.T, u *Uploader, st *fakeUploadStore, clock *fakeClock, initJob index.UploadTarget)
		initCAS   int // MarkInitUploaded 호출 수(확정까지)
	}{
		{"큐_포화로_접수_거부", func(t *testing.T, u *Uploader, _ *fakeUploadStore, _ *fakeClock, initJob index.UploadTarget) {
			for i := int64(0); i < 2; i++ { // QueueLen 2 를 채운다
				u.RequestUpload(newTarget("filler", i, "/recordings/filler/x.mp4", 1, false))
			}
			if u.RequestUpload(initJob) {
				t.Fatal("큐가 찼는데 init 요청이 접수됐다")
			}
			u.drainQueue()
		}, 1},
		{"init_CAS_DB_오류", func(t *testing.T, u *Uploader, st *fakeUploadStore, clock *fakeClock, initJob index.UploadTarget) {
			calls := 0
			st.onInitMark = func(string, []byte) (index.InitMark, error) {
				calls++
				if calls == 1 {
					return 0, errors.New("연결 끊김")
				}
				return index.InitMarkSuccess, nil
			}
			if !u.RequestUpload(initJob) {
				t.Fatal("init 요청이 접수되지 않았다")
			}
			runQueue(u)
			clock.advance(3 * time.Hour) // 실패가 건 키 백오프(SweepEvery 1h × 2)를 넘긴다
		}, 2},
	} {
		t.Run(c.name, func(t *testing.T) {
			st := &fakeUploadStore{}
			put := &fakePutter{}
			u, cap, dir, clock := newPlaybackUploader(t, st, put, &fakeProducer{}, func(o *Options) { o.QueueLen = 2 })
			first := writeSegment(t, dir, "demo", "first.mp4", 64)
			liveInit := index.UploadTarget{StreamID: "demo", Axis: index.AxisInit, SessionID: "S-1", LocalPath: first, Bytes: 64, IsTail: true}
			c.firstInit(t, u, st, clock, liveInit)

			// 실시간 ③ — 기대 init 을 아직 몰라 보류 목록에 든다(도장 위치를 실은 채).
			seg := sweepTarget(t, dir, 7, "S-1")
			seg.ExpectedInitSHA = nil
			seg.PlaybackPos = fixture4sMtxi
			if !u.RequestUpload(seg) {
				t.Fatal("③ 요청이 접수되지 않았다")
			}
			runQueue(u)
			if marked, _ := st.playbackCalls(); len(marked) != 0 {
				t.Fatalf("init 확정 전인데 ③ 이 올라갔다: %+v", marked)
			}

			// 스위퍼 회차 — 장부는 그 세션을 init 미확정으로, ③ 조각을 pending 으로 보고한다.
			pending := sweepTarget(t, dir, 7, "S-1")
			pending.ExpectedInitSHA = nil // init_sha256 이 아직 NULL 이다
			st.onPending = pagesByAxis(map[index.Axis][]index.UploadTarget{
				index.AxisPlayback: {pending},
				index.AxisInit:     {initSweepTarget("S-1", first, 64)},
			})
			u.sweepOnce(context.Background(), nil)
			runQueue(u)

			got := st.initCalls()
			want := initMarkCall{sessionID: "S-1", sha256: fakeInitSHA(), s3Key: "dvr/demo/init/S-1.mp4", bytes: int64(len(fakeInit))}
			if len(got) != c.initCAS || !sameInitCall(got[len(got)-1], want) {
				t.Fatalf("MarkInitUploaded = %+v, want %d회 · 마지막 %+v (%s)", got, c.initCAS, want, cap.dump())
			}
			if seqs := uploadedFrom(cap, index.AxisInit, OriginSweep); len(seqs) != 1 {
				t.Errorf("init 확정 = %v, want 스위퍼 작업 1건", seqs)
			}
			if marked, _ := st.playbackCalls(); len(marked) != 1 || marked[0].seq != 7 {
				t.Errorf("③ MarkPlaybackUploaded = %+v, want seq 7 1회 — init 확정이 보류를 풀어야 한다", marked)
			}
			if seqs := uploadedFrom(cap, index.AxisPlayback, OriginLive); len(seqs) != 1 || seqs[0] != 7 {
				t.Errorf("③ 확정 출처 = %v, want 보류 재요청(live) seq 7", seqs)
			}
		})
	}
}

// axisRows 는 한 축의 조회 행 n 개다(스위퍼만 도는 업로더용 — 워커가 없어 파일·키가 필요 없다).
// 스트림 이름에 축을 넣어 커서가 어느 축의 것인지 값만으로 드러나게 하고, init 행은 설계 5.5.2
// 대로 seq 를 쓰지 않고 세션으로 끝난다.
func axisRows(a index.Axis, n int) []pageRow {
	base := time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC)
	rows := make([]pageRow, 0, n)
	for i := 0; i < n; i++ {
		sid := fmt.Sprintf("%s-%d", a, i)
		tgt := index.UploadTarget{StreamID: sid, Axis: a, Seq: int64(i), Bytes: 1}
		cur := index.SweepCursor{StartWall: base.Add(time.Duration(i) * time.Second), StreamID: sid, Seq: int64(i)}
		if a == index.AxisInit {
			tgt.Seq, cur.Seq = 0, 0
			tgt.SessionID = fmt.Sprintf("S-%d", i)
			cur.SessionID = tgt.SessionID
		}
		rows = append(rows, pageRow{target: tgt, cursor: cur})
	}
	return rows
}

// 세 축은 제 커서를 따로 든다 — 다음 회차에 각 축의 이어 보기 조회는 **그 축**이 지난 회차에 멈춘
// 자리에서 시작한다(init 은 세션으로 끝나는 커서 그대로). 잡는 결함: 커서를 한 벌로 두거나 축을
// 엇갈려 넘기면 ③ 이 ② 의 자리부터 훑어 남은 행을 통째로 건너뛰고, init 은 정렬 키가 다른 커서를
// 받아 엉뚱한 자리부터 세션을 훑는다.
func TestSweepAxesAdvanceTheirOwnCursors(t *testing.T) {
	st := &pageStore{}
	axes := []index.Axis{index.AxisArchive, index.AxisPlayback, index.AxisInit}
	rows := map[index.Axis][]pageRow{}
	for _, a := range axes {
		rows[a] = axisRows(a, 6)
		st.rows = append(st.rows, rows[a]...)
	}
	u, cap := newSweepUploader(t, st, nil) // SweepLimit 4 · 워커 없음(접수된 작업은 in-flight 로 남는다)

	first := u.sweepOnce(context.Background(), nil)
	second := u.sweepOnce(context.Background(), first)

	for _, a := range axes {
		if got, want := first[a].resume, rows[a][3].cursor; got != want {
			t.Errorf("1회차 %s 커서 = %+v, want %+v (그 축 4번째 행)", a, got, want)
		}
		queried := st.queryCursors(a)
		if len(queried) != 3 || queried[2] != rows[a][3].cursor {
			t.Errorf("%s 축 조회 커서 = %+v, want [영값 영값 %+v] — 2회차 이어 보기는 그 축 커서에서 시작한다", a, queried, rows[a][3].cursor)
		}
		if got, want := second[a].resume, rows[a][5].cursor; got != want {
			t.Errorf("2회차 %s 커서 = %+v, want %+v (그 축 마지막 행)", a, got, want)
		}
	}
	if n := len(u.queue); n != 18 {
		t.Errorf("큐 = %d건, want 18건 — 세 축 6행씩 한 번씩 (%s)", n, cap.dump())
	}
}

// ② 가 회차의 큐를 먼저 잡는다(sweepAxes 순서의 이유 — M3 의 ② 회차 계약 G16⁗ 보존). 뒤 축은 남은
// 자리만 쓰고, 큐가 차서 못 든 축은 **그 축만** 정체로 센다. 잡는 결함: ③ 이 앞서면 ③ 적체가 ② 클립
// 소재의 회차를 굶기고, 정체를 한 벌로 세면 한 축의 포화가 다른 축의 정상 회차 기록을 덮는다.
func TestSweepArchiveAxisTakesTheQueueFirst(t *testing.T) {
	st := &pageStore{}
	archive := axisRows(index.AxisArchive, 2)
	playback := axisRows(index.AxisPlayback, 2)
	st.rows = append(append(st.rows, archive...), playback...)
	u, _ := newSweepUploader(t, st, func(o *Options) { o.QueueLen = 2 })

	first := u.sweepOnce(context.Background(), nil)

	for i := 0; i < 2; i++ {
		j := <-u.queue
		u.gate.releaseInflight(j.key())
		if j.target.Axis != index.AxisArchive {
			t.Errorf("큐 %d번째 = %s 축, want archive — ② 가 먼저 자리를 잡는다", i, j.target.Axis)
		}
	}
	if got := first[index.AxisPlayback]; got.stalled != 1 || !got.resume.IsZero() {
		t.Errorf("③ 진행 = %+v, want 정체 1 · 커서 불변(영값) — 큐가 차서 한 행도 못 들었다", got)
	}
	if got := first[index.AxisArchive].stalled; got != 0 {
		t.Errorf("② 정체 = %d, want 0 — ② 는 제 몫을 다 접수했다", got)
	}

	// 워커가 따라잡았고 ② 는 다 올랐다 — 이제 ③ 이 자리를 얻는다.
	for _, r := range archive {
		u.gate.quarantine(archiveKey(r.target.StreamID, r.target.Seq))
	}
	second := u.sweepOnce(context.Background(), first)

	if n := len(u.queue); n != 2 {
		t.Fatalf("2회차 큐 = %d건, want 2건(③)", n)
	}
	if got := second[index.AxisPlayback]; got.stalled != 0 || got.resume != playback[1].cursor {
		t.Errorf("2회차 ③ 진행 = %+v, want 정체 0 · 커서 = ③ 마지막 행", got)
	}
}
