'use client';

import styles from './LiveScreen.module.css';
import { GlassPlayer } from '@/features/player/GlassPlayer';
import { ChatVolumeCard } from './ChatVolumeCard';
import { ChatWarningBanner } from './ChatWarningBanner';
import { HighlightCardList } from './HighlightCardList';
import { LiveHeader } from './LiveHeader';
import { useLiveMockState } from './useLiveMockState';

// 디자인 1b — 라이브 대시보드. 전폭 자체 헤더를 가지므로
// ScreenContainer 대신 내부 .container로 본문 폭을 잡는다.
export function LiveScreen() {
  const { stream, highlights, hiddenCount, chatVolume, chatWarning } = useLiveMockState();

  return (
    <div className={styles.screen}>
      <LiveHeader stream={stream} />
      <div className={styles.container}>
        <div className={styles.grid}>
          <div className={styles.main}>
            <div className={styles.playerFrame}>
              <GlassPlayer
                channelName={stream.channelName}
                title={stream.title}
                viewersNote={`시청자 ${stream.viewers}`}
                embed
                simulationOptions={{ initialUptimeSeconds: stream.uptimeSeconds }}
              />
            </div>
            <ChatVolumeCard series={chatVolume} />
            {chatWarning ? <ChatWarningBanner /> : null}
          </div>
          <aside className={styles.aside} aria-label="하이라이트 카드">
            <HighlightCardList highlights={highlights} hiddenCount={hiddenCount} />
          </aside>
        </div>
      </div>
    </div>
  );
}
