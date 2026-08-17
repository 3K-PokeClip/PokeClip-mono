'use client';

import Image from 'next/image';
import { Avatar, IconButton } from '@/ui';
import { Bell } from 'lucide-react';
import styles from './GlobalHeader.module.css';

// 전역 헤더 — 디자인 1a 상단(심볼 로고 + 워드마크 + 알림 + 프로필).
// 알림 패널·프로필 메뉴 동작은 후속 티켓, 사용자 정보는 인증 도입(POK-101) 전까지 목업.
const MOCK_USER_NAME = '게임하는너구리';

export function GlobalHeader() {
  return (
    <header>
      <div className={styles.inner}>
        <span className={styles.brand}>
          <Image src="/brand/pokeclip-symbol.svg" alt="" width={34} height={34} />
          <span className={styles.wordmark}>PokeClip</span>
        </span>
        <div className={styles.actions}>
          <span className={styles.bellWrap}>
            <IconButton variant="ghost" size="md" aria-label="알림">
              <Bell size={19} aria-hidden />
            </IconButton>
            {/* 읽지 않은 알림 점 — 알림 데이터 연동 전까지 목업 표시 */}
            <span className={styles.unreadDot} aria-hidden />
          </span>
          <Avatar size="sm" name={MOCK_USER_NAME} />
        </div>
      </div>
    </header>
  );
}
