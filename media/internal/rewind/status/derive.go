package status

// Derive 는 스트림 하나의 입력을 응답으로 옮긴다(4.1절 표 — 위에서부터 처음 맞는 행이 이긴다). 거짓이면 404 다. 503 은
// Derive 밖(Board · 처리기)에서만 정한다.
//
//	1  소유 회차 live ∧ init 확정 ∧ 발행 판 있음 → ready:true · mode:live · url · 범위 = 발행 판
//	2  소유 회차 live                            → ready:false · mode:live
//	3  적재 중                                   → ready:false · mode:null
//	4  캐시가 아는데 컷오프 있는 뷰가 없음          → ready:false · mode:null(컷오프 없는 뷰 · 부정 표식)
//	5  종료 항목 ∧ 같은 회차                       → 종료 항목의 Status(ending · vod · ended)
//	6  소유 회차 ending · ended                    → ready:false · mode:ending
//	7  캐시가 앎(뷰는 있는데 소유 회차 · 종료 항목 없음) → ready:false · mode:null
//	8  그 밖                                     → 거짓(404)
//
// 3 · 4 가 5 보다 앞인 까닭: Forget 뒤 캐시 항목은 새 INSERT push 로만 다시 생긴다 — 새 송출의 표지다(R38).
// 「같은 회차」는 소유 회차가 없거나 종료 항목의 최신 세션이 소유 회차인 것이다 — 캐시가 더 새 회차를 든 동안에는
// 지난 회차의 종료 항목을 내지 않는다(경계 확정 1 이 M5 안에 남긴 방어).
func Derive(in Input) (Status, bool) {
	o := in.Owner
	switch {
	case o != nil && o.State == "live" && o.InitUploaded && in.Published != nil:
		p := in.Published
		return Status{Ready: true, Mode: ModeLive, URL: p.URL, FirstPDT: p.First, HeadPDT: p.Head}, true
	case o != nil && o.State == "live":
		return Status{Mode: ModeLive}, true
	case in.Loading:
		return Status{}, true
	case in.CacheKnown && !in.HasView:
		return Status{}, true
	case in.Final != nil && (o == nil || in.Final.LatestSessionID == o.SessionID):
		return in.Final.Status, true
	case o != nil && (o.State == "ending" || o.State == "ended"):
		return Status{Mode: ModeEnding}, true
	case in.CacheKnown:
		return Status{}, true
	}
	return Status{}, false
}
