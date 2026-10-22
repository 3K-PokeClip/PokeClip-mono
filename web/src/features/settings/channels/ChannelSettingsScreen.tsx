'use client';

import { useEffect } from 'react';
import { useOnboardingHydration, useOnboardingStore } from '@/stores/onboarding';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { ChzzkChannelRow } from './ChzzkChannelRow';
import { SoopChannelRow } from './SoopChannelRow';
import { UnlinkChzzkDialog } from './UnlinkChzzkDialog';
import { UnlinkSoopDialog } from './UnlinkSoopDialog';
import { UnlinkYoutubeDialog } from './UnlinkYoutubeDialog';
import { YoutubeChannelSection } from './YoutubeChannelSection';
import { useChzzkLinkState } from './useChzzkLinkState';
import { useSoopLinkState } from './useSoopLinkState';
import { useYoutubeLinkState } from './useYoutubeLinkState';
import styles from './ChannelSettingsScreen.module.css';

// 디자인 1k 설정 · 채널 연동 (POK-205 치지직 · POK-221 유튜브 · POK-396 SOOP). 조립만 한다 —
// 상태 판단은 use*LinkState 훅, 상태별 표현은 *ChannelRow가 갖는다.
export function ChannelSettingsScreen() {
  // 아래 미러링이 저장된 값과 비교하므로 스토어가 먼저 서 있어야 한다. 온보딩 스토어를 세우는 곳이
  // /home의 OnboardingController뿐이라, 이 화면으로 바로 들어오면 정적 기본값(false)과 비교하게 되고
  // stale 플래그가 그대로 살아남는다. 선언 순서상 이 이펙트가 아래 미러링 이펙트보다 먼저 돈다.
  useOnboardingHydration();
  const chzzk = useChzzkLinkState();
  const soop = useSoopLinkState();
  const youtube = useYoutubeLinkState();
  const setChannelLinked = useOnboardingStore((s) => s.setChannelLinked);

  // 홈 시작 가이드 1단계 「방송 채널 연동」은 이 플래그를 본다. 필자는 이 이펙트 하나뿐이다(쓰는 곳이
  // 둘이면 갈라진다). 방송 채널은 치지직이나 SOOP 중 하나면 된다(POK-396 C8). 각자의 `linked`는
  // ACTIVE·EXPIRED만 true인 서버 값 그대로다. 하나라도 아직 모르면 건드리지 않는다: 모르는 쪽을
  // false로 치면 SOOP만 연동한 사람의 플래그가 치지직 조회 실패 한 번에 꺼진다.
  const chzzkLinked = chzzk.linked;
  const soopLinked = soop.linked;
  useEffect(() => {
    if (chzzkLinked === undefined || soopLinked === undefined) return;
    const linked = chzzkLinked || soopLinked;
    if (useOnboardingStore.getState().channelLinked === linked) return; // 불필요한 localStorage 쓰기 차단
    setChannelLinked(linked);
  }, [chzzkLinked, soopLinked, setChannelLinked]);

  return (
    <div className={styles.screen}>
      {/* 1k의 상단은 제목 한 줄이다 — 보조 설명을 두지 않는다 */}
      <SettingsPageHeader title="채널 연동" />
      {/* aria-label 대신 제목 참조 — 제목 텍스트와 접근 이름이 중복 낭독되지 않게 (PairingCodeCard 선례) */}
      <section className={styles.broadcastSection} aria-labelledby="broadcast-channels-title">
        <h2 id="broadcast-channels-title" className={styles.sectionTitle}>
          방송 채널
        </h2>
        <div className={styles.rows}>
          <ChzzkChannelRow state={chzzk} />
          <SoopChannelRow state={soop} />
        </div>
      </section>
      <YoutubeChannelSection state={youtube} />
      <UnlinkChzzkDialog
        open={chzzk.confirmOpen}
        busy={chzzk.unlinking}
        onCancel={chzzk.closeConfirm}
        onConfirm={chzzk.confirmUnlink}
      />
      <UnlinkSoopDialog
        open={soop.confirmOpen}
        busy={soop.unlinking}
        onCancel={soop.closeConfirm}
        onConfirm={soop.confirmUnlink}
      />
      <UnlinkYoutubeDialog
        open={youtube.confirmOpen}
        busy={youtube.unlinking}
        onCancel={youtube.closeConfirm}
        onConfirm={youtube.confirmUnlink}
      />
    </div>
  );
}
