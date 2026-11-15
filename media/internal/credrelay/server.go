package credrelay

import "net/http"

// NewServer 는 h 를 중계의 서버 상한(머리 1초 · 쓰기 3초 · 머리 8 KiB · keep-alive 끔)으로 싼 http.Server 다.
// keep-alive 를 끄는 것은 소비자 SDK 가 시간 단위로 한 번 묻는 짧은 요청이라 연결을 다시 쓸 일이 없고, 열린
// 연결을 남기지 않으려는 것이다. 서버 자신의 로그(ErrorLog)는 부르는 쪽이 정한다.
func NewServer(addr string, h http.Handler) *http.Server {
	srv := &http.Server{
		Addr:              addr,
		Handler:           h,
		ReadHeaderTimeout: readHeaderTimeout,
		WriteTimeout:      writeTimeout,
		MaxHeaderBytes:    maxHeaderBytes,
	}
	srv.SetKeepAlivesEnabled(false)
	return srv
}
