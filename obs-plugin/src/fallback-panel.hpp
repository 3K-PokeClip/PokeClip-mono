#pragma once

#include <QWidget>

class QLabel;
class QLineEdit;
class QPushButton;
class QTimer;

namespace pokeclip {

struct StateSnapshot;

// obs-browser가 없거나(일부 Linux·Wayland) 페이지가 뜨지 않을 때의 최소 패널. 기능은 브라우저 독과 같다.
class FallbackPanel : public QWidget {
public:
	explicit FallbackPanel(const QString &reason, QWidget *parent = nullptr);
	~FallbackPanel() override;

private:
	void Render(const StateSnapshot &snapshot);
	void RenderRetryLine();
	void OnPairClicked();
	void OnUnpairClicked();
	void OnMarkClicked();
	void OnAssignClicked();

	QLabel *status_ = nullptr;
	// A5 — 재시도 진행(「재시도 N번째 · M초 뒤」)과 버튼. 상태는 다음 시도 시각만 주므로 남은 초는 여기서 센다 —
	// 기다리는 동안만 1초 타이머가 돈다.
	QLabel *retry_ = nullptr;
	QPushButton *sendNow_ = nullptr;   // 「지금 보내기」 · 기다리는 중이면 「지금 다시 시도」
	QPushButton *stopRetry_ = nullptr; // 「재시도 멈추기」
	QTimer *retryTick_ = nullptr;
	int retryAttempt_ = 0;   // 마지막 상태: 0이면 재시도 중이 아니다
	int64_t retryNextAt_ = 0; // 마지막 상태: 다음 시도 시각(UTC epoch ms), 0이면 접속 중
	bool retryKeySuspect_ = false;
	QLabel *checks_ = nullptr;
	QLabel *audio_ = nullptr;
	// 오디오 트랙 자동 배정 켜기·끄기 — 켜기는 확인 창으로 묻는다(독의 처음 안내와 같은 내용), 끄기는 독 스위치와 같다
	QPushButton *assign_ = nullptr;
	bool audioCustom_ = false;     // 마지막 상태: 스트리머가 트랙 2~6을 직접 짜 뒀다
	bool audioAutoAssign_ = false; // 마지막 상태: 자동 배정이 켜져 있다 — 버튼이 끄기다
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
