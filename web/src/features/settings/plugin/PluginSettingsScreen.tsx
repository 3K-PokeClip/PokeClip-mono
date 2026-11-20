'use client';

import { useEffect, useRef, useState } from 'react';
import { track } from '@/lib/analytics';
import { VisuallyHidden } from '@/ui';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { ConnectStepsCard } from './ConnectStepsCard';
import { DownloadBanner } from './DownloadBanner';
import { InstallVideoCard } from './InstallVideoCard';
import { IssuedCodeDialog } from './IssuedCodeDialog';
import { PairingCodeCard } from './PairingCodeCard';
import { PluginDownloadDialog } from './PluginDownloadDialog';
import { rememberPluginDownloadClicked, useRememberedPluginDownload } from './pluginDownloadMark';
import { useDetectedOs } from './useDetectedOs';
import { useStreamKeyState } from './useStreamKeyState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 설정 · 플러그인을 옮긴 화면. 맨 위 블록이 연결 여부로 갈린다(POK-608·609):
//   - 플러그인을 연결한 적 없는 회원 → 연결 순서 카드(ConnectStepsCard): 설치 → OBS 다시 켜기 → 코드 받기.
//     2번을 눌러야 3번이 열린다(2026-11-19 시안 C 「순서대로만 열리기」) — 설치 전에 10분짜리 코드부터 받는 일을 막는다.
//   - 연결한 회원 → 연동 코드 카드(「연결했어요 · 마지막 연결」, 다른 PC를 더 붙일 때 재발급) + 맨 아래 다운로드 배너.
// 「연결한 적」은 서버의 연결 시각 하나로 본다. 코드 발급으로 가르던 때(2026-11-18 시안 C)는 코드만 받고 떠난 회원이
// 돌아오면 배너 화면이라 설치 안내를 다시 못 봤고, 안내가 있어도 바로 아래 「코드 발급」을 먼저 눌렀다.
// 다운로드(POK-379)는 창에서 고정 주소(lib/pluginDownload)의 OS별 설치 파일을 받는다 — 여는 곳이 순서 카드의 1번이냐
// 맨 아래 배너냐만 다르다.
// 시안 맨 위의 연결 상태 배너·「연결 진단」 버튼은 두지 않는다 — 플러그인은 대기 중 서버에 신호를
// 보내지 않고 웹은 OBS 안 루프백 독에 닿을 수 없어, 웹이 아는 것은 「코드가 쓰인 시각」뿐이다(ADR-077 결정 4).
// 지금 붙어 있는지는 OBS PokeClip 독이 보여 준다. 연동 코드는 실제 API(useStreamKeyState).
// 재발급 확인 모달은 없다 — rotate를 쓰지 않아 재발급이 방송 키를 죽이지 않으므로
// 경고할 위험 자체가 사라졌다. 발급·재발급 모두 새 코드 1콜이다.

/** 상태를 이만큼 못 읽으면 기다리지 않고 맨 아래에 다운로드 배너를 세운다(아래 showBanner). */
export const STATUS_SLOW_MS = 2000;

export function PluginSettingsScreen() {
  const {
    loading,
    error,
    retryStatus,
    retrying,
    code,
    busy,
    justIssued,
    justPaired,
    codeWaiting,
    clearJustIssued,
    issue,
  } = useStreamKeyState();
  // 창을 닫았는데 초점이 갈 곳이 없으면(창을 연 버튼이 사라짐) 맨 위 카드 제목으로 옮긴다 — 연결을 본 순간 순서 카드가
  // 연동 코드 카드로 바뀌어 「코드 받기」가 문서에서 빠지고, FocusScope는 문서에 남은 요소에만 초점을 돌려준다
  // (PR #539 리뷰: 키보드·스크린리더 사용자가 문서 처음부터 다시 탭해야 했다). 창이 내려간 뒤(되돌리기 시도 뒤)에 본다
  const topCardTitleRef = useRef<HTMLHeadingElement>(null);
  const [dialogClosed, setDialogClosed] = useState(0);
  useEffect(() => {
    if (dialogClosed === 0) return;
    if (document.activeElement === null || document.activeElement === document.body) {
      topCardTitleRef.current?.focus();
    }
  }, [dialogClosed]);
  const closeIssuedDialog = () => {
    clearJustIssued();
    setDialogClosed((n) => n + 1);
  };
  const detectedOs = useDetectedOs();
  const [downloadOpen, setDownloadOpen] = useState(false);
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    if (!loading) return;
    const id = window.setTimeout(() => setSlow(true), STATUS_SLOW_MS);
    return () => window.clearTimeout(id);
  }, [loading]);

  const paired = code.pairedAt !== undefined;
  // 연결한 적 없다는 것을 「읽어서 아는」 때만 순서 카드다 — 읽는 중·못 읽음은 연동 코드 카드가 스켈레톤·오류로 그린다
  const showSteps = !loading && !error && !paired;

  // 창을 닫은 뒤 이 화면에서 연결을 본 순간(순서 카드 → 연동 코드 카드). 주 흐름이 「확인했어요」로 창부터 닫고 OBS에
  // 넣는 것이라, 창을 닫을 때만 초점을 챙기면 여기서 초점이 있던 「코드 받기」가 사라져 body로 떨어지고 카드가 바뀐 것도
  // 읽히지 않는다(PR #539 2차 리뷰). 처음 들어올 때부터 연결된 회원은 건드리지 않는다 — 순서 카드를 본 적이 있을 때만이다.
  // 창이 떠 있으면 창이 알린다(IssuedCodeDialog) — 그 창을 닫을 때의 초점은 위 dialogClosed가 맡는다
  const [pairedAnnouncement, setPairedAnnouncement] = useState('');
  const wasShowingSteps = useRef(false);
  useEffect(() => {
    if (wasShowingSteps.current && paired && justIssued === null) {
      setPairedAnnouncement('플러그인이 연결됐어요');
      if (document.activeElement === null || document.activeElement === document.body) {
        topCardTitleRef.current?.focus();
      }
    }
    wasShowingSteps.current = showSteps;
  }, [showSteps, paired, justIssued]);
  // 배너(「다른 PC에 설치하나요?」)는 이미 연결한 회원의 것이다. 상태를 모르는 동안에도 세운다: 다운로드는 서버와 무관한
  // 고정 주소라, 읽기가 오래 걸리거나 실패했다고 받을 길까지 막을 까닭이 없다(GET 한도 30초 + 재시도 1회면 1분).
  // 맨 아래 블록이라 늦게 온 「연결한 적 없음」에 거둬도 위쪽이 밀리지 않는다 — 그때는 순서 카드 1번이 받을 길이다
  const showBanner = paired || error || (loading && slow);
  // 설치 파일 링크를 누른 적(POK-613) — 순서 카드가 1번을 끝낸 것으로 세우고 진한 버튼을 2번으로 넘긴다.
  // 창을 연 것만으로는 세지 않는다 — 안내만 읽고 닫은 사람에게는 받은 파일이 없다. 화면이 들고 있는 까닭은 받기 창을
  // 여는 길이 둘(순서 카드 1번 · 배너)이기 때문이다: 읽기가 늦어 배너로 먼저 받은 뒤 순서 카드가 붙어도 1번이 끝난
  // 것으로 선다(PR #549 리뷰). 이 탭에 기억해 두어 다른 메뉴에 다녀오거나 새로고침해도 남는다(pluginDownloadMark)
  const rememberedDownload = useRememberedPluginDownload();
  const [downloadClicked, setDownloadClicked] = useState(false);
  const downloaded = downloadClicked || rememberedDownload;
  const markDownloadClicked = () => {
    rememberPluginDownloadClicked();
    setDownloadClicked(true);
  };
  // 창을 연 것도 센다 — 받기 링크(plugin_download_clicked)까지 안 간 사람이 창은 봤는지 가른다
  const openDownload = (from: 'guide' | 'banner') => {
    setDownloadOpen(true);
    track('plugin_download_opened', { from });
  };

  return (
    <div>
      <SettingsPageHeader title="플러그인" />
      <div className={styles.stack}>
        {showSteps ? (
          <ConnectStepsCard
            titleRef={topCardTitleRef}
            codeIssued={code.issued}
            codeWaiting={codeWaiting}
            downloaded={downloaded}
            busy={busy}
            onDownload={() => openDownload('guide')}
            onIssue={issue}
          />
        ) : (
          <PairingCodeCard
            titleRef={topCardTitleRef}
            pairedAt={code.pairedAt}
            loading={loading}
            error={error}
            retrying={retrying}
            busy={busy}
            onIssue={issue}
            onRetry={retryStatus}
          />
        )}
        {/* 설치 과정 영상(POK-615) — 연결 여부와 상관없이 늘 있다. 배너보다 위다: 배너는 늦게 온 「연결한 적 없음」에
            거둬도 위쪽이 밀리지 않게 맨 아래 블록이어야 한다 */}
        <InstallVideoCard />
        {showBanner && <DownloadBanner onOpen={() => openDownload('banner')} />}
      </div>
      {/* 늘 있는 상태 칸 — 창을 닫은 뒤 이 화면에서 본 연결을 읽어 준다(위 pairedAnnouncement) */}
      <VisuallyHidden role="status">{pairedAnnouncement}</VisuallyHidden>
      <IssuedCodeDialog
        issued={justIssued}
        paired={justPaired}
        busy={busy}
        onClose={closeIssuedDialog}
        onIssueNew={issue}
      />
      <PluginDownloadDialog
        open={downloadOpen}
        onClose={() => setDownloadOpen(false)}
        detectedOs={detectedOs}
        onDownloadClick={markDownloadClicked}
      />
    </div>
  );
}
