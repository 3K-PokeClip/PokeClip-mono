package upload

import (
	"errors"
	"fmt"
	"testing"
)

// 상태 코드만 주는 오류(awshttp.ResponseError 모양).
type statusErr struct{ code int }

func (e statusErr) Error() string       { return fmt.Sprintf("http %d", e.code) }
func (e statusErr) HTTPStatusCode() int { return e.code }

// 코드 문자열만 주는 오류(smithy.APIError 모양).
type codeErr struct{ code string }

func (e codeErr) Error() string     { return e.code }
func (e codeErr) ErrorCode() string { return e.code }

// ⑬ 전반부 — 분류의 기본값이 soft 인 것이 핵심이다.
// 알 수 없는 오류를 hard 로 오분류하면 브레이커가 전역으로 열려 정상 트래픽까지 막는다(R2).
func TestClassifyPutError(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want putErrClass
	}{
		{"nil", nil, putErrSoft},
		{"미상", errors.New("connection reset by peer"), putErrSoft},
		{"타임아웃", fmt.Errorf("wrapped: %w", errors.New("i/o timeout")), putErrSoft},
		{"500", statusErr{500}, putErrSoft},
		{"401", statusErr{401}, putErrHard},
		{"403", statusErr{403}, putErrHard},
		{"404", statusErr{404}, putErrHard},
		{"감싼_403", fmt.Errorf("put 실패: %w", statusErr{403}), putErrHard},
		{"PermanentRedirect", codeErr{"PermanentRedirect"}, putErrHard},
		{"NoSuchBucket", codeErr{"NoSuchBucket"}, putErrHard},
		{"SlowDown", codeErr{"SlowDown"}, putErrSoft},
	}
	for _, c := range cases {
		if got := classifyPutError(c.err); got != c.want {
			t.Errorf("classifyPutError(%s) = %v, want %v", c.name, got, c.want)
		}
	}
}
