package credrelay

import "time"

// 이름 붙인 상수 전부(설계 2.5 · 계획 E14). 상수 사이의 관계는 consts_test.go 가 단언한다 — consumerRefreshCap
// 는 소비자 SDK 캐시 창(5분)의 6배 이상, periodicReread < consumerRefreshCap, serveFloor < refreshWindow,
// sourceDeadline < writeTimeout, sourceDeadline < minSourceInterval, writeTimeout ≤ ShutdownGrace.
const (
	// consumerRefreshCap 은 소비자가 다시 물을 시각의 상한이다. 응답 Expiration 은 응답할 때마다
	// min(실제 만료, 응답 시각 + 이 값)이다 — 철회 · 교대 뒤 소비자가 옛 키에 묶이는 응답 한 벌의 길이를 묶는다.
	consumerRefreshCap = 60 * time.Minute
	// refreshWindow 는 실제 만료까지 이만큼 남으면 원천을 다시 읽는 창이다(IAM 「만료 15분 전」).
	refreshWindow = 15 * time.Minute
	// periodicReread 는 마지막 성공 뒤 이만큼 지나면 원천을 다시 읽는 주기다 — 첫 자격을 만료 직전까지 붙들다가
	// 마지막 창의 원천 장애에 걸리지 않게 한다(Kotlin SDK #2131 기제).
	periodicReread = 30 * time.Minute
	// minSourceInterval 은 원천 호출 사이의 최소 간격이다. 성공 · 실패와 무관하다 — 15분 창 안에서 IMDS 가
	// 같은 자격을 돌려줘도 요청마다 IMDS 를 부르지 않는다.
	minSourceInterval = 10 * time.Second
	// serveFloor 는 실제 만료까지 이만큼도 안 남은 자격은 주지 않는 하한이다(503).
	serveFloor = 1 * time.Minute
	// sourceDeadline 은 원천 호출 하나(토큰 PUT + GET 둘 + 재시도)의 전체 상한이다. 쓰기 상한보다 짧아야 원천이
	// 늦어도 소비자가 끊긴 연결이 아니라 503 · 200 을 받는다.
	sourceDeadline = 2 * time.Second

	// readHeaderTimeout 은 요청 머리를 다 받기까지의 상한이다(인증 앞이라 누구든 연결을 잡을 수 있다).
	readHeaderTimeout = 1 * time.Second
	// writeTimeout 은 머리를 읽은 뒤 응답을 다 쓰기까지의 상한이다.
	writeTimeout = 3 * time.Second
	// maxHeaderBytes 는 요청 머리 크기의 상한이다(SDK 의 머리는 수백 바이트).
	maxHeaderBytes = 8 << 10

	// denyLogInterval 은 거부 줄(cred_denied) 사이의 최소 간격이다 — 401 폭주가 저널(docker.service 단위
	// 속도 제한)을 채워 사이드카 로그까지 버려지게 하지 않는다. 그사이 거부 수는 다음 줄의 count 에 싣는다.
	denyLogInterval = 10 * time.Second
)

// ShutdownGrace 는 종료 신호 뒤 처리 중 요청을 기다리는 상한이다(ingest-auth 와 같은 3초 — 쓰기 상한 이상).
const ShutdownGrace = 3 * time.Second

// 중계의 HTTP 경로다. 소비자 SDK 는 AWS_CONTAINER_CREDENTIALS_FULL_URI 로 CredentialsPath 를 묻는다.
const (
	CredentialsPath = "/v1/credentials"
	HealthPath      = "/healthz"
)
