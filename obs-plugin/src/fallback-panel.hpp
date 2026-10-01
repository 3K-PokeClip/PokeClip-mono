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
	void OnMarkClicked();
	void OnAssignClicked();

	QLabel *status_ = nullptr;
	QLabel *checks_ = nullptr;
	QLabel *audio_ = nullptr;
	QPushButton *assign_ = nullptr; // 오디오 트랙 자동 배정 켜기 — 확인 창으로 묻는다(독의 처음 안내와 같은 내용)
	bool audioCustom_ = false;      // 마지막 상태: 스트리머가 트랙 2~6을 직접 짜 뒀다
	QLabel *marks_ = nullptr;
	QPushButton *mark_ = nullptr;
	QLabel *message_ = nullptr;
	QLineEdit *code_ = nullptr;
	QPushButton *pair_ = nullptr;
	QPushButton *unpair_ = nullptr;
	uint64_t listenerId_ = 0;
};

QString LocalizedReason(const std::string &code);

} // namespace pokeclip
