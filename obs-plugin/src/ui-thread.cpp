#include "ui-thread.hpp"

#include <QMetaObject>
#include <QObject>
#include <QPointer>

namespace pokeclip {

namespace {
QPointer<QObject> g_context;
}

void SetUiThreadContext(QObject *context)
{
	g_context = context;
}

void RunInUiThread(std::function<void()> task)
{
	QObject *context = g_context.data();
	if (!context)
		return;
	// 같은 스레드여도 큐에 넣는다 — libobs 콜백 안에서 재진입하지 않게.
	QMetaObject::invokeMethod(context, [task = std::move(task)]() { task(); }, Qt::QueuedConnection);
}

} // namespace pokeclip
