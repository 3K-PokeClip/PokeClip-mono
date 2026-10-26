package config

import (
	"errors"
	"net/url"
	"path/filepath"
	"strings"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/expiry"
)

// ExpiryOptions 는 60일 만료(POK-196 M6-4)의 설정이다(설계 M6 7절 prod 연결).
type ExpiryOptions struct {
	// ClipURL 은 CLIP_INTERNAL_URL 이다 — clip 내부 주소(scheme://host[:port] · 경로 · 질의 · 자격증명 없음). 빈 값이면
	// 만료를 켜지 않는다(롤백 스위치 — withDefault 를 쓰지 않는다).
	ClipURL string
	// Token · HasToken 은 CLIP_EXPIRY_TOKEN(값)이다. 원문은 expiry.Token 이 감싸 어느 출력에도 나오지 않는다.
	Token    expiry.Token
	HasToken bool
	// TokenFile 은 CLIP_EXPIRY_TOKEN_FILE 이다 — 토큰 한 줄이 든 파일의 절대 경로. 여기서는 열지 않는다(읽기는 main 이
	// secretfile 규칙으로 — MTX_API_PASS_FILE 과 같은 층 규칙). 못 읽으면 기동 실패가 아니라 만료만 끈다(ERROR
	// expiry_disabled reason=token_file — kty 결정 2026-10-27 강등 유지 · ADR-063 결정 1: 인덱싱 · 업로드는 그대로).
	TokenFile string
}

// loadExpiry 는 만료 설정 셋을 읽고 문자열 규칙만 본다. 주소가 비어도 나머지 값은 본다(꺼져도 값은 본다 — 오타를 켜는
// 날에야 알지 않게). 에러에는 env 이름과 무엇이 틀렸는지만 싣는다 — 토큰 · 경로 값은 싣지 않는다.
//
// 토큰은 값(CLIP_EXPIRY_TOKEN)과 파일(CLIP_EXPIRY_TOKEN_FILE) 둘 다 받는다(설계 7절 Z2 — prod 는 파일만 공급한다). 둘
// 다 넣으면 어느 쪽이 쓰이는지 모호하니 거부한다. 주소와 토큰의 짝은 보지 않는다 — 토큰 없이 켜면 clip 이 401 로
// 답하고 expiry_stalled 가 드러낸다.
func loadExpiry(env func(string) string) (ExpiryOptions, error) {
	o := ExpiryOptions{
		ClipURL:   strings.TrimSpace(env("CLIP_INTERNAL_URL")),
		TokenFile: strings.TrimSpace(env("CLIP_EXPIRY_TOKEN_FILE")),
	}
	if err := validateClipURL(o.ClipURL); err != nil {
		return ExpiryOptions{}, err
	}
	if raw := strings.TrimSpace(env("CLIP_EXPIRY_TOKEN")); raw != "" {
		o.Token, o.HasToken = expiry.NewToken(raw), true
	}
	if o.HasToken && o.TokenFile != "" {
		return ExpiryOptions{}, errors.New("CLIP_EXPIRY_TOKEN 과 CLIP_EXPIRY_TOKEN_FILE 은 하나만 넣는다 — 둘 다면 어느 쪽을 쓸지 모호하다")
	}
	if o.TokenFile != "" && !filepath.IsAbs(o.TokenFile) {
		return ExpiryOptions{}, errors.New("CLIP_EXPIRY_TOKEN_FILE 은 컨테이너 안의 절대 경로여야 한다")
	}
	return o, nil
}

// validateClipURL 은 값이 http · https 의 scheme://host[:port] 하나인가다. 만료기는 이 값 뒤에 창구 경로를 붙이므로 끝
// '/' · 경로 · 질의 · 조각 · 자격증명을 받지 않는다. 빈 값(끔)은 통과다.
func validateClipURL(raw string) error {
	if raw == "" {
		return nil
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil ||
		u.Path != "" || u.RawQuery != "" || u.Fragment != "" || u.Opaque != "" {
		return errors.New("CLIP_INTERNAL_URL 은 http(s)://host[:port] 꼴이어야 한다 — 경로 · 끝 '/' · 질의 · 자격증명 없이")
	}
	return nil
}
