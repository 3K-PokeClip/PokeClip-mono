#pragma once

namespace pokeclip {

// 본방(OBS 방송 출력)의 프런트엔드 이벤트로 「이번 STREAMING_STOPPED가 무엇인가」를 가르는 규칙. OBS 없이 시험한다.
// 한 방송은 STREAMING_STARTING에서 시작해(값을 새로 만든다) 처음 처리한 STREAMING_STOPPED에서 끝난다.

// 본방 출력 stop 신호의 성공 코드(OBS_OUTPUT_SUCCESS). plugin-main이 libobs 값과 같은지 컴파일 때 확인한다.
inline constexpr int kMainStopSuccess = 0;

struct MainStreamRun {
	// STREAMING_STARTED를 봤다 — 본방이 실제로 접속했다(본방 출력의 start 신호). 스트림 지연을 켜면 지연 시간 뒤에
	// 온다(지연을 기다리는 동안은 StreamDelayStarting뿐이다).
	bool started = false;
	// STREAMING_STOPPING을 봤다 — obs_output_stop·force_stop 안에서만 온다(OBS 「방송 종료」 버튼·단축키·websocket).
	// 본방이 스스로 끊겨 끝나면 STOPPING 없이 STOPPED만 온다.
	bool stopRequested = false;
	// 이 방송의 STOPPED를 이미 처리했다. 스트림 지연 중 「방송 중지 (지연된 분량 버림)」은 libobs가 본방 stop을 두 번
	// 낸다 — obs_output_actual_stop의 force·지연 가지가 obs_output_end_data_capture로 한 번(그 자리에서), 이어 부른
	// rtmp 출력의 stop이 송신 스레드에서 또 한 번(obs-output.c · rtmp-stream.c, 32.2.1). 두 번째를 처리하면 앞의
	// 판정(조작)을 「조작 아님」으로 덮어 종료 신호가 빠진다(POK-597).
	bool stopHandled = false;
};

enum class MainStopKind {
	Duplicate,      // 같은 방송의 두 번째 STOPPED — 무시한다
	Intentional,    // 스트리머가 본방을 멈췄다 — 우리 송출도 조작으로 멈춘다(종료 신호를 보낸다)
	NotIntentional, // 본방이 스스로 끊겨 끝났다 · 공유 인코더 실패 — 같이 멈추되 종료 신호는 보내지 않는다
	// 본방이 한 번도 접속하지 못하고 오류로 끝났다(POK-596). 연결 거부·없는 호스트처럼 접속 스레드에서 난 실패는
	// obs_output_start가 이미 받아들인 뒤라 그 자리 실패 감시(plugin-main WatchMainStreamStart)를 지나고, STOPPING 없이
	// STOPPED(예: CONNECT_FAILED)만 온다. 우리 송출도 main_stream_failed 사유로 멈춘다(종료 신호는 보내지 않는다).
	FailedToStart,
};

// STREAMING_STOPPED 하나를 가른다. mainStopCode는 이번 방송에서 본방 출력 stop 신호가 준 code, hasOutput은 지금 우리
// SRT 출력이 있는가. 출력이 있으면 STOPPING을 조작으로 본다 — 공유 인코더 실패는 그 출력의 stopping 스레드로 따로
// 걸러진다(StreamTarget). 출력이 없으면(재시도 대기·「재시도 중지」 뒤) 본방 stop 코드가 성공일 때만 조작이다.
MainStopKind ClassifyMainStop(const MainStreamRun &run, int mainStopCode, bool hasOutput);
// STREAMING_STOPPED를 받았을 때 — 가른 뒤, 중복이 아니면 이 방송의 STOPPED를 처리한 것으로 적는다.
MainStopKind HandleMainStopped(MainStreamRun &run, int mainStopCode, bool hasOutput);
const char *MainStopKindName(MainStopKind kind);

} // namespace pokeclip
