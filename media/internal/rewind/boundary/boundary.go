// Package boundary 는 되감기 창의 경계를 정한다 — 어느 조각부터 어느 조각까지 목록에 실을 수 있는가.
//
// 설계 3.2 의 rewind/boundary(「settled 접두와 1시간 창」)다. 판정은 설계 4.1 의 settled 술어,
// 산식은 설계 4.2 그대로다. 입력은 Snapshot 하나뿐이며 DB·S3·HTTP·파일 시스템을 모른다 —
// 같은 입력이면 같은 답을 내는 순수 계층이라 로그도 남기지 않는다.
//
// 지키는 불변식(프로필 4절): 되감기 등재 기준은 ③ playback_upload_state='uploaded' 이고, 경계는
// 컷오프부터 **끊김 없이 이어진 접두의 끝**이다. 머리를 settled 행의 최댓값으로 읽으면 홀 뒤
// 조각이 목록에 실려 404 가 난다.
package boundary

import "time"

// WindowMS 는 되감기 창의 길이다 — 1시간 = 3,600,000ms(설계 4.2 의 3_600_000).
//
// 꼬리를 자르는 기준이며, 1시간이 차면 창 길이는 [WindowMS, WindowMS + 조각 길이 최댓값) 안에 든다.
// 조각 길이가 ms 정수(duration_ms)라 같은 단위로 둔다.
const WindowMS = int64(time.Hour / time.Millisecond)

// Row 는 경계 계산이 보는 장부 한 행이다 — 설계 4.1 settled 정본 SQL 이 읽는 열 가운데 경계가
// 쓰는 것만 싣는다(is_discontinuity 는 경계가 쓰지 않아 싣지 않는다).
//
// NULL 은 영값으로 나른다. index.SeedResult 와 같은 규약(영값 = NULL)이라 캐시가 INSERT push 값을
// 그대로 옮겨 담는다. 장부의 세 열(session_id·playback_pdt·playback_s3_key)에는 NULL 아니면 실제 값이
// 들어가므로 영값과 겹치지 않는다.
type Row struct {
	// Seq 는 stream_segments.seq 다.
	Seq int64
	// SessionID 는 session_id 다. "" 면 NULL(회차 비귀속)이다.
	SessionID string
	// DurationMS 는 duration_ms 다 — 꼬리를 자르는 길이 축이다.
	DurationMS int32
	// PlaybackPDT 는 playback_pdt 다. 영값이면 NULL 이다.
	PlaybackPDT time.Time
	// PlaybackS3Key 는 playback_s3_key 다. "" 면 NULL(키 파생 실패 포함)이다.
	PlaybackS3Key string
	// PlaybackUploaded 는 playback_upload_state = 'uploaded' 인가다(SQL 의 pb_uploaded).
	// ② 축(upload_state)이 아니라 ③ 축이다 — 되감기 등재 기준이 ③ 이다.
	PlaybackUploaded bool
	// IsGap 은 (stream_id, seq) 가 발행된 GAP 원장(stream_published_gaps)에 있는가다(SQL 의 is_gap).
	IsGap bool
}

// Snapshot 은 한 스트림의 경계 계산 입력이다 — 장부·GAP 원장·컷오프를 읽기만 하는 창이다.
//
// 인터페이스인 이유는 입력의 출처가 실제로 둘이기 때문이다(계획 3절 표): 운영은 루프가 소유한
// 메모리 캐시(rewind/cache — 평시 DB 조회 0)이고 검증은 픽스처다. 계산은 출처와 무관하게
// 같아야 한다.
type Snapshot interface {
	// Cutoff 는 그 스트림의 활성화 컷오프(stream_cutoffs.cutoff_seq)다. ok 가 거짓이면 컷오프가
	// 없다 — 되감기를 제공하지 않는 스트림이다(설계 4.2 ⓑ).
	Cutoff() (seq int64, ok bool)
	// RowsFrom 은 seq ≥ from 인 행을 seq 오름차순으로 전부 준다. 돌려받은 슬라이스는 읽기만
	// 한다 — 구현이 복사 없이 내부 슬라이스를 내줄 수 있게 하는 약속이다.
	RowsFrom(from int64) []Row
}

// Window 는 경계 계산 한 번의 결과다 — 설계 4.2 의 세 값 그대로다.
//
// 창은 [TailSeq, HeadSeq] 다. 비어 있으면 HeadSeq = ScanFrom − 1 이고 TailSeq 는 컷오프다.
type Window struct {
	// ScanFrom 은 이번 스캔의 시작 seq 다 = max(컷오프, 직전 계산의 TailSeq).
	ScanFrom int64
	// HeadSeq 는 ScanFrom 부터 끊김 없이 이어진 settled 접두의 마지막 seq 다. 머리는 자르지 않는다.
	HeadSeq int64
	// TailSeq 는 창의 첫 seq 다 — [TailSeq, HeadSeq] 의 길이 합이 WindowMS 이상인 가장 늦은
	// seq 이고, 합이 WindowMS 에 못 미치면 컷오프다.
	TailSeq int64
}

// Settled 는 행이 되감기 목록에 실릴 수 있는가다 — 설계 4.1 settled 술어의 정본이다.
//
//	settled(k) ⟺ k >= cutoff ∧ playback_pdt(k) IS NOT NULL ∧ session_id(k) IS NOT NULL
//	             ∧ playback_s3_key(k) IS NOT NULL
//	             ∧ ( playback_upload_state(k)='uploaded' ∨ (stream_id,k) ∈ stream_published_gaps )
//
// 판정 자리는 이 함수 하나다. 캐시·부팅 재구성·정합성 감사는 SQL 로 행만 가져오고 모두 이것을
// 부른다 — 판정이 두 자리에 있으면 캐시와 감사가 서로 다른 접두를 보고 드리프트를 오보한다.
// 세 NULL 검사는 r6 의 NOT VALID CHECK 를 대체한다: "세션이 있으면 PDT·키도 있다"는 쓰기 경로의
// 성질일 뿐 DB 제약이 아니다. 컷오프 미만은 홀이 아니라 범위 밖이다(설계 4.2 ⓐ).
func Settled(r Row, cutoff int64) bool {
	return r.Seq >= cutoff &&
		!r.PlaybackPDT.IsZero() &&
		r.SessionID != "" &&
		r.PlaybackS3Key != "" &&
		(r.PlaybackUploaded || r.IsGap)
}

// Compute 는 한 스트림의 되감기 창을 계산한다(설계 4.2). 컷오프가 없으면 계산하지 않고 거짓을
// 돌려준다 — 컷오프 없는 스트림은 되감기를 제공하지 않는다.
//
//	firstAvail  = cutoff                                     -- 접두 시작점 = 컷오프 행 자신(포함)
//	scanFrom    = max(firstAvail, prevTail)
//	headSeq     = scanFrom..n 스캔에 나온 행이 전부 settled 인 가장 큰 n   (없으면 scanFrom − 1)
//	suffixMs(t) = Σ duration_ms[k]   (k = t..headSeq, 스캔에 나온 행)
//	tailSeq     = firstAvail                            if suffixMs(firstAvail) < WindowMS
//	            = max{ t : suffixMs(t) >= WindowMS }    otherwise
//
// prevTail 은 같은 스트림의 직전 계산이 돌려준 TailSeq 다(처음이면 0 — max 가 컷오프로 올린다).
// 머리는 홀 앞에서 멈추고(headSeq < H) 꼬리는 머리를 넘지 않으므로(tailSeq ≤ headSeq) 다음 계산의
// scanFrom 도 홀을 넘지 않는다 — 홀 뒤 조각은 홀이 메워지거나 GAP 으로 등재되기 전에는 창에
// 실리지 않는다(설계 4.2 cc 검증 성질).
//
// tailSeq 가 최솟값이 아니라 **최댓값**인 것이 계약이다. suffixMs 는 t 에 대해 단조 감소라
// 최솟값은 언제나 firstAvail 이고 창이 끝없이 자란다(r4 오류). 최댓값이면 창 길이가
// [WindowMS, WindowMS + max(duration)) 안에 든다.
//
// 산식의 전제는 settled 단조(한 번 settled 인 행은 계속 settled)와 settled 행의 길이 비감소이며,
// 그 전제에서 꼬리는 뒤로 가지 않는다. 전제가 깨진 입력이 만든 역행·미settled 창은 발행 전
// 검사(설계 4.5.5 S2·S3)가 거른다.
func Compute(snap Snapshot, prevTail int64) (Window, bool) {
	cutoff, ok := snap.Cutoff()
	if !ok {
		return Window{}, false
	}
	firstAvail := cutoff
	scanFrom := max(firstAvail, prevTail)
	prefix := settledPrefix(snap.RowsFrom(scanFrom), cutoff)

	headSeq := scanFrom - 1 // 빈 창
	if len(prefix) > 0 {
		headSeq = prefix[len(prefix)-1].Seq
	}
	return Window{ScanFrom: scanFrom, HeadSeq: headSeq, TailSeq: tailSeq(prefix, firstAvail)}, true
}

// settledPrefix 는 rows 의 앞에서부터 끊김 없이 settled 인 행들이다. 첫 비settled 행에서 멈추는
// 것이 계약이다 — 건너뛰면 홀 뒤 조각이 창에 실린다.
func settledPrefix(rows []Row, cutoff int64) []Row {
	for i, r := range rows {
		if !Settled(r, cutoff) {
			return rows[:i]
		}
	}
	return rows
}

// tailSeq 는 prefix 의 끝(머리)에서 거꾸로 길이를 누적해 처음으로 WindowMS 에 닿는 행의 seq 다
// = max{ t : suffixMs(t) >= WindowMS }. 끝까지 못 닿으면 firstAvail 이다.
func tailSeq(prefix []Row, firstAvail int64) int64 {
	var suffixMS int64
	for i := len(prefix) - 1; i >= 0; i-- {
		suffixMS += int64(prefix[i].DurationMS)
		if suffixMS >= WindowMS {
			return prefix[i].Seq
		}
	}
	return firstAvail
}
