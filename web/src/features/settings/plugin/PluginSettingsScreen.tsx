'use client';

import { SettingsPageHeader } from '../SettingsPageHeader';
import { DownloadBanner } from './DownloadBanner';
import { PairingCodeCard } from './PairingCodeCard';
import { PluginStatusBanner } from './PluginStatusBanner';
import { usePluginMockState } from './usePluginMockState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 설정 · 플러그인을 그대로 옮긴 화면.
// 블록 순서: 연결 상태 → 연동 코드 → 다운로드.
export function PluginSettingsScreen() {
  const { connection, code, issueCode } = usePluginMockState();

  return (
    <div className={styles.screen}>
      <SettingsPageHeader
        title="플러그인"
        description="OBS 플러그인이 방송 송출과 하이라이트 감지 신호를 PokeClip 서버로 보냅니다"
      />
      <div className={styles.stack}>
        <PluginStatusBanner connection={connection} />
        <PairingCodeCard code={code} onIssue={issueCode} />
        <DownloadBanner />
      </div>
    </div>
  );
}
