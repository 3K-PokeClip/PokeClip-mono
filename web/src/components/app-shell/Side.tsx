'use client';

import { useState } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  Bell,
  CreditCard,
  HelpCircle,
  History,
  Link2,
  Menu,
  Plug,
  Radio,
  User,
  Users,
  type LucideIcon,
} from 'lucide-react';
import { Avatar, IconButton } from '@/ui';
import { isDockHrefActive } from './dockTransition';
import styles from './Side.module.css';

// 독 그룹별 좌측 사이드바 (디자인 Side). 그룹이 달라도 껍데기는 같고 메뉴만 갈리므로
// 컴포넌트 하나가 menu prop으로 메뉴를 고른다 — 그룹마다 복제하면 접기 동작·치수가 곧 어긋난다.

interface Item {
  key: string;
  label: string;
  Icon: LucideIcon;
  href?: string; // 없으면 비활성 — 각 하위 티켓에서 라우트가 생기면 href를 추가한다
}

interface Group {
  title: string;
  items: Item[];
}

/** 독 그룹 key. 라우트 세그먼트(`/broadcast`·`/settings`)와 같은 이름을 쓴다. */
export type SideMenu = 'broadcast' | 'settings';

// IA v5.7. 라우팅된 화면만 href를 갖는다 — 나머지는 별도 티켓.
const MENUS: Record<SideMenu, Group[]> = {
  // 「방송」 그룹(POK-210). livenow·vod는 그룹 안의 화면이라 이름을 바꾸지 않는다 —
  // livenow는 실제로 라이브고, 바뀐 것은 그 둘을 담는 그룹의 이름이다.
  broadcast: [
    {
      title: '방송',
      items: [
        { key: 'livenow', label: '라이브 대시보드', Icon: Radio, href: '/broadcast/livenow' },
        { key: 'vod', label: '지난 방송', Icon: History },
      ],
    },
  ],
  settings: [
    {
      title: '채널 · 협업자',
      items: [
        { key: 'link', label: '채널 연동', Icon: Link2, href: '/settings/channels' },
        { key: 'editors', label: '편집자 관리', Icon: Users },
      ],
    },
    {
      title: '환경설정',
      items: [
        { key: 'plugin', label: '플러그인', Icon: Plug, href: '/settings/plugin' },
        { key: 'noti', label: '알림 설정', Icon: Bell },
        { key: 'billing', label: '구독 · 결제', Icon: CreditCard },
        { key: 'account', label: '계정', Icon: User },
        { key: 'help', label: '도움말 · 문의', Icon: HelpCircle },
      ],
    },
  ],
};

export function Side({ menu }: { menu: SideMenu }) {
  const pathname = usePathname();
  const [collapsed, setCollapsed] = useState(false);

  return (
    <aside className={styles.sidebar} data-collapsed={collapsed}>
      <div className={styles.brand}>
        <span className={styles.brandMark}>
          <Image
            src="/brand/pokeclip-symbol.svg"
            alt=""
            width={28}
            height={28}
            className={styles.brandLogo}
          />
        </span>
        <span className={styles.brandName}>PokeClip</span>
        <span className={styles.brandToggle}>
          <IconButton
            variant="ghost"
            size="sm"
            aria-label={collapsed ? '사이드바 펼치기' : '사이드바 접기'}
            aria-expanded={!collapsed}
            onClick={() => setCollapsed((c) => !c)}
          >
            {/* 크기는 CSS가 셸 배율로 정한다 — size prop은 덮어써져 무의미 */}
            <Menu aria-hidden />
          </IconButton>
        </span>
      </div>
      {MENUS[menu].map((group, i) => (
        <div key={group.title}>
          {i > 0 && <div className={styles.divider} />}
          <div className={styles.groupTitle}>{group.title}</div>
          <nav className={styles.nav} aria-label={group.title}>
            {group.items.map(({ key, label, Icon, href }) => {
              const content = (
                <>
                  {/* 크기는 CSS(.itemIcon)가 셸 배율로 정한다 — size prop은 덮어써져 무의미 */}
                  <Icon aria-hidden className={styles.itemIcon} />
                  <span className={styles.label}>{label}</span>
                </>
              );
              if (!href) {
                return (
                  <span key={key} className={styles.item} aria-disabled="true">
                    {content}
                  </span>
                );
              }
              // 하위 경로 판정은 독과 같은 규칙을 쓴다 — 복사본을 만들면 조용히 갈라진다
              const active = isDockHrefActive(pathname, href);
              return (
                <Link
                  key={key}
                  href={href}
                  className={styles.item}
                  aria-current={active ? 'page' : undefined}
                >
                  {content}
                </Link>
              );
            })}
          </nav>
        </div>
      ))}
      <div className={styles.userBlock}>
        <Avatar size="sm" name="너구리" className={styles.userAvatar} />
        <div className={styles.userText}>
          <div className={styles.userName}>너구리</div>
          <div className={styles.userRole}>스트리머 · Pro</div>
        </div>
        <div className={styles.bellWrap}>
          <IconButton variant="ghost" size="sm" aria-label="알림">
            <Bell aria-hidden />
          </IconButton>
          <span className={styles.bellDot} />
        </div>
      </div>
    </aside>
  );
}
