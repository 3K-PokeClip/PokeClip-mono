'use client';

import Image from 'next/image';
import { Avatar, DropdownMenu, IconButton } from '@/ui';
import { Bell } from 'lucide-react';
import { useLogout, useMe } from '@/features/auth/useSession';
import styles from './GlobalHeader.module.css';

// 전역 헤더 — 디자인 1a 상단(심볼 로고 + 워드마크 + 알림 + 프로필).
// 알림 패널 동작은 후속 티켓. 프로필은 POK-101의 "최소만" — 아바타와 로그아웃 항목 하나.
export function GlobalHeader() {
  const { data: me } = useMe();
  const logout = useLogout();

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
          <DropdownMenu>
            <DropdownMenu.Trigger>
              {/* me 로딩 중엔 이름 없는 빈 아바타 — 이니셜이 나중에 채워져도 어긋날 것이 없다 */}
              <button type="button" className={styles.avatarButton} aria-label="계정 메뉴">
                <Avatar size="sm" src={me?.profileImageUrl} name={me?.name} />
              </button>
            </DropdownMenu.Trigger>
            <DropdownMenu.Content align="end">
              <DropdownMenu.Item onSelect={() => void logout()}>로그아웃</DropdownMenu.Item>
            </DropdownMenu.Content>
          </DropdownMenu>
        </div>
      </div>
    </header>
  );
}
