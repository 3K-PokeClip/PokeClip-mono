import { Clock } from 'lucide-react';
import { Avatar } from '@/ui';
import styles from './GlassPlayer.module.css';
import { formatUptime } from './playerMath';

// 플레이어 상단 오버레이 — 채널 필 + 방송 경과 필 (시안 영상 플레이어 글래스).
// 뒤로가기는 1b에서 페이지 헤더가 사라진 뒤 사이드 메뉴가 담당하므로 여기엔 없다.
export function PlayerTopOverlay({
  channelName,
  title,
  viewersNote,
  uptimeSeconds,
}: {
  channelName: string;
  title: string;
  viewersNote: string;
  uptimeSeconds: number;
}) {
  return (
    <div className={styles.topOverlay}>
      <div className={styles.channelPill}>
        <Avatar size="sm" name={channelName} />
        <div className={styles.channelText}>
          <div className={styles.channelRow}>
            <span className={styles.channelName}>{channelName}</span>
            <span className={styles.liveBadge}>
              <span className={styles.liveBadgeDot} aria-hidden />
              LIVE
            </span>
          </div>
          <div className={styles.channelSub}>
            {title} · {viewersNote}
          </div>
        </div>
      </div>
      <div className={styles.uptimePill}>
        <Clock size={14} aria-hidden />
        <span aria-label="방송 경과 시간">{formatUptime(uptimeSeconds)}</span>
      </div>
    </div>
  );
}
