'use client';

import { SettingsPageHeader } from '../SettingsPageHeader';
import { AudioTrackNamesCard } from './AudioTrackNamesCard';
import { DownloadBanner } from './DownloadBanner';
import { IssuedCodeDialog } from './IssuedCodeDialog';
import { PairingCodeCard } from './PairingCodeCard';
import { PluginConnectionGuide } from './PluginConnectionGuide';
import { useStreamKeyState } from './useStreamKeyState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 설정 · 플러그인을 그대로 옮긴 화면.
// 블록 순서: 연결 확인 안내 → 연동 코드 → 오디오 트랙 이름 → 다운로드.
// 연동 코드는 실제 API(useStreamKeyState). 연결 상태는 플러그인 신호 API가 없어 상태 대신
// OBS 독에서 확인하라고 안내한다(PluginConnectionGuide).
// 재발급 확인 모달은 없다 — rotate를 쓰지 않아 재발급이 방송 키를 죽이지 않으므로
// 경고할 위험 자체가 사라졌다. 발급·재발급 모두 새 코드 1콜이다.
export function PluginSettingsScreen() {
  const { loading, error, retryStatus, code, busy, justIssued, clearJustIssued, issue } =
    useStreamKeyState();

  return (
    <div>
      <SettingsPageHeader
        title="플러그인"
        description="OBS 플러그인이 방송 송출과 하이라이트 감지 신호를 PokeClip 서버로 보냅니다"
      />
      <div className={styles.stack}>
        <PluginConnectionGuide />
        <PairingCodeCard
          code={code}
          loading={loading}
          error={error}
          busy={busy}
          onIssue={issue}
          onRetry={retryStatus}
        />
        <AudioTrackNamesCard />
        <DownloadBanner />
      </div>
      <IssuedCodeDialog
        issued={justIssued}
        busy={busy}
        onClose={clearJustIssued}
        onIssueNew={issue}
      />
    </div>
  );
}
