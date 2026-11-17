#include "main-stream-policy.hpp"

namespace pokeclip {

MainStopKind ClassifyMainStop(const MainStreamRun &run, int mainStopCode, bool hasOutput)
{
	if (run.stopHandled)
		return MainStopKind::Duplicate;
	if (run.stopRequested && (hasOutput || mainStopCode == kMainStopSuccess))
		return MainStopKind::Intentional;
	return MainStopKind::NotIntentional;
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
	}
	return "unknown";
}

} // namespace pokeclip
