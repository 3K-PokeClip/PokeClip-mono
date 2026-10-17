#include "dock-host.hpp"

#include "constants.hpp"
#include "fallback-panel.hpp"
#include "ui-thread.hpp"

#include <obs-module.h>
#include <plugin-support.h>

// obs-browser/panel/browser-panel.hpp (OBS 32.2.1 서브모듈 3f0a2cd) — obs-browser 모듈을 동적으로 찾는다.
#include <browser-panel.hpp>

#include <QDockWidget>
#include <QPointer>
#include <QTimer>
#include <QVBoxLayout>

#include <thread>

namespace pokeclip {

namespace {
// obs-browser의 QCef 인터페이스 버전. create_widget(parent,url,cookie) 시그니처가 들어온 버전 이상을 요구한다.
constexpr int kMinQcefVersion = 3;
} // namespace

DockHost::DockHost(QWidget *parent) : QWidget(parent)
{
	layout_ = new QVBoxLayout(this);
	layout_->setContentsMargins(0, 0, 0, 0);
	layout_->setSpacing(0);
	setMinimumWidth(260);
}

void DockHost::Replace(QWidget *widget)
{
	if (current_) {
		layout_->removeWidget(current_);
		current_->deleteLater();
	}
	current_ = widget;
	if (current_)
		layout_->addWidget(current_);
}

bool DockHost::RevealDock()
{
	for (QWidget *w = parentWidget(); w; w = w->parentWidget()) {
		if (auto *dock = qobject_cast<QDockWidget *>(w)) {
			dock->setVisible(true);
			dock->raise();
			return true;
		}
	}
	return false;
}

void DockHost::StartWatchdog(std::function<bool()> helloSeen)
{
	// 숨은 독에서는 obs-browser가 페이지를 로드하지 않는다 — 보이는 동안의 시간만 센다.
	if (watchdog_)
		watchdog_->deleteLater();
	visibleMs_ = 0;
	watchdog_ = new QTimer(this);
	watchdog_->setInterval(500);
	QPointer<DockHost> self(this);
	connect(watchdog_, &QTimer::timeout, this, [self, helloSeen]() {
		if (!self || !self->browser_) {
			if (self && self->watchdog_)
				self->watchdog_->stop();
			return;
		}
		if (helloSeen && helloSeen()) {
			self->watchdog_->stop();
			return;
		}
		if (!self->isVisible())
			return;
		self->visibleMs_ += 500;
		if (self->visibleMs_ >= kBrowserWatchdogMs) {
			self->watchdog_->stop();
			obs_log(LOG_WARNING, "dock: page did not reach bridge in time — falling back");
			self->MountFallback("page_watchdog");
		}
	});
	watchdog_->start();
}

void DockHost::CloseBrowser()
{
	if (watchdog_)
		watchdog_->stop();
	if (browser_) {
		browser_->closeBrowser();
		browser_ = nullptr;
	}
}

void DockHost::MountFallback(const QString &reason)
{
	CloseBrowser();
	obs_log(LOG_INFO, "dock: Qt fallback panel (%s)", reason.toUtf8().constData());
	Replace(new FallbackPanel(reason, this));
}

void DockHost::Mount(const std::string &url, bool forceFallback, std::function<bool()> helloSeen)
{
	if (forceFallback) {
		MountFallback("force_fallback");
		return;
	}
	if (url.empty()) {
		MountFallback("bridge_unavailable");
		return;
	}
	int version = obs_browser_qcef_version();
	if (version < kMinQcefVersion) {
		MountFallback(QString("obs-browser unavailable (qcef %1)").arg(version));
		return;
	}
	QCef *cef = obs_browser_init_panel();
	if (!cef) {
		MountFallback("obs-browser panel unavailable");
		return;
	}

	if (cef->init_browser()) {
		MountBrowser(cef, url, std::move(helloSeen));
		return;
	}
	// CEF가 아직 준비 전 — OBS 자신도 스레드에서 기다린 뒤 위젯을 만든다 (OBSBasic_Browser.cpp).
	QPointer<DockHost> self(this);
	std::thread([self, cef, url, helloSeen = std::move(helloSeen)]() {
		cef->wait_for_browser_init();
		RunInUiThread([self, cef, url, helloSeen]() {
			if (self)
				self->MountBrowser(cef, url, helloSeen);
		});
	}).detach();
}

void DockHost::MountBrowser(QCef *cef, const std::string &url, std::function<bool()> helloSeen)
{
	QCefWidget *widget = cef->create_widget(this, url, nullptr);
	if (!widget) {
		MountFallback("create_widget failed");
		return;
	}
	browser_ = widget;
	Replace(widget);
	obs_log(LOG_INFO, "dock: browser panel mounted");

	StartWatchdog(std::move(helloSeen));
}

} // namespace pokeclip
