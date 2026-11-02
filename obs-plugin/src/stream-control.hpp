#pragma once

#include "app-state.hpp"

#include <string>

namespace pokeclip {

// 우리 SRT 송출을 시작·중단하는 창구. 모두 UI 스레드에서 부른다(출력 객체를 만진다).

// 오류 사유를 상태에 남긴다. 재시도 진행 표시는 지운다.
void SetStreamError(StreamPhase phase, const std::string &code);

// 키와 GOP를 확인하고 SRT 출력을 시작한다. 본방 시작(STREAMING_STARTING)·방송 중 동기화 켜기·「다시 연결」이 같이 쓴다.
// 실패하면 상태에 사유를 남기고 false.
bool StartSrtOutputChecked();

// 「다시 연결」 — 본방은 그대로 두고 우리 송출만 (다시) 시작한다. 본방 시작 때 하는 오디오 재배정·마크 카운터
// 초기화·본방 시작 감시는 하지 않는다 — 방송 도중이라 이미 나가는 것을 건드리면 안 된다.
// 다음 재시도를 기다리던 중이면 기다림만 건너뛴다.
void SendNow();

// 「재시도 중지」 — 자동 재시도를 그만두고 마지막 실패 사유를 남긴다. 그 뒤에야 수신 주소·키를 고칠 수 있다.
void StopRetryNow();

} // namespace pokeclip
