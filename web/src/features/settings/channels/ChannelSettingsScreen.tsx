'use client';

import { Button } from '@/ui';
import { SettingsPageHeader } from '../SettingsPageHeader';
import { ChannelRow } from './ChannelRow';
import { SoopMark } from './ChannelMarks';
import { ChzzkChannelRow } from './ChzzkChannelRow';
import { UnlinkChzzkDialog } from './UnlinkChzzkDialog';
import { YoutubeChannelSection } from './YoutubeChannelSection';
import { useChzzkLinkState } from './useChzzkLinkState';
import styles from './ChannelSettingsScreen.module.css';

// 디자인 1k 설정 · 채널 연동 (POK-205). 조립만 한다 — 상태 판단은 useChzzkLinkState,
// 상태별 표현은 ChzzkChannelRow가 갖는다.
export function ChannelSettingsScreen() {
  const chzzk = useChzzkLinkState();

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
          <ChannelRow
            icon={<SoopMark />}
            iconClassName={styles.soopIcon}
            name="SOOP"
            meta="연동하면 SOOP 방송에서도 하이라이트를 감지해요"
            /* SOOP은 IA대로 자리만 — 백엔드가 없다. 있는 척하지 않으려고 누를 수 없게 둔다. */
            action={
              <Button variant="soft" size="sm" disabled>
                연동
              </Button>
            }
          />
        </div>
      </section>
      <YoutubeChannelSection />
      <UnlinkChzzkDialog
        open={chzzk.confirmOpen}
        busy={chzzk.unlinking}
        onCancel={chzzk.closeConfirm}
        onConfirm={chzzk.confirmUnlink}
      />
    </div>
  );
}
