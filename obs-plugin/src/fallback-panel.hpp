#pragma once

#include "audio-assign.hpp"

#include <QWidget>

#include <array>

class QComboBox;
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
	void OnTrackPicked(size_t index);

	QLabel *status_ = nullptr;
	// A5 — 재시도 진행(「M초 뒤 다시 시도해요 · N번째」)과 버튼. 상태는 다음 시도 시각만 주므로 남은 초는 여기서 센다 —
	// 기다리는 동안만 1초 타이머가 돈다.
	QLabel *retry_ = nullptr;
	QPushButton *sendNow_ = nullptr;   // 「다시 연결」 · 기다리는 중이면 「다시 시도」
	QPushButton *stopRetry_ = nullptr; // 「재시도 중지」
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
	// POK-266 손 배정 — 트랙 2~6마다 「T3 BGM, 알림」 줄과 「넣기: <소스>」·「빼기: <소스>」 콤보. 독의 칩 묶음과 같다.
	QWidget *trackRows_ = nullptr;
	std::array<QLabel *, kStemSlots> trackLabel_{};
	std::array<QComboBox *, kStemSlots> trackPick_{};
	// 마지막으로 줄·콤보를 만든 오디오 뷰 — 같으면 다시 만들지 않는다(전송 중엔 통계가 매초 상태를 바꾸는데, 콤보를 비우고
	// 다시 채우면 열어 둔 팝업의 선택이 매초 첫 항목으로 돌아간다)
	AudioRoutingView audioLast_{};
	bool audioLocked_ = false; // 마지막 상태: 방송·녹화 중 — 넣기 전에 한 번 묻는다
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
