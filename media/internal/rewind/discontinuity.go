package rewind

import "fmt"

// HasDiscontinuityTag 는 seq 인 조각 앞에 EXT-X-DISCONTINUITY 가 서는가다 — 계획 4.2-R RF 의
// 끊김 표시 규칙(kty 결정 F · ADR-073)이다. s 는 그 조각이 귀속된 회차다.
//
//	DISCONTINUITY(k) ⟺ k = 자기 회차의 첫 조각 ∧ 그 회차의 inherits_session ≠ NULL
//	회차 첫 조각     = max(회차 최소 seq, cutoff)
//
// 표시는 계승 회차의 첫 조각마다 하나씩 선다 — 직전 회차에서 그 회차로 넘어가는 자리다. 그래서
// 한 목록의 표시 수는 첫 조각이 그 목록에 실린 계승 회차의 수와 같다. 예: 직전 회차도 계승
// 회차이고(연쇄 계승) 두 회차의 첫 조각이 다 창 안에 있으면 표시는 둘이다(계획 4.2-R RF). 회차
// 안에서는 init 이 늘 같고(다르면 init_mismatch 가 회차를 끊는다) 시간 도장이 이어 붙어 있어서
// (계획 4.2-R R3) 끊김이 있어도 달지 않는다 — 그 끊김은 PDT 점프로만 드러난다. 그래서 장부의
// is_discontinuity 는 근거가 아니다(설계 4.3 의 "is_discontinuity(k)=true ∨" 항 폐기 — 계획 부기 29).
//
// 목록 문맥 없이 행과 그 회차만 보고 정하는 것이 계약이다. 같은 행이 직전 회차의 목록과 계승
// 회차의 목록 양쪽에 실리는데, 어느 목록에서 축출되든 discontinuity_base 에 같은 값이 더해져야
// 하기 때문이다. 그래서 렌더와 축출 증분(EvictedDiscontinuityTags)이 이 함수 하나를 부른다 —
// 둘이 갈리면 축출 뒤 DISCONTINUITY-SEQUENCE 가 실제 표시 수와 어긋난다. 회차 첫 조각을 컷오프로
// 자르는 것도 같은 이유다: 부팅 재구성은 seq ≥ cutoff 인 행만 다시 싣는다. 컷오프 아래 행으로
// 판정하면 재기동 전후로 같은 행의 판정이 갈려, 이미 나간 목록 머리 앞에 표시가 새로 선다.
func HasDiscontinuityTag(seq int64, s Session, cutoff int64) bool {
	return s.InheritsSession != "" && seq == s.firstSeq(cutoff)
}

// firstSeq 는 회차 첫 조각의 seq 다 — 회차 최소 seq 와 컷오프 가운데 큰 쪽이다(컷오프로 자르는
// 까닭은 HasDiscontinuityTag). 끊김 표시와 발행 전 검사 S3(checkRange)가 이 정의 하나를 쓴다 —
// 따로 적으면 둘을 함께 묶는 테스트가 없어 한쪽만 바뀌어도 조용히 갈린다.
func (s Session) firstSeq(cutoff int64) int64 {
	return max(s.MinSeq, cutoff)
}

// EvictedDiscontinuityTags 는 직전 목록 prev 가 첫 조각이 nextMSN 인 다음 목록으로 넘어갈 때 목록 앞에서
// 빠지는 끊김 표시(EXT-X-DISCONTINUITY)의 수다 — 목록 소유 회차의 discontinuity_base 에 더할 증분이다(계획
// PR ⓑ discontinuity_base 정의 · 설계 4.2 「축출 시 DISC-SEQ 증가 의무」).
//
//	증분 = |{ r ∈ prev.Rows : r.Seq < nextMSN ∧ HasDiscontinuityTag(r.Seq, r 의 회차, prev.Cutoff) }|
//
// 조각의 Discontinuity Sequence Number 는 DISC-SEQ 에 그 조각 URI 줄 앞의 표시 수를 더한 값이고(RFC
// 8216bis-22 6.2.1), 표시를 목록에서 없애면 남은 조각의 그 번호가 바뀌지 않게 DISC-SEQ 를 올려야 한다
// (6.2.2 MUST). 렌더는 base 를 그대로 DISC-SEQ 로 적으므로(Render) 빠진 표시 하나마다 base 를 1 올린다.
// 판정은 렌더가 표시를 달 때 부르는 술어(HasDiscontinuityTag) 하나다 — 다른 근거(PDT 점프 · 목록 안 회차
// 경계 · 장부 is_discontinuity)로 세면 올린 수와 빠진 표시 수가 갈린다. 표시가 행의 성질이라 같은 행은
// 어느 목록에서 빠지든 같은 값을 더한다.
//
// prev 는 소유 회차의 지금 base 를 DISC-SEQ 로 적은 직전 목록이고, 새 base = 그 base + 증분이다. prev 에
// 실린 행만 센다 — 목록에 없던 표시(세션 필터가 거른 행 · 두 목록 사이에 창을 지나간 행)는 목록에서 빠질
// 수도 없다. 빠지는 행의 회차가 회차 목록에 없으면 오류다(Render 가 거부하는 입력과 같은 부류). 표시 여부를
// 모르는 행을 건너뛰고 세면 표시를 덜 세어 남은 조각의 번호가 어긋난다.
func EvictedDiscontinuityTags(prev Playlist, nextMSN int64) (int64, error) {
	var n int64
	for _, r := range prev.Rows {
		if r.Seq >= nextMSN {
			break // 여기부터는 다음 목록에 남는다(행은 seq 오름차순 — Playlist)
		}
		s, ok := prev.session(r.SessionID)
		if !ok {
			return 0, fmt.Errorf("rewind: 빠지는 seq %d 의 회차 %q 가 회차 목록에 없다 — 끊김 표시를 셀 수 없다",
				r.Seq, r.SessionID)
		}
		if HasDiscontinuityTag(r.Seq, s, prev.Cutoff) {
			n++
		}
	}
	return n, nil
}
