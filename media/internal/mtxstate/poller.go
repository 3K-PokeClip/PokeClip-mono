package mtxstate

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// DefaultPollInterval 은 OBS_POLL 의 기본값이다(설계 5.4.1).
	// **이 값이 유일한 집이다** — config 가 리터럴로 10s 를 또 적으면 두 곳이 언젠가 어긋난다.
	DefaultPollInterval = 10 * time.Second
	// DefaultBootWait 은 OBS_BOOT_WAIT 의 기본값이다(설계 6.5.3 ⑶).
	// 기동을 3 초까지만 늦춘다 — 그 창에 들어온 파일은 초기 수집이 회수한다.
	DefaultBootWait = 3 * time.Second

	// pathsListPath 는 관측에 쓰는 유일한 엔드포인트다(F-34 실측으로 확정).
	pathsListPath = "/v3/paths/list"

	// 폴 실패 사유 라벨. **셋이다** — 늘리는 기준은 「운영자가 할 일이 다른가」 하나다(사유를
	// 늘리면 대시보드가 갈라진다). request_failed · multi_page 는 다음 주기가 고칠 수 있는 일시
	// 실패라 WARN, auth_rejected 는 사람이 자격 설정을 고쳐야만 낫는 설정 고장이라 ERROR 다.
	reasonRequestFailed = "request_failed" // 요청·상태코드(401 제외)·파싱 실패가 모두 여기로 합류한다
	reasonMultiPage     = "multi_page"     // 응답이 여러 페이지라 원자 스냅샷이 아니다
	reasonAuthRejected  = "auth_rejected"  // Control API 가 401 로 자격을 거부했다

	// 자격 방식 — 기동 로그 · 폴 실패 로그의 auth 속성 값이다(AuthScheme).
	authBasic = "basic"
	authNone  = "none"
)

// errAuthRejected 는 Control API 가 401 로 답한 폴의 에러다. 응답 본문은 싣지 않는다 — 고칠 곳은
// 본문이 아니라 자격 설정이고, MediaMTX 의 401 본문은 언제나 같은 문구다.
var errAuthRejected = errors.New("HTTP 401 — Control API 가 자격을 거부했다. MTX_API_USER · MTX_API_PASS_FILE 과 MediaMTX authInternalUsers 의 사용자를 맞춘다")

// Options 는 폴러의 설정이다. 값의 출처는 config(env 8종 중 5종)이고, Pass 만은 main 이 파일에서
// 읽어 채운다(config 는 파일을 열지 않는다).
type Options struct {
	// APIURL 은 MediaMTX Control API 의 베이스 URL 이다(예: http://media:9997).
	//
	// **빈 값이면 폴러를 아예 만들지 않는다**(NewPoller 가 거부한다). 그것이 즉시 롤백
	// 스위치다 — 관측이 사라지면 ⓐ2 가 fail-closed 되어 스캔 유입(ⓐ2) 주조가 멈추고,
	// 신선한 관측을 요구하는 4.1 종료 전이(M4 ⓒ)도 서지 않아 같은 스트림의 연속 방송이
	// 한 회차로 이어진다. 인덱싱은 그대로다.
	// HOOK_SPOOL_PATH 와 같은 형태의 스위치이며, 그래서 config 도 기본값을 두지 않는다.
	APIURL string
	// PollInterval 은 폴 주기이자 **poll 1 회의 상한**이다(OBS_POLL).
	// 두 뜻을 한 값이 지는 이유: 주기가 곧 재시도다. 헤더조차 오지 않는 응답에서
	// 고루틴이 영구 정지하면 스냅샷이 영원히 낡은 채 회복 경로가 사라진다.
	// 0 이면 DefaultPollInterval.
	PollInterval time.Duration
	// BootWait 은 WaitFirstObservation 이 첫 관측을 기다리는 상한이다(OBS_BOOT_WAIT).
	// 0 이면 DefaultBootWait.
	BootWait time.Duration
	// User 는 Control API 의 Basic 자격 사용자 이름이다(MTX_API_USER). 빈 값이면 자격을 싣지 않는다 —
	// MediaMTX 의 `user: any` 항목으로 붙는 무인증 구성(dev compose)이다. ':' 를 담을 수 없다(Basic 은
	// 첫 ':' 에서 사용자와 비밀번호를 가른다 — 검사는 config 가 한다).
	User string
	// Pass 는 그 비밀번호다(MTX_API_PASS_FILE 의 내용). User 와 짝이다 — 한쪽만이면 NewPoller 가 거부한다.
	// Secret 이라 Options 를 어디로 찍어도 원문이 나오지 않는다.
	Pass Secret `json:"-"`
	Log  *slog.Logger
}

// Poller 는 Control API 를 주기적으로 읽어 스냅샷을 갈아 끼운다.
//
// 폴은 **직렬**이다(고루틴 하나 + 티커). 그래서 스냅샷 교체가 poll 시작 시각 기준으로
// 자연히 단조가 되고, 늦게 끝난 옛 poll 이 새 스냅샷을 덮는 국면 자체가 없다 —
// 별도 비교 가드를 두지 않는 이유가 이것이다(도달 불가 분기를 만들지 않는다).
type Poller struct {
	opt    Options
	client *http.Client
	// authorize 는 요청에 자격을 싣는다(자격이 없으면 아무것도 하지 않는다). 원문은 이 클로저 안에만
	// 있어서 Poller 를 어떻게 찍어도 나오지 않는다(mtxctl.Client 와 같은 꼴).
	authorize func(*http.Request)

	// snap 은 마지막으로 성공한 poll 의 스냅샷이다. nil 이면 관측 이력 0 이다.
	snap atomic.Pointer[snapshot]

	// first 는 첫 성공 스냅샷에서 닫힌다. WaitFirstObservation 이 이것만 본다.
	first     chan struct{}
	firstOnce sync.Once
	done      chan struct{}
}

// NewPoller 는 폴러를 만든다. 설정이 잘못됐으면 기동 시점에 실패시킨다.
//
// User 와 Pass 가 짝이 아니면 거부한다 — User 만이면 빈 비밀번호로 Basic 을 싣고, Pass 만이면 비밀번호가
// 조용히 버려진다. main 경로는 config 가 먼저 막으므로 여기 닿지 않지만, config 를 거치지 않는 조립에
// 대한 회선 패키지의 보장이다(mtxctl NewClient 와 같은 자리). 에러에는 자격 값을 싣지 않는다.
func NewPoller(opt Options) (*Poller, error) {
	if strings.TrimSpace(opt.APIURL) == "" {
		return nil, errors.New("MediaMTX Control API URL 이 비었다 — 빈 값이면 폴러를 아예 만들지 않아야 한다")
	}
	hasUser, hasPass := opt.User != "", opt.Pass.reveal() != ""
	if hasUser != hasPass {
		return nil, errors.New("MediaMTX Control API 자격은 User 와 Pass 를 함께 넣거나 함께 비워야 한다")
	}
	if opt.PollInterval <= 0 {
		opt.PollInterval = DefaultPollInterval
	}
	if opt.BootWait <= 0 {
		opt.BootWait = DefaultBootWait
	}
	if opt.Log == nil {
		opt.Log = slog.Default()
	}
	opt.APIURL = strings.TrimSuffix(strings.TrimSpace(opt.APIURL), "/")
	authorize := func(*http.Request) {}
	if hasUser {
		user, pass := opt.User, opt.Pass.reveal()
		authorize = func(r *http.Request) { r.SetBasicAuth(user, pass) }
	}
	return &Poller{
		opt:       opt,
		client:    newHTTPClient(),
		authorize: authorize,
		first:     make(chan struct{}),
		done:      make(chan struct{}),
	}, nil
}

// newHTTPClient 는 폴러의 HTTP 클라이언트다. 자격이 있든 없든 같은 꼴을 쓴다 — 꼴이 하나면 시험 행렬도
// 하나다(compose 는 프록시 env 를 넘기지 않고 /v3/paths/list 에는 리다이렉트가 없어 무자격 관측의 동작은
// 그대로다).
//
//   - 전송은 http.DefaultTransport 의 복제본이다 — 빈 Transport 는 다이얼 · TLS 악수 · 유휴 연결 상한을
//     통째로 잃는다. 복제본에서 Proxy 만 끈다: 환경 변수의 프록시를 타지 않아 Basic 자격이 프록시를 거치지
//     않는다(응답기 Control API 클라이언트 mtxctl 과 같은 규칙).
//   - 리다이렉트를 따르지 않는다. 따라가면 Go 는 같은 호스트에 Authorization 을 다시 싣는다. 3xx 는 200 이
//     아니라 request_failed 다.
//   - 클라이언트 타임아웃을 두지 않는다 — 상한은 poll 마다 거는 컨텍스트가 지고, 두 곳에 상한이 있으면
//     어느 쪽이 끊었는지 로그로 구분되지 않는다.
func newHTTPClient() *http.Client {
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.Proxy = nil
	return &http.Client{
		Transport: tr,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

// AuthScheme 은 폴러가 요청에 싣는 자격 방식이다 — "basic"(User · Pass 있음) 또는 "none"(무인증).
// 기동 로그 · 폴 실패 로그의 auth 속성이며, 비밀번호와 무관한 값이라 그대로 찍어도 된다.
func (p *Poller) AuthScheme() string {
	if p.opt.User == "" {
		return authNone
	}
	return authBasic
}

// Start 는 폴 고루틴을 띄우고 즉시 반환한다.
//
// **첫 폴을 티커 전에 한 번 돌린다.** 기다렸다 돌리면 BootWait(3초)이 PollInterval(10초)
// 보다 짧아 첫 관측이 언제나 타임아웃한다.
func (p *Poller) Start(ctx context.Context) {
	go p.run(ctx)
}

// Done 은 폴 고루틴이 끝나면 닫힌다.
func (p *Poller) Done() <-chan struct{} { return p.done }

// WaitFirstObservation 은 첫 성공 관측을 BootWait 까지 기다린다(설계 6.5.3 ⑶ 2단계).
//
// 타임아웃이면 거짓을 돌려주고 **기동은 그대로 계속된다** — 관측 없이 뜬 상태는
// EpochKnown=false 라 ⓐ2 가 fail-closed 될 뿐이고, 그 창의 파일은 초기 수집이 회수한다.
// 상한을 인자로 받지 않는 이유: 값의 집을 Options 하나로 둔다(config 가 채운다).
func (p *Poller) WaitFirstObservation(ctx context.Context) bool {
	t := time.NewTimer(p.opt.BootWait)
	defer t.Stop()
	select {
	case <-p.first:
		return true
	case <-t.C:
		return false
	case <-ctx.Done():
		return false
	}
}

func (p *Poller) run(ctx context.Context) {
	defer close(p.done)

	p.pollOnce(ctx)

	t := time.NewTicker(p.opt.PollInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			p.pollOnce(ctx)
		}
	}
}

// pollOnce 는 목록을 한 번 읽어 성공했을 때만 스냅샷을 갈아 끼운다.
//
// 실패는 에러로 올리지 않는다 — 관측 고장은 강등이지 사망이 아니고(설계 S3),
// 다음 주기가 곧 재시도다. 대신 반드시 신호를 남긴다(무징후 금지 — 설계 S4).
func (p *Poller) pollOnce(ctx context.Context) {
	// ObservedAt 은 poll **시작** 시각이다. 완료 시각을 쓰면 왕복 시간만큼 관측이
	// 실제보다 신선해 보여 판정 창 공시(90초)와 어긋난다.
	observedAt := time.Now().UTC()

	reqCtx, cancel := context.WithTimeout(ctx, p.opt.PollInterval)
	defer cancel()

	list, err := p.fetch(reqCtx)
	if err != nil {
		// 정상 종료(부모 ctx 취소)로 끊긴 요청은 고장이 아니다 — 내릴 때마다 WARN 이
		// 하나씩 쌓이면 진짜 폴 실패와 구분이 사라진다(위양성). 폴 상한 만료는 여기
		// 해당하지 않는다: 부모는 살아 있고 reqCtx 만 끝났으므로 그대로 신호한다.
		if ctx.Err() != nil {
			return
		}
		reason := reasonRequestFailed
		if errors.Is(err, errAuthRejected) {
			reason = reasonAuthRejected
		}
		p.pollFailed(ctx, reason, err)
		return
	}
	// pageCount ≤ 1 인 응답만 서버 시점의 원자 스냅샷이다(0 = 빈 목록도 정상).
	// 여러 페이지는 **그 poll 을 통째로 버린다** — 이어 붙이면 페이지 사이의 삭제·추가가
	// itemCount·pageCount 를 그대로 둔 채 목록만 어긋나게 만든다(계획 3절 ⑴).
	if list.PageCount > 1 {
		p.pollFailed(ctx, reasonMultiPage, fmt.Errorf("pageCount=%d (원자 스냅샷이 아니다)", list.PageCount))
		return
	}

	p.snap.Store(newSnapshot(observedAt, list.Items))
	p.firstOnce.Do(func() { close(p.first) })
}

// pollFailed 는 스냅샷을 건드리지 않고 신호만 남긴다.
//
// **직전 스냅샷을 유지하는 것이 계약이다**(계획 3절 ⑴): 성공 이력이 있으면 그 관측이
// OBS_FRESH 로 자연 만료되게 두고, 이력이 없으면 애초에 영값이라 EpochKnown=false 다.
// 실패했다는 이유로 스냅샷을 비우면 30초짜리 유예가 사라져 순간 장애가 곧 ⓐ2(스캔 유입)
// 주조 중단이 된다(ⓐ1 워처·훅 유입은 관측과 무관하다).
//
// 등급은 사유가 정한다. request_failed · multi_page 는 WARN 이다 — 프로세스는 살아 있고 방향은
// 비주조(안전)이며 다음 주기가 회복시킨다. auth_rejected 는 ERROR 다 — 다음 주기도 같은 자격으로
// 같은 401 을 받으므로 사람이 자격 설정을 고치기 전에는 낫지 않는다.
// 모든 줄에 auth(basic | none)를 싣는다 — 401 을 본 운영자가 「자격을 싣기는 했나」 부터 가를 수 있게.
func (p *Poller) pollFailed(ctx context.Context, reason string, err error) {
	level := slog.LevelWarn
	if reason == reasonAuthRejected {
		level = slog.LevelError
	}
	p.opt.Log.Log(ctx, level, "mtxstate_poll_failed",
		"reason", reason, "auth", p.AuthScheme(), "url", p.opt.APIURL, "err", err,
		"note", "직전 관측을 유지한다. 만료되면 ⓐ2 가 fail-closed 된다")
}

// newSnapshot 은 응답 항목을 조회용 형태로 접는다.
func newSnapshot(observedAt time.Time, items []apiPath) *snapshot {
	m := make(map[string]pathState, len(items))
	for _, it := range items {
		st := pathState{publishing: it.publishing()}
		if it.OnlineTime != nil {
			st.onlineTime = it.OnlineTime.UTC()
		}
		m[it.Name] = st
	}
	return &snapshot{observedAt: observedAt, items: m}
}

// maxBodyBytes 는 읽어 들일 응답 본문의 상한이다.
// 경로 100개 × 넉넉한 항목 크기를 덮으면서, 오구성으로 엉뚱한 서버를 가리켰을 때
// 메모리를 무한정 먹지 않게 한다.
const maxBodyBytes = 4 << 20

// fetch 는 목록을 한 번 읽어 파싱한다. 경계 검증은 전부 여기서 한다.
func (p *Poller) fetch(ctx context.Context) (pathList, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.opt.APIURL+pathsListPath, nil)
	if err != nil {
		return pathList{}, err
	}
	// 자격은 요청마다 싣는다 — 요청은 poll 마다 새로 만든다.
	p.authorize(req)
	resp, err := p.client.Do(req)
	if err != nil {
		return pathList{}, err
	}
	defer resp.Body.Close()

	// 401 은 본문을 읽지 않고 가른다. MediaMTX 의 관리 API 인증 실패는 언제나 401 이고(403 은 이 경로에서
	// 나오지 않는다 — 그래서 403 은 묶지 않고 아래 일반 갈래로 본문과 함께 보인다), 고칠 곳은 자격 설정이다.
	if resp.StatusCode == http.StatusUnauthorized {
		return pathList{}, errAuthRejected
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes))
	if err != nil {
		return pathList{}, err
	}
	if resp.StatusCode != http.StatusOK {
		// 401 을 뺀 비정상 상태(5xx · 404 · 3xx 등)가 여기로 온다.
		// 본문을 진단에 싣되 길이를 자른다 — 통째로 실으면 로그가 응답으로 도배된다.
		return pathList{}, fmt.Errorf("HTTP %d: %s", resp.StatusCode, truncate(string(body), 200))
	}
	var list pathList
	if err := json.Unmarshal(body, &list); err != nil {
		return pathList{}, fmt.Errorf("응답 파싱 실패: %w", err)
	}
	return list, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

// pathList 는 GET /v3/paths/list 응답이다(F-34 실측 스키마).
type pathList struct {
	// itemCount 는 읽지 않는다 — 수량 검사는 계획 3절 ⑴ 에서 철회했고(원자성 판정은
	// pageCount 하나로 한다), 안 읽는 필드를 두면 "검사한다"는 오해만 남는다.
	PageCount int       `json:"pageCount"`
	Items     []apiPath `json:"items"`
}

// apiPath 는 항목 중 **우리가 쓰는 필드만** 담는다.
//
// ready/readyTime 은 실측상 값이 사실상 같지만 포크 소스에서 deprecated 태그라 읽지 않는다
// (폴백 갈래를 두지 않는다 — 두면 어느 쌍으로 판정했는지 로그로 구분되지 않는다).
type apiPath struct {
	Name       string     `json:"name"`
	Online     bool       `json:"online"`
	OnlineTime *time.Time `json:"onlineTime"`
	// Source 는 존재 여부만 본다. 타입까지 열면 새 소스 종류가 생길 때마다 여기가 바뀐다.
	Source json.RawMessage `json:"source"`
}

// publishing 은 설계가 정한 3항 결합이다 — 하나라도 빠지면 거짓이다.
func (a apiPath) publishing() bool {
	return a.Online && sourcePresent(a.Source)
}

// sourcePresent 는 source 가 실제 객체인지 본다.
// 필드 자체가 없으면 nil 이고, 있는데 비어 있으면 리터럴 null 이 온다 — 둘 다 부재다.
func sourcePresent(raw json.RawMessage) bool {
	trimmed := bytes.TrimSpace(raw)
	return len(trimmed) > 0 && !bytes.Equal(trimmed, []byte("null"))
}
