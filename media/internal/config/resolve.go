package config

import (
	"errors"
	"path/filepath"
	"strings"
)

// resolveSettings 는 사이드카의 4C resolve 설정 둘이다(POK-472 설계 2절 「설정 짝 규칙」).
type resolveSettings struct {
	url       string // STREAM_KEY_RESOLVE_URL — auth 주소. 꼴은 main 의 keyresolve.NewClient 가 본다
	tokenFile string // STREAM_KEY_RESOLVE_TOKEN_FILE — 전용 출입증 파일의 절대 경로. 여기서는 열지 않는다
}

// loadResolve 는 STREAM_KEY_RESOLVE_URL · STREAM_KEY_RESOLVE_TOKEN_FILE 을 읽어 짝 규칙과 경로 문자열만 본다.
//
// 둘 다 빈 값이면 4D 수신부를 등록하지 않는다(꺼짐 — 조립이 WARN 을 낸다). 하나만 있으면 기동 실패다 — 조용히 꺼지면
// 4D 가 모두 404 라 신호를 잃는다(설계 7절 P14). 토큰 파일은 열지 않는다 — 읽기는 main 이 secretfile 규칙으로 한다
// (MTX_API_PASS_FILE 과 같은 층 규칙). 값 변수 STREAM_KEY_RESOLVE_TOKEN 은 읽지 않는다 — 사이드카 비밀은 파일로만
// 받는다(env 값은 docker inspect 에 남는다 — ADR-018 · compose.yml.tmpl). 창구 변수도 없다 — 전용 창구 고정이다.
//
// 에러에는 env 이름과 무엇이 틀렸는지만 싣는다 — 주소 · 경로 값은 싣지 않는다.
func loadResolve(env func(string) string) (resolveSettings, error) {
	s := resolveSettings{
		url:       strings.TrimSpace(env("STREAM_KEY_RESOLVE_URL")),
		tokenFile: strings.TrimSpace(env("STREAM_KEY_RESOLVE_TOKEN_FILE")),
	}
	if s.url == "" && s.tokenFile == "" {
		return resolveSettings{}, nil
	}
	if s.url == "" || s.tokenFile == "" {
		return resolveSettings{}, errors.New("STREAM_KEY_RESOLVE_URL 과 STREAM_KEY_RESOLVE_TOKEN_FILE 은 함께 넣거나 함께 비워야 한다 — 둘 다 비면 4D 수신부가 꺼진다")
	}
	if !filepath.IsAbs(s.tokenFile) {
		return resolveSettings{}, errors.New("STREAM_KEY_RESOLVE_TOKEN_FILE 은 컨테이너 안의 절대 경로여야 한다 — 상대 경로는 작업 디렉터리에 따라 다른 파일을 가리킨다")
	}
	return s, nil
}
