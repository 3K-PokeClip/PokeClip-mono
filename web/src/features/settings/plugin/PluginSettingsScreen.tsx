'use client';

import { useState } from 'react';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { AudioTrackNamesCard } from './AudioTrackNamesCard';
import { DownloadBanner } from './DownloadBanner';
import { IssuedCodeDialog } from './IssuedCodeDialog';
import { PairingCodeCard } from './PairingCodeCard';
import { PluginDownloadDialog } from './PluginDownloadDialog';
import { useDetectedOs } from './useDetectedOs';
import { usePluginReleaseState } from './usePluginReleaseState';
import { useStreamKeyState } from './useStreamKeyState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 설정 · 플러그인을 옮긴 화면.
// 블록 순서: 연동 코드 → 오디오 트랙 이름 → 다운로드.
// 다운로드(POK-379)는 GitHub Release의 최신 설치 파일을 /api/plugin-release로 읽어 배너 → 창에서 받는다.
// 시안 맨 위의 연결 상태 배너·「연결 진단」 버튼은 두지 않는다 — 플러그인은 대기 중 서버에 신호를
// 보내지 않고 웹은 OBS 안 루프백 독에 닿을 수 없어, 웹이 아는 연결 상태가 없다(ADR-077 결정 4).
// 코드 입력 결과는 OBS PokeClip 독이 보여 준다. 연동 코드는 실제 API(useStreamKeyState).
// 재발급 확인 모달은 없다 — rotate를 쓰지 않아 재발급이 방송 키를 죽이지 않으므로
// 경고할 위험 자체가 사라졌다. 발급·재발급 모두 새 코드 1콜이다.
export function PluginSettingsScreen() {
  const { loading, error, retryStatus, code, busy, justIssued, clearJustIssued, issue } =
    useStreamKeyState();
  const download = usePluginReleaseState();
  const detectedOs = useDetectedOs();
  const [downloadOpen, setDownloadOpen] = useState(false);

  return (
    <div>
      <SettingsPageHeader
        title="플러그인"
        description="OBS 플러그인이 방송 송출과 하이라이트 감지 신호를 PokeClip 서버로 보냅니다"
      />
      <div className={styles.stack}>
        <PairingCodeCard
          code={code}
          loading={loading}
          error={error}
          busy={busy}
          onIssue={issue}
          onRetry={retryStatus}
        />
        <AudioTrackNamesCard />
        <DownloadBanner state={download} onOpen={() => setDownloadOpen(true)} />
      </div>
      <IssuedCodeDialog
        issued={justIssued}
        busy={busy}
        onClose={clearJustIssued}
        onIssueNew={issue}
      />
      {download.kind === 'ready' ? (
        <PluginDownloadDialog
          open={downloadOpen}
          onClose={() => setDownloadOpen(false)}
          release={download.release}
          detectedOs={detectedOs}
        />
      ) : null}
    </div>
  );
}
