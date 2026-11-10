'use client';

import { Avatar } from '@/ui';
import { ChannelSwitcher, useChannelIdentity } from '@/features/channel/ChannelSwitcher';
import { NotificationBell } from '@/features/notifications/NotificationBell';
import { BrandSymbol } from './BrandSymbol';
import styles from './GlobalHeader.module.css';

// 전역 헤더 — 디자인 1a 상단(심볼 로고 + 워드마크 + 알림 + 프로필).
// 알림은 종을 누르면 아래로 열리는 창(POK-540). 프로필 아바타는 사이드바가 없는 홈에서 채널 스위처를 연다(POK-390): 로그아웃은 그 패널 아래 줄.
export function GlobalHeader() {
  const channel = useChannelIdentity();
  const othersChannel = channel.role === '편집자';

  return (
    <header>
      <div className={styles.inner}>
        <span className={styles.brand}>
          <BrandSymbol size={34} className={styles.brandSymbol} />
          <span className={styles.wordmark}>PokeClip</span>
        </span>
        <div className={styles.actions}>
          <span className={styles.bellWrap}>
            <NotificationBell place="header" />
          </span>
          <ChannelSwitcher side="bottom" align="end">
            {/* me 로딩 중엔 이름 없는 빈 아바타: 이니셜이 나중에 채워져도 어긋날 것이 없다.
                헤더엔 역할을 적을 자리가 없어 남의 채널이면 버튼 이름에만 역할을 남긴다 */}
            <button
              type="button"
              className={styles.avatarButton}
              aria-label={othersChannel ? '계정 메뉴 · 편집자' : '계정 메뉴'}
            >
              <Avatar size="sm" src={channel.photo ?? undefined} name={channel.name ?? undefined} />
            </button>
          </ChannelSwitcher>
        </div>
      </div>
    </header>
  );
}
