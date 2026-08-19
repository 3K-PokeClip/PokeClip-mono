'use client';

import { Suspense } from 'react';
import styles from './LiveScreen.module.css';
import { GlassPlayer } from '@/features/player/GlassPlayer';
import { useMediaSource } from '@/features/player/mediaSource';
import { ChatVolumeCard } from './ChatVolumeCard';
import { ChatWarningBanner } from './ChatWarningBanner';
import { HighlightCardList } from './HighlightCardList';
import { LiveHeader } from './LiveHeader';
import { useLiveMockState, type LiveStream } from './useLiveMockState';

// 디자인 1b — 라이브 대시보드. 전폭 자체 헤더를 가지므로
// ScreenContainer 대신 내부 .container로 본문 폭을 잡는다.

// useSearchParams(?stream=)는 프리렌더에서 가장 가까운 Suspense 경계까지 CSR로 전환한다 —
// 화면 전체가 아니라 플레이어만 빠지도록 여기서 분리하고 경계는 playerFrame 안에 둔다.
function LivePlayer({ stream }: { stream: LiveStream }) {
  // env 미설정이면 null → GlassPlayer가 시뮬레이션으로 폴백 (테스트 포함)
  const src = useMediaSource();
  return (
    <GlassPlayer
      channelName={stream.channelName}
      title={stream.title}
      viewersNote={`시청자 ${stream.viewers}`}
      src={src}
      embed
      simulationOptions={{ initialUptimeSeconds: stream.uptimeSeconds }}
    />
  );
}

export function LiveScreen() {
  const { stream, highlights, hiddenCount, chatVolume, chatWarning } = useLiveMockState();

  return (
    <div>
      <LiveHeader stream={stream} />
      {/* 본문 랜드마크 — 헤더는 밖에 두고 본문만 감싼다 (ScreenContainer 선례) */}
      <main className={styles.container}>
        <div className={styles.grid}>
          <div className={styles.main}>
            <div className={styles.playerFrame}>
              {/* 폴백은 플레이어와 같은 16:9 빈 블록 — 서스펜드 중에도 레이아웃이 흔들리지 않는다 */}
              <Suspense fallback={<div className={styles.playerFallback} aria-hidden />}>
                <LivePlayer stream={stream} />
              </Suspense>
            </div>
            <ChatVolumeCard series={chatVolume} />
            {chatWarning ? <ChatWarningBanner /> : null}
          </div>
          <aside className={styles.aside} aria-label="하이라이트 카드">
            <HighlightCardList highlights={highlights} hiddenCount={hiddenCount} />
          </aside>
        </div>
      </main>
    </div>
  );
}
