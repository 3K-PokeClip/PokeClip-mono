package index

// Axis 는 업로드 축이다 — 같은 방송의 바이트를 서로 다른 목적으로 올리는 세 경로를 가른다
// (설계 5.5.1). ② 아카이브는 클립 소재, ③ 재생은 되감기 렌디션, init 은 세션의 MAP 이다.
//
// **집이 index 인 이유**: 현행 의존 방향이 upload → index 다(worker.go 가 index 를 임포트한다).
// 축은 장부의 열·키·CAS 를 가르는 값이라 장부 쪽에 두는 것이 그 방향과 맞는다.
// 대안(leaf 패키지 axis 신설)은 소비자가 둘뿐이라 과설계로 기각됐다.
type Axis uint8

// 값이 1 부터인 이유는 **영값을 유효한 축으로 만들지 않기 위해서다**(SessionOp 와 같은 이디엄).
// 축을 안 채운 대상이 조용히 ② 로 접히면 ③ 의 결과가 아카이브 열을 바꾼다 —
// 그래서 미판정 축은 업로더가 거부한다(fail-closed).
const (
	// AxisArchive 는 ② 클립 소재 축이다. streams/… 키와 stream_segments.upload_state 를 쓴다.
	AxisArchive Axis = iota + 1
	// AxisPlayback 은 ③ 되감기 렌디션 축이다. dvr/…/seg/… 키와 playback_upload_state 를 쓴다.
	AxisPlayback
	// AxisInit 은 세션 init(MAP) 축이다. dvr/…/init/… 키와 stream_sessions.init_uploaded_at 을 쓴다.
	AxisInit
)

// String 은 로그·메트릭의 axis 라벨 값이다(설계 5.5.4 #8).
// 미판정 축이 "unknown" 으로 드러나는 것이 계약이다 — 라벨이 비면 누락이 안 보인다.
func (a Axis) String() string {
	switch a {
	case AxisArchive:
		return "archive"
	case AxisPlayback:
		return "playback"
	case AxisInit:
		return "init"
	default:
		return "unknown"
	}
}
