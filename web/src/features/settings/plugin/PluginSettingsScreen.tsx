'use client';

import { useEffect, useState } from 'react';
import { track } from '@/lib/analytics';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { AudioTrackNamesCard } from './AudioTrackNamesCard';
import { DownloadBanner } from './DownloadBanner';
import { InstallGuideCard } from './InstallGuideCard';
import { IssuedCodeDialog } from './IssuedCodeDialog';
import { PairingCodeCard } from './PairingCodeCard';
import { PluginDownloadDialog } from './PluginDownloadDialog';
import { useDetectedOs } from './useDetectedOs';
import { useStreamKeyState } from './useStreamKeyState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 설정 · 플러그인을 옮긴 화면.
// 블록 순서: 연동 코드 → 오디오 트랙 이름 → 다운로드.
// 다운로드(POK-379)는 배너 → 창에서 고정 주소(lib/pluginDownload)의 OS별 설치 파일을 받는다.
// 단, 들어왔을 때 코드를 한 번도 받지 않은 회원은 맨 위의 설치 안내(InstallGuideCard)가 배너를 대신한다
// (2026-11-18 시안 C) — 플러그인 없이 10분짜리 코드부터 받고 떠나는 일을 막는다. 이 화면에 있는 동안은
// 첫 코드를 받아도 안내를 거두지 않는다: 거두면 코드를 받은 직후 현재안으로 돌아가 설치 안내가 맨 아래로 숨는다.
// 발급 상태를 2초 안에 못 읽거나 읽기가 실패하면 기다리지 않고 배너로 정한다(GUIDE_DECIDE_WAIT_MS).
// 웹은 플러그인이 연결됐는지 모른다 — 코드를 한 번 받은 뒤 다시 들어오면 배너 화면이다(시안의 알려진 약점).
// 시안 맨 위의 연결 상태 배너·「연결 진단」 버튼은 두지 않는다 — 플러그인은 대기 중 서버에 신호를
// 보내지 않고 웹은 OBS 안 루프백 독에 닿을 수 없어, 웹이 아는 연결 상태가 없다(ADR-077 결정 4).
// 코드 입력 결과는 OBS PokeClip 독이 보여 준다. 연동 코드는 실제 API(useStreamKeyState).
// 재발급 확인 모달은 없다 — rotate를 쓰지 않아 재발급이 방송 키를 죽이지 않으므로
// 경고할 위험 자체가 사라졌다. 발급·재발급 모두 새 코드 1콜이다.

/** 발급 상태를 이만큼 못 읽으면 기다리지 않고 맨 아래 배너로 정한다(아래 showGuide). */
export const GUIDE_DECIDE_WAIT_MS = 2000;

export function PluginSettingsScreen() {
  const { loading, error, retryStatus, retrying, code, busy, justIssued, clearJustIssued, issue } =
    useStreamKeyState();
  const detectedOs = useDetectedOs();
  const [downloadOpen, setDownloadOpen] = useState(false);
  // 안내냐 배너냐는 한 번만 정한다(null = 아직). 렌더 중 조정이라 effect 한 바퀴 깜빡임이 없다.
  //   - 처음 읽은 발급 상태로 정한다.
  //   - 못 읽었거나(오류 — 미발급으로 오인시키지 않는 코드 카드와 같은 규칙) 읽기가 GUIDE_DECIDE_WAIT_MS를 넘기면
  //     배너로 정하고, 그 뒤 「받은 적 없음」이 와도 바꾸지 않는다. 다운로드는 서버와 무관한 고정 주소라 오래 비워 둘
  //     까닭이 없고(GET 한도 30초 + 재시도 1회면 1분), 회원이 「다시 시도」 등을 누른 뒤 배너가 맨 위 안내로 튀면
  //     누른 자리가 포인터 아래에서 밀린다(PR #538 리뷰).
  const [showGuide, setShowGuide] = useState<boolean | null>(null);
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    if (!loading) return;
    const id = window.setTimeout(() => setSlow(true), GUIDE_DECIDE_WAIT_MS);
    return () => window.clearTimeout(id);
  }, [loading]);
  if (showGuide === null) {
    if (error || (loading && slow)) setShowGuide(false);
    else if (!loading) setShowGuide(!code.issued);
  }
  // 창을 연 것도 센다 — 받기 링크(plugin_download_clicked)까지 안 간 사람이 창은 봤는지 가른다
  const openDownload = (from: 'guide' | 'banner') => {
    setDownloadOpen(true);
    track('plugin_download_opened', { from });
  };

  return (
    <div>
      <SettingsPageHeader
        title="플러그인"
        description="OBS 플러그인이 방송 송출과 하이라이트 감지 신호를 PokeClip 서버로 보냅니다"
      />
      <div className={styles.stack}>
        {showGuide === true && <InstallGuideCard onOpen={() => openDownload('guide')} />}
        <PairingCodeCard
          code={code}
          loading={loading}
          error={error}
          retrying={retrying}
          busy={busy}
          onIssue={issue}
          onRetry={retryStatus}
        />
        <AudioTrackNamesCard />
        {/* 정하기 전(처음 읽는 짧은 동안)은 비워 둔다 — 배너를 먼저 그렸다가 안내로 바꾸면 맨 아래에서 맨 위로 튄다 */}
        {showGuide === false && <DownloadBanner onOpen={() => openDownload('banner')} />}
      </div>
      <IssuedCodeDialog
        issued={justIssued}
        busy={busy}
        onClose={clearJustIssued}
        onIssueNew={issue}
      />
      <PluginDownloadDialog
        open={downloadOpen}
        onClose={() => setDownloadOpen(false)}
        detectedOs={detectedOs}
      />
    </div>
  );
}
