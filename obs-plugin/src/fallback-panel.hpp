#pragma once

#include <QWidget>

class QLabel;
class QLineEdit;
class QPushButton;

namespace pokeclip {

struct StateSnapshot;

// obs-browser가 없거나(일부 Linux·Wayland) 페이지가 뜨지 않을 때의 최소 패널. 기능은 브라우저 독과 같다.
class FallbackPanel : public QWidget {
public:
	explicit FallbackPanel(const QString &reason, QWidget *parent = nullptr);
	~FallbackPanel() override;

private:
	void Render(const StateSnapshot &snapshot);
	void OnPairClicked();
	void OnUnpairClicked();

	QLabel *status_ = nullptr;
	QLabel *checks_ = nullptr;
	QLabel *message_ = nullptr;
	QLineEdit *code_ = nullptr;
	QPushButton *pair_ = nullptr;
	QPushButton *unpair_ = nullptr;
	uint64_t listenerId_ = 0;
};

QString LocalizedReason(const std::string &code);

} // namespace pokeclip
