#include "fallback-panel.hpp"

#include "app-state.hpp"
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
	audio_ = new QLabel(this);
	audio_->setWordWrap(true);
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
	layout->addLayout(row);
	layout->addWidget(unpair_);
	layout->addWidget(message_);
	layout->addStretch(1);
	layout->addWidget(note);

	connect(pair_, &QPushButton::clicked, this, [this]() { OnPairClicked(); });
	connect(code_, &QLineEdit::returnPressed, this, [this]() { OnPairClicked(); });
	connect(unpair_, &QPushButton::clicked, this, [this]() { OnUnpairClicked(); });

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
			parts << QString("T%1 %2").arg(t.track).arg(names.isEmpty() ? QString("–") : names.join(", "));
		}
		QString line = Text("Audio.Title") + ": " + parts.join(" · ");
		if (!s.audio.mixOnly.empty())
			line += " · " + Text("Audio.MixOnly").arg(static_cast<int>(s.audio.mixOnly.size()));
		if (!s.audio.autoAssign)
			line += " " + Text("Audio.Manual");
		audio_->setText(line);
		audio_->setVisible(true);
	} else {
		audio_->setVisible(false);
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

void FallbackPanel::OnUnpairClicked()
{
	PairingResult r = Unpair();
	message_->setText(r.ok ? QString() : LocalizedReason(r.reason));
}

} // namespace pokeclip
