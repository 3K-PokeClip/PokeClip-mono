'use client';

import { useState } from 'react';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { DownloadBanner } from './DownloadBanner';
import { PairingCodeCard } from './PairingCodeCard';
import { PluginStatusBanner } from './PluginStatusBanner';
import { RotateConfirmDialog } from './RotateConfirmDialog';
import { usePluginMockState } from './usePluginMockState';
import { useStreamKeyState } from './useStreamKeyState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 설정 · 플러그인을 그대로 옮긴 화면.
// 블록 순서: 연결 상태 → 연동 코드 → 다운로드.
// 연동 코드는 실제 API(useStreamKeyState), 연결 상태는 플러그인 신호 API가 없어 아직 목업.
export function PluginSettingsScreen() {
  const { connection } = usePluginMockState();
  const { loading, error, retryStatus, code, busy, issue, reissue } = useStreamKeyState();
  // 재발급은 기존 키를 즉시 죽이므로(ADR-019) 반드시 확인 모달을 거친다 (POK-102 완료조건)
  const [confirmOpen, setConfirmOpen] = useState(false);

  return (
    <div className={styles.screen}>
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
          onReissue={() => setConfirmOpen(true)}
          onRetry={retryStatus}
        />
        <DownloadBanner />
      </div>
      <RotateConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        onConfirm={() => {
          setConfirmOpen(false);
          reissue();
        }}
      />
    </div>
  );
}
