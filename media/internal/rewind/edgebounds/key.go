package edgebounds

// FloorKey 는 KVS 키 streamID + "/" + roundID 다 — 함수와 공유 골든으로 맞춘다.
//
// 엣지 함수는 경로 /dvr/{streamID}/{roundID}/seg/{seq}.m4s 의 두 성분을 그대로 이어 이 키를 묻는다(설계 4-2). 성분은
// 다시 검사하지 않는다 — 원장의 회차는 ③ 조각 키 문법(playback.ParseSegKey — URL 안전 문자 · 128자 이하)을 통과한
// 키에서 왔다(Observe 는 목록 마지막 행 키를 풀어 부른다). 키에는 `/` 가 꼭 하나라 ReleaseMarkerKey 와 겹치지 않는다.
func FloorKey(streamID, roundID string) string {
	return streamID + "/" + roundID
}
