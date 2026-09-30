#include "fallback-panel.hpp"

#include "app-state.hpp"
#include "mark-sender.hpp"
#include "pairing.hpp"
#include "ui-thread.hpp"

#include <obs-module.h>

#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QPointer>
#include <QPushButton>
#include <QStringList>
#include <QVBoxLayout>

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
	checks_ = new QLabel(this);
	checks_->setWordWrap(true);
	// 소스 이름·단축키처럼 사용자가 정한 글자를 싣는다 — 태그처럼 보여도 HTML로 그리지 않는다.
	audio_ = new QLabel(this);
	audio_->setWordWrap(true);
	audio_->setTextFormat(Qt::PlainText);
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

	layout->addWidget(title);
	layout->addWidget(status_);
	layout->addWidget(checks_);
	layout->addWidget(audio_);
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

	auto mark = [](const std::optional<bool> &v) { return !v.has_value() ? QString("–") : (*v ? "✓" : "✗"); };
	checks_->setText(QString("GOP 2s %1   1080p %2   %3 %4   %5 %6")
				 .arg(mark(s.checks.gop2s), mark(s.checks.res1080p), Text("Check.SharedEncoder"),
				      mark(s.checks.sharedEncoder), Text("Check.AudioTracks"), mark(s.checks.audioTracks)));

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

	message_->setText(LocalizedReason(s.errorCode));
	code_->setVisible(!s.paired);
	pair_->setVisible(!s.paired);
	unpair_->setVisible(s.paired);
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

void FallbackPanel::OnUnpairClicked()
{
	PairingResult r = Unpair();
	message_->setText(r.ok ? QString() : LocalizedReason(r.reason));
}

} // namespace pokeclip
