#pragma once

#include <QString>
#include <QWidget>

#include <functional>
#include <string>

class QTimer;
class QVBoxLayout;
class QCefWidget;
struct QCef;

namespace pokeclip {

// OBS에 등록하는 독의 본체. 안에 CEF 브라우저 위젯이나 Qt 폴백 패널을 끼운다.
class DockHost : public QWidget {
public:
	explicit DockHost(QWidget *parent = nullptr);

	// obs-browser 패널이 있으면 CEF로, 없으면 폴백으로. FINISHED_LOADING 이후 UI 스레드에서 부른다.
	// helloSeen: 페이지가 브리지에 붙었는지. 워치독 시간 안에 false면 폴백으로 바꾼다.
	void Mount(const std::string &url, bool forceFallback, std::function<bool()> helloSeen);
	void MountFallback(const QString &reason);
	void CloseBrowser();
	// 이 위젯을 감싼 OBS 독(QDockWidget)을 보이게 한다. 성공하면 true.
	bool RevealDock();

private:
	void MountBrowser(QCef *cef, const std::string &url, std::function<bool()> helloSeen);
	void Replace(QWidget *widget);

	void StartWatchdog(std::function<bool()> helloSeen);

	QVBoxLayout *layout_ = nullptr;
	QWidget *current_ = nullptr;
	QCefWidget *browser_ = nullptr;
	QTimer *watchdog_ = nullptr;
	int visibleMs_ = 0;
};

} // namespace pokeclip
