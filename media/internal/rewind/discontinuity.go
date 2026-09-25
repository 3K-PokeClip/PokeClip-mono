package rewind

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
// 하기 때문이다. 그래서 렌더와 축출 증분(커밋 ⑤)이 이 함수 하나를 부른다 — 둘이 갈리면 축출 뒤
// DISCONTINUITY-SEQUENCE 가 실제 표시 수와 어긋난다. 회차 첫 조각을 컷오프로 자르는 것도 같은
// 이유다: 부팅 재구성은 seq ≥ cutoff 인 행만 다시 싣는다. 컷오프 아래 행으로 판정하면 재기동
// 전후로 같은 행의 판정이 갈려, 이미 나간 목록 머리 앞에 표시가 새로 선다.
func HasDiscontinuityTag(seq int64, s Session, cutoff int64) bool {
	return s.InheritsSession != "" && seq == max(s.MinSeq, cutoff)
}
