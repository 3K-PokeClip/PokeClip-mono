'use client';

import { Avatar, IconButton } from '@pokeclip/ui';
import { Bell } from 'lucide-react';
import styles from './GlobalHeader.module.css';

// 전역 헤더 자리 — 로고·알림·프로필 (POK-99: 자리만, 동작은 각 화면 티켓에서)
export function GlobalHeader() {
  return (
    <header className={styles.header}>
      <span className={styles.logo}>PokeClip</span>
      <div className={styles.actions}>
        <IconButton variant="ghost" size="sm" aria-label="알림">
          <Bell size={18} aria-hidden />
        </IconButton>
        <Avatar size="sm" alt="프로필" />
      </div>
    </header>
  );
}
