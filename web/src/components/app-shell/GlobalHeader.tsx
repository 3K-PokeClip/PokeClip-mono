'use client';

import Image from 'next/image';
import { Avatar, IconButton } from '@/ui';
import { Bell } from 'lucide-react';
import { ChannelSwitcher, RoleChip, useChannelIdentity } from '@/features/channel/ChannelSwitcher';
import styles from './GlobalHeader.module.css';

// 전역 헤더 — 디자인 1a 상단(심볼 로고 + 워드마크 + 알림 + 프로필).
// 알림 패널 동작은 후속 티켓. 프로필 아바타는 사이드바가 없는 홈에서 채널 스위처를 연다(POK-390): 로그아웃은 그 패널 아래 줄.
export function GlobalHeader() {
  const channel = useChannelIdentity();

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
          {/* 남의 채널일 때만 칩을 단다: 홈에는 사이드바가 없어 「여기는 내 채널이 아니다」를 알릴 자리가 여기뿐이다 */}
          {channel.role === '편집자' && <RoleChip role={channel.role} />}
          <ChannelSwitcher side="bottom" align="end">
            {/* me 로딩 중엔 이름 없는 빈 아바타: 이니셜이 나중에 채워져도 어긋날 것이 없다 */}
            <button type="button" className={styles.avatarButton} aria-label="계정 메뉴">
              <Avatar size="sm" src={channel.photo ?? undefined} name={channel.name ?? undefined} />
            </button>
          </ChannelSwitcher>
        </div>
      </div>
    </header>
  );
}
