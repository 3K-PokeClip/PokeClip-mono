#include "main-stream-policy.hpp"

namespace pokeclip {

MainStopKind ClassifyMainStop(const MainStreamRun &run, int mainStopCode, bool hasOutput)
{
	if (run.stopHandled)
		return MainStopKind::Duplicate;
	if (run.stopRequested)
		return (hasOutput || mainStopCode == kMainStopSuccess) ? MainStopKind::Intentional
								       : MainStopKind::NotIntentional;
	// STOPPING 없이 온 STOPPED — 본방 stop 코드는 보지 않는다. libobs는 콜백을 연결한 순서대로 부르고 프런트엔드의
	// stop 콜백이 우리 것보다 먼저 연결돼 있어, STOPPED가 코드보다 먼저 처리될 수 있다(callback/signal.c). 성공 코드
	// 정지는 모두 obs_output_stop·force_stop·지연 정지에서 나고 그 셋은 STOPPING을 먼저 내므로 여기로 오지 않는다.
	return run.started ? MainStopKind::NotIntentional : MainStopKind::FailedToStart;
}

MainStopKind HandleMainStopped(MainStreamRun &run, int mainStopCode, bool hasOutput)
{
	const MainStopKind kind = ClassifyMainStop(run, mainStopCode, hasOutput);
	if (kind != MainStopKind::Duplicate)
		run.stopHandled = true;
	return kind;
}

const char *MainStopKindName(MainStopKind kind)
{
	switch (kind) {
	case MainStopKind::Duplicate:
		return "duplicate";
	case MainStopKind::Intentional:
		return "intentional";
	case MainStopKind::NotIntentional:
		return "not_intentional";
	case MainStopKind::FailedToStart:
		return "failed_to_start";
	}
	return "unknown";
}

} // namespace pokeclip
