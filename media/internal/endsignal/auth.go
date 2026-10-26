package endsignal

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strings"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// derivationMessage 는 4D 파생 값의 HMAC 메시지다(계약4 3-1 — ASCII "pokeclip-4d-v1").
const derivationMessage = "pokeclip-4d-v1"

// bearerHexLen 은 파생 값의 길이다 — HMAC-SHA256 32바이트의 16진 64자.
const bearerHexLen = 2 * sha256.Size

// parseBearer 는 Authorization 값에서 파생 값을 꺼낸다. 스킴 Bearer(대소문자 무관 — RFC 9110 11.1) 뒤 공백 하나 ·
// 16진 64자(대소문자 무관)만 받고 소문자로 돌려준다. 그 밖은 거짓이다.
func parseBearer(header string) (string, bool) {
	scheme, value, found := strings.Cut(header, " ")
	if !found || !strings.EqualFold(scheme, "Bearer") || len(value) != bearerHexLen {
		return "", false
	}
	if _, err := hex.DecodeString(value); err != nil {
		return "", false
	}
	return strings.ToLower(value), true
}

// verifyBearer 는 bearer(소문자 16진 64자)가 passphrase 의 파생 값 HMAC-SHA256(passphrase, derivationMessage) 와
// 같은지 본다. 대조는 상수 시간(hmac.Equal)이다. passphrase 원문과 계산한 파생 값은 이 함수 밖으로 나가지 않는다.
func verifyBearer(passphrase streamkey.Passphrase, bearer string) bool {
	mac := hmac.New(sha256.New, []byte(passphrase.Reveal()))
	mac.Write([]byte(derivationMessage))
	want := hex.EncodeToString(mac.Sum(nil))
	return hmac.Equal([]byte(want), []byte(bearer))
}
