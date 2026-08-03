package indexer

import (
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// (a) — ReasonNextFile 은 INSERT 직후 즉시 요청한다. 그 행이 곧 꼬리이므로 IsTail=true 다.
func TestNextFileRequestsUploadImmediately(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	f.makeFile("s1", segName(baseWall, 0), 1000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	got := f.upload.targets()
	if len(got) != 1 {
		t.Fatalf("요청 %d건, want 1건", len(got))
	}
	if !got[0].IsTail {
		t.Error("IsTail = false, want true — 방금 넣은 행이 곧 꼬리다")
	}
	if got[0].StreamID != "s1" || got[0].Seq != 0 || got[0].Bytes != 1000 {
		t.Errorf("대상 = %+v", got[0])
	}
	if seq, ok := f.ix.requested["s1"]; !ok || seq != 0 {
		t.Errorf("requested = %v/%v, want 0 기록", seq, ok)
	}
	if _, held := f.ix.held["s1"]; held {
		t.Error("접수된 행이 held 에 남아 있다")
	}
}

// (h) — advance 가 예약 키를 커서에 실어야 한다.
//
// 필드명 리터럴이라 빠뜨려도 컴파일이 통과하고 빈 문자열이 된다. 그 상태로 held 가
// 승격되면 키가 빈 채 요청되어 대상 검증에 걸리고 마지막 조각이 영구 미업로드된다(R-a).
func TestAdvanceCarriesS3KeyIntoCursor(t *testing.T) {
	f := newFixture(t, 4000)
	f.makeFile("s1", segName(baseWall, 0), 1000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	cur := f.ix.cursors["s1"]
	want := index.S3Key("s1", 0, baseWall)
	if cur.Tail.S3Key != want {
		t.Fatalf("cur.Tail.S3Key = %q, want %q", cur.Tail.S3Key, want)
	}
	if got := f.upload.targets()[0].S3Key; got != want {
		t.Errorf("요청 대상의 S3Key = %q, want %q", got, want)
	}
}

// (b) — Idle 로 확정된 꼬리는 보류했다가, 다음 INSERT 로 꼬리가 아니게 되는 순간
// IsTail=false 로 요청한다. 그 시점에는 파일이 더 자랄 수 없다.
func TestIdleTailIsHeldThenPromotedByNextInsert(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	f.makeFile("s1", segName(baseWall, 0), 1000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonIdle))

	if n := len(f.upload.targets()); n != 0 {
		t.Fatalf("보류돼야 하는데 %d건 요청됐다", n)
	}
	h, ok := f.ix.held["s1"]
	if !ok || h.seq != 0 {
		t.Fatalf("held = %+v/%v, want seq 0", h, ok)
	}
	if want := baseWall.Add(f.opt.TailGrace); !h.eligibleAt.Equal(want) {
		t.Errorf("eligibleAt = %v, want %v (start_wall + TailGrace)", h.eligibleAt, want)
	}

	f.makeFile("s1", segName(baseWall, 4*time.Second), 1000)
	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile))

	got := f.upload.targets()
	if len(got) != 2 {
		t.Fatalf("요청 %d건, want 2건(승격 + 새 꼬리)", len(got))
	}
	if got[0].Seq != 0 || got[0].IsTail {
		t.Errorf("승격 대상 = %+v, want seq 0 IsTail=false", got[0])
	}
	if _, held := f.ix.held["s1"]; held && f.ix.held["s1"].seq == 0 {
		t.Error("승격된 행이 held 에 남아 있다")
	}
}

// (c) — 접수가 거부되면 아무것도 기록하지 않는다. held 를 지우지도 않는다.
// 포기는 now >= eligibleAt 일 때만이며, 그전에는 nextTry 로 미룬다.
func TestRejectedRequestKeepsHoldAndRecordsNothing(t *testing.T) {
	f := newFixture(t, 4000)
	f.upload.accept = false
	path := f.makeFile("s1", segName(baseWall, 0), 1000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	if _, ok := f.ix.requested["s1"]; ok {
		t.Error("거부됐는데 requested 를 기록했다 — D13 교정이 죽는다")
	}
	h, ok := f.ix.held["s1"]
	if !ok {
		t.Fatal("거부된 행이 held 에 없다 — 다시 시도할 길이 없어진다")
	}
	if !h.nextTry.After(h.since) {
		t.Errorf("nextTry = %v, want since(%v) 이후", h.nextTry, h.since)
	}

	// 틱이 와도 TailHold 전에는 조용하다.
	f.ix.ReleaseHeldTails()
	if n := len(f.upload.targets()); n != 1 {
		t.Errorf("요청 %d건, want 1건 — 보류 시간 전에 다시 시도했다", n)
	}

	// 시간을 밀면 다시 시도한다.
	f.ix.held["s1"] = heldTail{seq: 0, since: time.Now().Add(-time.Hour),
		nextTry: time.Now().Add(-time.Minute), eligibleAt: time.Now().Add(time.Hour)}
	f.upload.accept = true
	f.ix.ReleaseHeldTails()
	if n := len(f.upload.targets()); n != 2 {
		t.Fatalf("요청 %d건, want 2건", n)
	}
	if seq, ok := f.ix.requested["s1"]; !ok || seq != 0 {
		t.Errorf("접수됐는데 requested 미기록 (%v/%v)", seq, ok)
	}
	_ = path

	// eligibleAt 을 넘기면 포기한다.
	f.upload.accept = false
	f.ix.held["s2"] = heldTail{seq: 9, since: time.Now().Add(-time.Hour),
		nextTry: time.Now().Add(-time.Minute), eligibleAt: time.Now().Add(-time.Second)}
	f.ix.cursors["s2"] = &index.Cursor{NextSeq: 10, Tail: &index.TailRow{Seq: 9}}
	f.ix.ReleaseHeldTails()
	if _, ok := f.ix.held["s2"]; ok {
		t.Error("eligibleAt 을 넘겼는데 계속 붙들고 있다")
	}
	if n := f.logs.count(slog.LevelDebug, "tail_upload_hold_abandoned"); n != 1 {
		t.Errorf("tail_upload_hold_abandoned = %d건, want 1", n)
	}
}

// (c-2) — 틱마다 예산만큼만 처리하고, 오래 기다린 것부터 본다.
// Go 맵 순회는 무작위라 정렬 없이는 라운드로빈이 성립하지 않는다.
func TestReleaseHeldTailsHonorsBudgetAndOrder(t *testing.T) {
	f := newFixture(t, 4000)
	f.upload.accept = false
	now := time.Now()
	for i := 0; i < 12; i++ {
		sid := string(rune('a' + i))
		path := f.makeFile(sid, segName(baseWall, 0), 1000)
		f.ix.cursors[sid] = &index.Cursor{NextSeq: 1, Tail: &index.TailRow{
			Seq: 0, StartWallUTC: baseWall, Bytes: 1000, LocalPath: path,
			S3Key: index.S3Key(sid, 0, baseWall), UploadState: index.UploadStatePending,
		}}
		// since 가 이를수록 오래 기다린 것이다. i=11 이 가장 오래됐다.
		f.ix.held[sid] = heldTail{seq: 0,
			since:      now.Add(-time.Duration(i) * time.Minute),
			nextTry:    now.Add(-time.Minute),
			eligibleAt: now.Add(time.Hour),
		}
	}

	f.ix.ReleaseHeldTails()

	got := f.upload.targets()
	if len(got) != f.opt.HoldStatBudget {
		t.Fatalf("요청 %d건, want %d건(예산)", len(got), f.opt.HoldStatBudget)
	}
	if got[0].StreamID != "l" {
		t.Errorf("첫 대상 = %q, want %q — since 오름차순이 아니다", got[0].StreamID, "l")
	}
}

// (d)(e) — pending + requested 인 꼬리가 자라면 경고하고 통상 교정 경로로 계속 간다.
// 자라지 않았으면 DEBUG 로 조기 반환한다.
func TestRegrowAfterUploadRequested(t *testing.T) {
	t.Run("자랐으면_WARN_후_교정", func(t *testing.T) {
		f := newFixture(t, 4000, 6000)
		path := f.makeFile("s1", segName(baseWall, 0), 1000)
		f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
		if err := os.WriteFile(path, make([]byte, 3000), 0o644); err != nil {
			t.Fatal(err)
		}

		seg := f.segment("s1", segName(baseWall, 0), 3000, recording.ReasonRegrown)
		f.mustHandle(seg)

		if n := f.logs.count(slog.LevelWarn, "regrow_after_upload_requested"); n != 1 {
			t.Errorf("regrow_after_upload_requested = %d건, want 1", n)
		}
		if len(f.store.updateTail) != 1 {
			t.Errorf("UpdateTail %d건, want 1건 — 통상 경로로 계속 가야 자가 치유된다", len(f.store.updateTail))
		}
	})

	t.Run("안_자랐으면_DEBUG_조기반환", func(t *testing.T) {
		f := newFixture(t, 4000, 4000)
		f.makeFile("s1", segName(baseWall, 0), 1000)
		f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

		f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonRegrown))

		if n := f.logs.count(slog.LevelDebug, "regrow_after_upload_noop"); n != 1 {
			t.Errorf("regrow_after_upload_noop = %d건, want 1", n)
		}
		if len(f.store.updateTail) != 0 {
			t.Errorf("UpdateTail %d건, want 0건", len(f.store.updateTail))
		}
	})
}

// (f)(g) — 확정된 꼬리가 자란 경우. uploaded 는 잘린 실물이 굳었다는 뜻이라 ERROR,
// failed 는 실물이 안 올라갔으므로 굳음은 아니지만 자동 회복이 보장되지 않는다(L20).
func TestRegrowAfterTerminalState(t *testing.T) {
	cases := []struct {
		state index.UploadState
		msg   string
		level slog.Level
	}{
		{index.UploadStateUploaded, "regrow_after_upload_ignored", slog.LevelError},
		{index.UploadStateFailed, "regrow_after_failed_ignored", slog.LevelWarn},
	}
	for _, c := range cases {
		t.Run(string(c.state), func(t *testing.T) {
			f := newFixture(t, 4000)
			path := f.makeFile("s1", segName(baseWall, 0), 2500)
			f.store.seed(index.Record{
				StreamID: "s1", Seq: 0, StartWallUTC: baseWall, DurationMS: 4000,
				LocalPath: path, UploadState: c.state, Bytes: 1000,
			})

			seg := f.segment("s1", segName(baseWall, 0), 2500, recording.ReasonRegrown)
			f.mustHandle(seg)

			if n := f.logs.count(c.level, c.msg); n != 1 {
				t.Fatalf("%s(%v) = %d건, want 1", c.msg, c.level, n)
			}
			if len(f.store.updateTail) != 0 {
				t.Errorf("UpdateTail %d건, want 0건", len(f.store.updateTail))
			}
		})
	}
}

// (i) — recoverTail 의 파일 부재·stat 실패는 pending 일 때만 경고다.
// 그 외는 recordDeleteAfter·janitor 가 지운 정상 상황이라 5분마다 WARN 이 폭주한다.
func TestRecoverTailFileGoneIsWarnOnlyWhenPending(t *testing.T) {
	cases := []struct {
		state index.UploadState
		level slog.Level
		msg   string
	}{
		{index.UploadStatePending, slog.LevelWarn, "tail_file_missing"},
		{index.UploadStateUploaded, slog.LevelDebug, "tail_file_gone"},
		{index.UploadStateFailed, slog.LevelDebug, "tail_file_gone"},
	}
	for _, c := range cases {
		t.Run(string(c.state), func(t *testing.T) {
			f := newFixture(t, 4000)
			// 스트림 디렉토리는 있어야 Scan 이 그 스트림을 발견한다. 꼬리 행이 가리키는
			// 파일만 없는 상태를 만든다.
			dir := filepath.Dir(f.makeFile("s1", segName(baseWall, 4*time.Second), 1000))
			f.store.seed(index.Record{
				StreamID: "s1", Seq: 0, StartWallUTC: baseWall, DurationMS: 4000,
				LocalPath: filepath.Join(dir, "사라진파일.mp4"), UploadState: c.state, Bytes: 1000,
			})

			if err := f.ix.Scan(t.Context(), f.root); err != nil {
				t.Fatalf("Scan 실패: %v", err)
			}

			if n := f.logs.count(c.level, c.msg); n != 1 {
				t.Fatalf("%s(%v) = %d건, want 1", c.msg, c.level, n)
			}
		})
	}
}

// ApplyUploadResult 는 그 행이 아직 꼬리일 때만 커서를 갱신한다.
func TestApplyUploadResult(t *testing.T) {
	f := newFixture(t, 4000)
	f.makeFile("s1", segName(baseWall, 0), 1000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	f.ix.ApplyUploadResult("s1", 0, index.UploadStateUploaded)

	if got := f.ix.cursors["s1"].Tail.UploadState; got != index.UploadStateUploaded {
		t.Errorf("커서 상태 = %q, want uploaded", got)
	}
	if _, ok := f.ix.requested["s1"]; ok {
		t.Error("결과가 왔는데 requested 가 남아 있다")
	}

	// 꼬리가 아닌 seq 의 결과는 커서를 건드리지 않는다.
	f.ix.ApplyUploadResult("s1", 99, index.UploadStateFailed)
	if got := f.ix.cursors["s1"].Tail.UploadState; got != index.UploadStateUploaded {
		t.Errorf("커서 상태 = %q, want uploaded (변하면 안 된다)", got)
	}
	// 모르는 스트림도 안전하다.
	f.ix.ApplyUploadResult("없는스트림", 1, index.UploadStateUploaded)
}

// 커서를 재적재하면 메모리 상태를 새 커서에 맞춘다.
// 맞추지 않으면 옛 seq 의 requested·held 가 남아 D13 교정과 보류 판정이 어긋난다.
func TestReconcileUploadStateOnCursorReload(t *testing.T) {
	f := newFixture(t)
	path := f.makeFile("s1", segName(baseWall, 0), 1000)
	f.store.seed(index.Record{
		StreamID: "s1", Seq: 0, StartWallUTC: baseWall, DurationMS: 4000,
		LocalPath: path, UploadState: index.UploadStateUploaded, Bytes: 1000,
	})
	f.ix.requested["s1"] = 7
	f.ix.held["s1"] = heldTail{seq: 7}

	if err := f.ix.Scan(t.Context(), f.root); err != nil {
		t.Fatalf("Scan 실패: %v", err)
	}

	if _, ok := f.ix.requested["s1"]; ok {
		t.Error("재적재 후에도 옛 requested 가 남아 있다")
	}
	if _, ok := f.ix.held["s1"]; ok {
		t.Error("재적재 후에도 옛 held 가 남아 있다")
	}
}

// tq-17 — accepted=false 면 인덱서는 아무것도 기록하지 않는다.
//
// Disabled·격리·백오프·브레이커·큐 포화가 전부 이 값 하나로 오며, 인덱서에는 그 구분이
// 쓸모가 없다. **QueueFull 이 실제로 false 를 돌려주는지는 이 테스트가 아니라
// upload 패키지의 TestRequestUploadReturnsFalseWhenQueueIsFull 이 고정한다** —
// 여기서 쓰는 것은 가짜라 반환값을 테스트가 정하기 때문이다.
func TestRejectedRequestRecordsNothing(t *testing.T) {
	f := newFixture(t, 4000)
	f.upload.accept = false // Disabled·격리·백오프·브레이커·QueueFull 이 전부 이 값이다
	f.makeFile("s1", segName(baseWall, 0), 1000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))

	if _, ok := f.ix.requested["s1"]; ok {
		t.Fatal("false 인데 requested 를 기록했다 — 그 작업은 영원히 처리되지 않는다")
	}
	if f.ix.cursors["s1"].Tail.UploadState != index.UploadStatePending {
		t.Error("false 인데 커서 상태를 바꿨다")
	}
}

// holdNow 는 "방금 보류된 꼬리"를 프로덕션 경로와 같은 모양으로 만든다.
// start_wall 을 현재 시각으로 두어 eligibleAt(= start_wall + TailGrace)이 미래가 되게 한다 —
// 픽스처의 baseWall 은 과거라 그대로 쓰면 매 틱 포기 분기로 빠진다.
func (f *fixture) holdNow(t *testing.T, streamID string, size int) string {
	t.Helper()
	path := f.makeFile(streamID, segName(baseWall, 0), size)
	wall := time.Now().UTC()
	f.ix.cursors[streamID] = &index.Cursor{NextSeq: 1, Tail: &index.TailRow{
		Seq: 0, StartWallUTC: wall, Bytes: int64(size), LocalPath: path,
		S3Key: index.S3Key(streamID, 0, wall), UploadState: index.UploadStatePending,
	}}
	f.ix.holdTail(streamID, f.ix.cursors[streamID].Tail, time.Now())
	return path
}

// tq-B5 — releaseHeldTail 의 판정 4개를 실경로(ReleaseHeldTails)로 고정한다.
func TestReleaseHeldTailJudgements(t *testing.T) {
	// ① TailHold 만큼은 무조건 기다린다. 그 시간이 "더 안 자란다"의 유일한 근거이며,
	//    빼면 유휴 판정 직후의 꼬리를 그대로 올려 잘린 실물이 S3 에 굳는다.
	t.Run("TailHold_전에는_올리지_않는다", func(t *testing.T) {
		f := newFixture(t)
		f.holdNow(t, "s1", 1000)

		f.ix.ReleaseHeldTails()
		if n := len(f.upload.targets()); n != 0 {
			t.Fatalf("보류 시간 전에 %d건 요청됐다", n)
		}

		time.Sleep(f.opt.TailHold + 10*time.Millisecond)
		f.ix.ReleaseHeldTails()
		if n := len(f.upload.targets()); n != 1 {
			t.Fatalf("보류 시간이 지났는데 %d건 요청됐다, want 1건", n)
		}
	})

	// ② 크기가 장부와 다르면 아직 자라는 중이다. 이 판정을 빼면 ①과 같은 사고가 난다.
	t.Run("크기가_다르면_올리지_않고_미룬다", func(t *testing.T) {
		f := newFixture(t)
		path := f.holdNow(t, "s1", 1000)
		if err := os.WriteFile(path, make([]byte, 1500), 0o644); err != nil {
			t.Fatal(err)
		}
		before := f.ix.held["s1"].nextTry
		time.Sleep(f.opt.TailHold + 10*time.Millisecond)

		f.ix.ReleaseHeldTails()

		if n := len(f.upload.targets()); n != 0 {
			t.Fatalf("자라는 중인데 %d건 요청됐다", n)
		}
		if n := f.logs.count(slog.LevelDebug, "tail_upload_hold_extended"); n != 1 {
			t.Errorf("tail_upload_hold_extended = %d건, want 1", n)
		}
		if !f.ix.held["s1"].nextTry.After(before) {
			t.Error("nextTry 가 갱신되지 않았다 — 매 틱 stat 을 다시 태운다")
		}
	})

	// ③ stat 자체가 실패해도 nextTry 를 미룬다. 미루지 않으면 매 틱 stat 을 재소모하고
	//    예산 8개를 독점해 다른 스트림의 꼬리가 굶는다(A9 tq-16 과 같은 클래스).
	t.Run("stat_실패는_nextTry_를_미룬다", func(t *testing.T) {
		f := newFixture(t)
		path := f.holdNow(t, "s1", 1000)
		// 경로의 부모를 파일로 만들어 ENOTDIR 을 낸다 — 부재(ENOENT)와 다른 오류다.
		if err := os.Remove(path); err != nil {
			t.Fatal(err)
		}
		f.ix.cursors["s1"].Tail.LocalPath = filepath.Join(path, "child.mp4")
		if err := os.WriteFile(path, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
		before := f.ix.held["s1"].nextTry
		time.Sleep(f.opt.TailHold + 10*time.Millisecond)

		f.ix.ReleaseHeldTails()

		if n := len(f.upload.targets()); n != 0 {
			t.Fatalf("stat 이 실패했는데 %d건 요청됐다", n)
		}
		h, ok := f.ix.held["s1"]
		if !ok {
			t.Fatal("보류가 사라졌다 — 다음 기회를 잃는다")
		}
		if !h.nextTry.After(before) {
			t.Error("nextTry 가 갱신되지 않았다 — 매 틱 stat 을 재소모하고 예산을 독점한다")
		}
	})

	// ④ 커서가 앞서갔으면 그 보류는 의미가 없다. 지우지 않으면 예산만 계속 먹는다.
	t.Run("커서가_앞서가면_보류를_버린다", func(t *testing.T) {
		f := newFixture(t)
		f.holdNow(t, "s1", 1000)
		f.ix.cursors["s1"].Tail.Seq = 1 // 다음 조각이 들어온 상황

		f.ix.ReleaseHeldTails()

		if _, ok := f.ix.held["s1"]; ok {
			t.Error("낡은 보류가 남아 있다")
		}
		if n := f.logs.count(slog.LevelDebug, "held_tail_stale"); n != 1 {
			t.Errorf("held_tail_stale = %d건, want 1", n)
		}
	})

	// bytes 가 없는 행은 CAS 기대값을 만들 수 없어 요청 자체를 하지 않는다(결정 14).
	t.Run("bytes_가_0_이면_요청하지_않는다", func(t *testing.T) {
		f := newFixture(t)
		// 실물도 장부도 0 바이트라 크기 판정은 통과한다 — 그 다음 관문이 이 검사다.
		f.holdNow(t, "s1", 0)
		time.Sleep(f.opt.TailHold + 10*time.Millisecond)

		f.ix.ReleaseHeldTails()

		if n := len(f.upload.targets()); n != 0 {
			t.Fatalf("bytes=0 인데 %d건 요청됐다", n)
		}
		if n := f.logs.count(slog.LevelError, "upload_target_rejected"); n != 1 {
			t.Errorf("upload_target_rejected = %d건, want 1", n)
		}
	})
}

// tq-B7 — 교정이 보류 시계를 리셋한다.
// 리셋하지 않으면 방금 자란 것을 확인하고도 다음 틱에 바로 올려 버린다.
func TestTailCorrectionResetsHoldClock(t *testing.T) {
	f := newFixture(t, 4000, 6000)
	path := f.makeFile("s1", segName(baseWall, 0), 1000)
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonIdle))

	before := f.ix.held["s1"]
	if before.seq != 0 {
		t.Fatalf("준비 실패: held = %+v", before)
	}
	time.Sleep(5 * time.Millisecond)
	if err := os.WriteFile(path, make([]byte, 3000), 0o644); err != nil {
		t.Fatal(err)
	}

	f.mustHandle(f.segment("s1", segName(baseWall, 0), 3000, recording.ReasonRegrown))

	after := f.ix.held["s1"]
	if !after.since.After(before.since) {
		t.Errorf("since 가 리셋되지 않았다: %v -> %v", before.since, after.since)
	}
	if !after.nextTry.Equal(after.since) {
		t.Errorf("nextTry = %v, want since(%v) 와 같음", after.nextTry, after.since)
	}
	// eligibleAt 은 start_wall 기반이라 불변이어야 스위퍼 자격 시점과 계속 맞물린다(R9).
	if !after.eligibleAt.Equal(before.eligibleAt) {
		t.Errorf("eligibleAt 이 변했다: %v -> %v", before.eligibleAt, after.eligibleAt)
	}
}

// tq-B3 — reconcileUploadState 의 가드 셋을 각각 구분해 고정한다.
func TestReconcileUploadStateGuards(t *testing.T) {
	newIx := func(t *testing.T, state index.UploadState, tailSeq int64) *fixture {
		f := newFixture(t)
		f.ix.cursors["s1"] = &index.Cursor{NextSeq: tailSeq + 1, Tail: &index.TailRow{
			Seq: tailSeq, StartWallUTC: baseWall, Bytes: 1000, UploadState: state,
		}}
		return f
	}

	t.Run("held_만_어긋나면_held_만_지운다", func(t *testing.T) {
		f := newIx(t, index.UploadStatePending, 5)
		f.ix.held["s1"] = heldTail{seq: 4}
		f.ix.requested["s1"] = 5

		f.ix.reconcileUploadState("s1")

		if _, ok := f.ix.held["s1"]; ok {
			t.Error("어긋난 held 가 남아 있다")
		}
		if _, ok := f.ix.requested["s1"]; !ok {
			t.Error("맞는 requested 까지 지웠다")
		}
	})

	t.Run("requested_만_어긋나면_requested_만_지운다", func(t *testing.T) {
		f := newIx(t, index.UploadStatePending, 5)
		f.ix.held["s1"] = heldTail{seq: 5}
		f.ix.requested["s1"] = 4

		f.ix.reconcileUploadState("s1")

		if _, ok := f.ix.requested["s1"]; ok {
			t.Error("어긋난 requested 가 남아 있다")
		}
		if _, ok := f.ix.held["s1"]; !ok {
			t.Error("맞는 held 까지 지웠다")
		}
	})

	t.Run("이미_확정된_꼬리면_둘_다_지운다", func(t *testing.T) {
		f := newIx(t, index.UploadStateUploaded, 5)
		f.ix.held["s1"] = heldTail{seq: 5}
		f.ix.requested["s1"] = 5

		f.ix.reconcileUploadState("s1")

		if _, ok := f.ix.held["s1"]; ok {
			t.Error("확정된 꼬리의 held 가 남아 있다")
		}
		if _, ok := f.ix.requested["s1"]; ok {
			t.Error("확정된 꼬리의 requested 가 남아 있다")
		}
	})
}

// tq-B3 — seq 충돌 재적재 경로에서도 맞춰야 한다.
// 그 경로는 커서를 통째로 갈아 끼우므로 옛 seq 의 requested 가 그대로 남으면
// 확인 2.5 가 엉뚱한 행을 근거로 돌아간다.
func TestReconcileRunsOnSeqConflictReload(t *testing.T) {
	f := newFixture(t, 4000)
	f.upload.accept = false // 접수되면 advance 가 requested 를 덮어써 관측이 흐려진다

	// DB 에는 seq 0 이 이미 있는데 메모리 커서는 비어 있는 상태 = 단일 쓰기자 전제 붕괴.
	path := f.makeFile("s1", segName(baseWall, 0), 1000)
	f.store.seed(index.Record{
		StreamID: "s1", Seq: 0, StartWallUTC: baseWall, DurationMS: 4000,
		LocalPath: path, UploadState: index.UploadStatePending, Bytes: 1000,
	})
	f.ix.cursors["s1"] = &index.Cursor{NextSeq: 0}
	f.ix.requested["s1"] = 99 // 옛 커서가 남긴 값

	next := f.makeFile("s1", segName(baseWall, 4*time.Second), 1000)
	_ = next
	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile))

	if seq, ok := f.ix.requested["s1"]; ok && seq == 99 {
		t.Error("재적재 후에도 옛 requested(99)가 남아 있다")
	}
}

// tq-B2 — tail_stat_failed 도 pending 한정이다(계획 6.3 (i)).
// 확정된 행의 stat 실패는 조사 대상이 아니라 정상 상황이라 5분마다 WARN 이 폭주한다.
func TestRecoverTailStatFailureIsWarnOnlyWhenPending(t *testing.T) {
	cases := []struct {
		state index.UploadState
		level slog.Level
	}{
		{index.UploadStatePending, slog.LevelWarn},
		{index.UploadStateUploaded, slog.LevelDebug},
	}
	for _, c := range cases {
		t.Run(string(c.state), func(t *testing.T) {
			f := newFixture(t, 4000)
			// 경로의 부모가 파일이면 stat 이 ENOTDIR 로 실패한다(부재와 다른 오류다).
			blocker := f.makeFile("s1", segName(baseWall, 4*time.Second), 1000)
			f.store.seed(index.Record{
				StreamID: "s1", Seq: 0, StartWallUTC: baseWall, DurationMS: 4000,
				LocalPath: filepath.Join(blocker, "child.mp4"), UploadState: c.state, Bytes: 1000,
			})

			if err := f.ix.Scan(t.Context(), f.root); err != nil {
				t.Fatalf("Scan 실패: %v", err)
			}

			if n := f.logs.count(c.level, "tail_stat_failed"); n != 1 {
				t.Fatalf("tail_stat_failed(%v) = %d건, want 1", c.level, n)
			}
		})
	}
}

// tq-B2 — 확정된 꼬리의 파일이 사라진 경우 correctTail 도 조용히 넘어간다.
func TestCorrectTailOnTerminalRowWithMissingFile(t *testing.T) {
	f := newFixture(t, 4000)
	missing := filepath.Join(f.root, "s1", "사라진파일.mp4")
	f.ix.cursors["s1"] = &index.Cursor{NextSeq: 1, Tail: &index.TailRow{
		Seq: 0, StartWallUTC: baseWall, Bytes: 1000, LocalPath: missing,
		UploadState: index.UploadStateUploaded,
	}}

	seg := recording.Segment{StreamID: "s1", Path: missing, StartWall: baseWall,
		Reason: recording.ReasonRegrown}
	if err := f.ix.correctTail(t.Context(), seg); err != nil {
		t.Fatalf("correctTail 실패: %v", err)
	}

	if n := f.logs.count(slog.LevelDebug, "tail_file_gone"); n != 1 {
		t.Errorf("tail_file_gone = %d건, want 1", n)
	}
	if n := f.logs.count(slog.LevelError, "regrow_after_upload_ignored"); n != 0 {
		t.Errorf("파일이 없는데 굳음으로 판정했다 (%d건)", n)
	}
	if len(f.store.updateTail) != 0 {
		t.Errorf("UpdateTail %d건, want 0건", len(f.store.updateTail))
	}
}

// tq-B10 — 보류 기본값은 계약이다.
// HoldTick 이 30s 급이면 마지막 조각의 총 지연이 48초까지 늘어 AC1 재현성이 깨지고,
// HoldStatBudget 이 바뀌면 틱당 stat 비용과 지연 상한(틱 x ceil(n/예산))이 함께 흔들린다.
func TestHoldDefaults(t *testing.T) {
	opt := DefaultOptions()
	if opt.HoldTick != time.Second {
		t.Errorf("HoldTick = %v, want 1s", opt.HoldTick)
	}
	if opt.HoldStatBudget != 8 {
		t.Errorf("HoldStatBudget = %d, want 8", opt.HoldStatBudget)
	}
	if opt.TailHold != 5*time.Second {
		t.Errorf("TailHold = %v, want 5s", opt.TailHold)
	}
	// TailGrace 는 기본값이 없다 — config 가 upload 쪽 값을 넣어 준다.
	if opt.TailGrace != 0 {
		t.Errorf("TailGrace = %v, want 0 (소유자는 upload.Options 하나)", opt.TailGrace)
	}
}
