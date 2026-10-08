#include "fallback-panel.hpp"

#include "app-state.hpp"
#include "audio-router.hpp"
#include "config.hpp"
#include "mark-sender.hpp"
#include "pairing.hpp"
#include "stream-control.hpp"
#include "ui-thread.hpp"

#include <obs-module.h>

#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QMessageBox>
#include <QPointer>
#include <QPushButton>
#include <QStringList>
#include <QTimer>
#include <QVBoxLayout>

#include <chrono>
#include <thread>

namespace pokeclip {

namespace {
QString Text(const char *key)
{
	return QString::fromUtf8(obs_module_text(key));
}
} // namespace

QString LocalizedReason(const std::string &code)
{
	if (code.empty())
		return {};
	std::string key = "Reason." + code;
	const char *text = nullptr;
	if (obs_module_get_string(key.c_str(), &text) && text)
		return QString::fromUtf8(text);
	return Text("Reason.unknown") + " (" + QString::fromStdString(code) + ")";
}

FallbackPanel::FallbackPanel(const QString &reason, QWidget *parent) : QWidget(parent)
{
	auto *layout = new QVBoxLayout(this);
	layout->setContentsMargins(10, 10, 10, 10);
	layout->setSpacing(8);

	auto *title = new QLabel("<b>PokeClip for OBS</b>", this);
	status_ = new QLabel(this);
	status_->setWordWrap(true);
	retry_ = new QLabel(this);
	retry_->setWordWrap(true);
	sendNow_ = new QPushButton(Text("Fallback.SendNow"), this);
	stopRetry_ = new QPushButton(Text("Retry.Stop"), this);
	retryTick_ = new QTimer(this);
	retryTick_->setInterval(1000);
	checks_ = new QLabel(this);
	checks_->setWordWrap(true);
	// 소스 이름·단축키처럼 사용자가 정한 글자를 싣는다 — 태그처럼 보여도 HTML로 그리지 않는다.
	audio_ = new QLabel(this);
	audio_->setWordWrap(true);
	audio_->setTextFormat(Qt::PlainText);
	assign_ = new QPushButton(Text("Audio.Enable"), this);
	marks_ = new QLabel(this);
	marks_->setWordWrap(true);
	marks_->setTextFormat(Qt::PlainText);
	mark_ = new QPushButton(Text("Mark.Button"), this);
	message_ = new QLabel(this);
	message_->setWordWrap(true);

	code_ = new QLineEdit(this);
	code_->setPlaceholderText("XXXX-XXXX");
	code_->setMaxLength(9);

	pair_ = new QPushButton(Text("Fallback.Pair"), this);
	unpair_ = new QPushButton(Text("Fallback.Unpair"), this);

	auto *row = new QHBoxLayout();
	row->addWidget(code_, 1);
	row->addWidget(pair_);

	auto *note = new QLabel(Text("Fallback.Note") + (reason.isEmpty() ? QString() : " [" + reason + "]"), this);
	note->setWordWrap(true);
	note->setStyleSheet("color: gray; font-size: 11px;");

	auto *retryRow = new QHBoxLayout();
	retryRow->addWidget(sendNow_);
	retryRow->addWidget(stopRetry_);
	retryRow->addStretch(1);

	layout->addWidget(title);
	layout->addWidget(status_);
	layout->addWidget(retry_);
	layout->addLayout(retryRow);
	layout->addWidget(checks_);
	layout->addWidget(audio_);
	layout->addWidget(assign_);
	layout->addWidget(mark_);
	layout->addWidget(marks_);
	layout->addLayout(row);
	layout->addWidget(unpair_);
	layout->addWidget(message_);
	layout->addStretch(1);
	layout->addWidget(note);

	connect(pair_, &QPushButton::clicked, this, [this]() { OnPairClicked(); });
	connect(code_, &QLineEdit::returnPressed, this, [this]() { OnPairClicked(); });
	connect(unpair_, &QPushButton::clicked, this, [this]() { OnUnpairClicked(); });
	connect(mark_, &QPushButton::clicked, this, [this]() { OnMarkClicked(); });
	connect(assign_, &QPushButton::clicked, this, [this]() { OnAssignClicked(); });
	// 버튼은 UI 스레드에서 눌린다 — 독(브리지 워커)과 달리 그대로 부른다. 결과는 상태로 돌아와 Render가 그린다.
	connect(sendNow_, &QPushButton::clicked, this, []() { SendNow(); });
	connect(stopRetry_, &QPushButton::clicked, this, []() { StopRetryNow(); });
	connect(retryTick_, &QTimer::timeout, this, [this]() { RenderRetryLine(); });

	QPointer<FallbackPanel> self(this);
	listenerId_ = AppState::Instance().AddListener([self](const StateSnapshot &s) {
		RunInUiThread([self, s]() {
			if (self)
				self->Render(s);
		});
	});
	Render(AppState::Instance().Snapshot());
}

FallbackPanel::~FallbackPanel()
{
	AppState::Instance().RemoveListener(listenerId_);
}

void FallbackPanel::Render(const StateSnapshot &s)
{
	QString phase = Text((std::string("Phase.") + PhaseName(s.phase)).c_str());
	QString pairing = s.paired ? Text("Fallback.Paired").arg(QString::fromStdString(s.keyHint))
				   : Text("Fallback.Unpaired");
	QString line = pairing + " · " + phase;
	if (s.phase == StreamPhase::Live)
		line += QString(" · %1 kbps").arg(static_cast<int>(s.stats.bitrateKbps));
	status_->setText(line);

	// A5 — 재시도 중이면 진행과 「다시 시도」·「재시도 중지」, 멈춰 있고 본방이 나가면 「다시 연결」.
	const bool retrying = s.retry.attempt > 0 &&
			      (s.phase == StreamPhase::Starting || s.phase == StreamPhase::Reconnecting);
	const bool waiting = retrying && s.retry.nextAt > 0;
	retryAttempt_ = retrying ? s.retry.attempt : 0;
	retryNextAt_ = s.retry.nextAt;
	retryKeySuspect_ = s.retry.keySuspect;
	RenderRetryLine();
	if (waiting && !retryTick_->isActive())
		retryTick_->start();
	else if (!waiting)
		retryTick_->stop();
	sendNow_->setText(Text(retrying ? "Retry.Now" : "Fallback.SendNow"));
	sendNow_->setVisible(waiting || CanSendNow(s));
	stopRetry_->setVisible(retrying);

	auto mark = [](const std::optional<bool> &v) { return !v.has_value() ? QString("–") : (*v ? "✓" : "✗"); };
	// 1080p는 권장값이라 어긋나도 송출은 나간다 — 실패(✗)가 아니라 경고(⚠)로 적고, 독과 같은 안내를 한 줄 붙인다.
	const bool resWarn = s.checks.res1080p.has_value() && !*s.checks.res1080p;
	QString checksText = QString("GOP 2s %1   1080p %2   %3 %4   %5 %6")
				     .arg(mark(s.checks.gop2s), resWarn ? QString("⚠") : mark(s.checks.res1080p),
					  Text("Check.SharedEncoder"), mark(s.checks.sharedEncoder), Text("Check.AudioTracks"),
					  mark(s.checks.audioTracks));
	if (resWarn)
		checksText += "\n" + Text("Check.ResAdvisory");
	checks_->setText(checksText);

	// 트랙 1은 늘 최종 믹스라 트랙 2~6만 적는다. 실제 트랙 비트 기준이라 수동 모드에서도 진실을 보여준다.
	if (s.paired && s.audio.known) {
		QStringList parts;
		for (const AudioTrackView &t : s.audio.tracks) {
			QStringList names;
			for (const AudioSourceView &src : t.sources)
				names << QString::fromStdString(src.name);
			QString label = t.mainStream ? QString("T%1(%2)").arg(t.track).arg(Text("Audio.MainStream"))
						     : QString("T%1").arg(t.track);
			parts << label + " " + (names.isEmpty() ? QString("–") : names.join(", "));
		}
		QString line = Text("Audio.Title") + ": " + parts.join(" · ");
		if (!s.audio.mixOnly.empty())
			line += " · " + Text("Audio.MixOnly").arg(static_cast<int>(s.audio.mixOnly.size()));
		if (s.audio.deferred)
			line += " " + Text("Audio.Deferred");
		else if (!s.audio.autoAssign)
			line += " " + Text("Audio.Manual");
		audio_->setText(line);
		audio_->setVisible(true);
	} else {
		audio_->setVisible(false);
	}
	// 자동 배정은 기본으로 꺼져 있다 — 켜는 길은 확인 창을 거친다(스템이 왜 필요한지·짜 둔 트랙을 덮어쓴다).
	// 켜져 있으면 같은 버튼이 끄기다 — 독 「고급 설정」 스위치와 같이 원래 체크로 되돌린다(방송·녹화 중이면 끝난 뒤).
	assign_->setText(Text(s.audio.autoAssign ? "Audio.Disable" : "Audio.Enable"));
	assign_->setVisible(s.paired && s.audio.known);
	audioCustom_ = s.audio.customRouting;
	audioAutoAssign_ = s.audio.autoAssign;

	// A4 — 우리 송출 중에만 누를 수 있다. 개수 줄은 지난 방송 것이 남아 있어도 보여준다.
	bool sending = s.phase == StreamPhase::Live || s.phase == StreamPhase::Reconnecting;
	mark_->setVisible(s.paired && sending);
	if (s.paired && (sending || s.marks.sent || s.marks.pending || s.marks.failed)) {
		QString hotkey = s.marks.hotkey.empty() ? Text("Mark.NoHotkey") : QString::fromStdString(s.marks.hotkey);
		QString line = Text("Mark.Line").arg(s.marks.sent).arg(s.marks.pending).arg(s.marks.failed).arg(hotkey);
		if (s.marks.result != "sent" && !s.marks.reason.empty())
			line += " — " + LocalizedReason(s.marks.reason);
		marks_->setText(line);
		marks_->setVisible(true);
	} else {
		marks_->setVisible(false);
	}

	// 재시도를 멈췄거나 포기한 뒤에도 키 확인 안내는 남긴다(재시도 중에는 위 재시도 줄이 보여 준다).
	QString reason = !retrying && s.retry.keySuspect ? Text("Retry.KeyHint") : LocalizedReason(s.errorCode);
	if (s.retry.gaveUp)
		reason = Text("Retry.GaveUp") + " " + reason;
	message_->setText(reason);
	code_->setVisible(!s.paired);
	pair_->setVisible(!s.paired);
	unpair_->setVisible(s.paired);
}

void FallbackPanel::RenderRetryLine()
{
	if (retryAttempt_ <= 0) {
		retry_->setVisible(false);
		return;
	}
	QString line;
	if (retryNextAt_ > 0) {
		const int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
					    std::chrono::system_clock::now().time_since_epoch())
					    .count();
		const int64_t leftMs = retryNextAt_ - now;
		const int seconds = leftMs > 0 ? static_cast<int>((leftMs + 999) / 1000) : 0;
		line = Text("Retry.Waiting").arg(retryAttempt_).arg(seconds);
	} else {
		line = Text("Retry.Connecting").arg(retryAttempt_);
	}
	if (retryKeySuspect_)
		line += "\n" + Text("Retry.KeyHint");
	retry_->setText(line);
	retry_->setVisible(true);
}

void FallbackPanel::OnPairClicked()
{
	std::string code = code_->text().toStdString();
	pair_->setEnabled(false);
	message_->setText(Text("Fallback.Pairing"));
	QPointer<FallbackPanel> self(this);
	std::thread([self, code]() {
		PairingResult r = PairWithCode(code);
		RunInUiThread([self, r]() {
			if (!self)
				return;
			self->pair_->setEnabled(true);
			self->message_->setText(r.ok ? QString() : LocalizedReason(r.reason));
			if (r.ok)
				self->code_->clear();
		});
	}).detach();
}

void FallbackPanel::OnMarkClicked()
{
	// 거절 사유(방송 아님 등)는 상태 줄에 실리고, 연타만 여기서 알린다.
	MarkAccept a = MarkSender::Instance().Mark(MarkVia::Panel);
	if (!a.ok && a.reason == "mark_too_soon")
		message_->setText(LocalizedReason(a.reason));
}

void FallbackPanel::OnAssignClicked()
{
	const bool enable = !audioAutoAssign_;
	if (enable) {
		QString body = Text("Audio.ConsentBody");
		if (audioCustom_)
			body += "\n\n" + Text("Audio.ConsentOverwrite");
		if (QMessageBox::question(this, Text("Audio.ConsentTitle"), body) != QMessageBox::Yes)
			return;
	}
	bool saved = ConfigStore::Instance().Commit([enable](PluginConfig &c) {
		c.audioAutoAssign = enable;
		c.audioAssignPrompted = true;
	});
	if (!saved) {
		// 독 경로(PUT /api/config)와 같다 — 저장하지 못했으면 알리고 아무것도 바꾸지 않는다.
		message_->setText(LocalizedReason("save_failed"));
		return;
	}
	AudioRouter::Instance().Schedule("setting");
}

void FallbackPanel::OnUnpairClicked()
{
	PairingResult r = Unpair();
	message_->setText(r.ok ? QString() : LocalizedReason(r.reason));
}

} // namespace pokeclip
