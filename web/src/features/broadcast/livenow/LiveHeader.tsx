import Link from 'next/link';
import { ChevronLeft } from 'lucide-react';
import { Avatar } from '@/ui';
import styles from './LiveScreen.module.css';
import type { LiveStream } from './useLiveMockState';

// 디자인 1b 상단 — 라이브 화면 자체 헤더 (전역 헤더 대신).
export function LiveHeader({ stream }: { stream: LiveStream }) {
  return (
    <header className={styles.header}>
      <Link href="/home" className={styles.backLink} aria-label="홈으로">
        <ChevronLeft size={17} aria-hidden />
      </Link>
      <span className={styles.livePill}>
        <span className={styles.livePillDot} aria-hidden />
        LIVE
      </span>
      <div className={styles.headerTitleBlock}>
        <h1 className={styles.headerTitle}>{stream.title}</h1>
        <div className={styles.headerMeta}>
          {stream.platform} · {stream.startedNote} · {stream.uptimeLabel} 경과
        </div>
      </div>
      <div className={styles.headerRight}>
        <div className={styles.editorChip}>
          <Avatar size="xs" name={stream.editorName} />
          <span>{stream.editorName} 접속 중</span>
        </div>
        <div className={styles.headerDivider} aria-hidden />
        <span className={styles.viewerCount}>시청자 {stream.viewers}</span>
      </div>
    </header>
  );
}
