'use client';

import { SettingsPageHeader } from '../SettingsPageHeader';
import { DownloadBanner } from './DownloadBanner';
import { IssuedCodeDialog } from './IssuedCodeDialog';
import { PairingCodeCard } from './PairingCodeCard';
import { PluginStatusBanner } from './PluginStatusBanner';
import { usePluginMockState } from './usePluginMockState';
import { useStreamKeyState } from './useStreamKeyState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 설정 · 플러그인을 그대로 옮긴 화면.
// 블록 순서: 연결 상태 → 연동 코드 → 다운로드.
// 연동 코드는 실제 API(useStreamKeyState), 연결 상태는 플러그인 신호 API가 없어 아직 목업.
// 재발급 확인 모달은 없다 — rotate를 쓰지 않아 재발급이 방송 키를 죽이지 않으므로
// 경고할 위험 자체가 사라졌다. 발급·재발급 모두 새 코드 1콜이다.
export function PluginSettingsScreen() {
  const { connection } = usePluginMockState();
  const { loading, error, retryStatus, code, busy, justIssued, clearJustIssued, issue } =
    useStreamKeyState();

  return (
    <div>
      <SettingsPageHeader
        title="플러그인"
        description="OBS 플러그인이 방송 송출과 하이라이트 감지 신호를 PokeClip 서버로 보냅니다"
      />
      <div className={styles.stack}>
        <PluginStatusBanner connection={connection} />
        <PairingCodeCard
          code={code}
          loading={loading}
          error={error}
          busy={busy}
          onIssue={issue}
          onRetry={retryStatus}
        />
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
