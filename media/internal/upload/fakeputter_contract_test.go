package upload

import (
	"context"
	"reflect"
	"strings"
	"testing"

	"github.com/aws/aws-sdk-go-v2/service/s3"
)

// 계획 6.5 ⑵ — S3 LIST 금지(프로필 4절)의 동적 그물, 업로더 쪽이다(발행 쪽은 rewind/publish 의
// TestFakeStoreFailsTestOnListCalls). Putter 에는 목록 연산이 없어서 Putter 를 든 코드가 목록에 닿는 길은 SDK 목록
// 인터페이스로의 형 단언뿐이다. 가짜는 *s3.Client 의 목록 연산 전부(이름이 List 로 시작하는 메서드 — 발행 쪽 정적 단언과
// 같은 집합)를 같은 시그니처로 가져 어느 형 단언이든 성립하고, 불리면 패키지 기록에 적으며 오류를 돌려준다. TestMain 이 그
// 기록으로 패키지를 실패로 바꾼다 — 여기서는 함정마다 기록을 꺼내 비운다.
func TestFakePutterTrapsListCalls(t *testing.T) {
	client := reflect.ValueOf((*s3.Client)(nil))
	var p Putter = &fakePutter{}
	for i := range client.NumMethod() {
		name := client.Type().Method(i).Name
		if !strings.HasPrefix(name, "List") {
			continue
		}
		t.Run(name, func(t *testing.T) {
			want := client.Method(i).Type()
			trap := reflect.ValueOf(p).MethodByName(name)
			if !trap.IsValid() || trap.Type() != want {
				t.Fatalf("가짜에 %s 함정(%v)이 없다 — 형 단언이 실패해 목록 호출이 그물을 빠져나간다", name, want)
			}
			// 함정은 인자를 보지 않는다 — ctx 와 빈 입력만 넘긴다.
			out := trap.Call([]reflect.Value{reflect.ValueOf(context.Background()), reflect.Zero(want.In(1))})
			if err, _ := out[1].Interface().(error); err == nil {
				t.Errorf("%s 결과 오류 = nil, want 목록 금지 오류", name)
			}
			if calls := takeListTrapCalls(); len(calls) != 1 || !strings.HasPrefix(calls[0], name+"\n") {
				t.Errorf("%s 의 패키지 기록 = %d건, want 1건(%s)", name, len(calls), name)
			}
		})
	}
}
