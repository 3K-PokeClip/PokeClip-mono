'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Clapperboard, House, Radio, Settings } from 'lucide-react';
import styles from './Dock.module.css';

// 독 4개 (IA v5.7). DS Tabs는 URL을 모르고 asChild가 없어 앱 자체 컴포넌트로 만든다.
const ITEMS = [
  { href: '/home', label: '홈', Icon: House },
  { href: '/live', label: '라이브', Icon: Radio },
  { href: '/clips', label: '클립', Icon: Clapperboard },
  { href: '/settings', label: '설정', Icon: Settings },
] as const;

export function Dock() {
  const pathname = usePathname();

  return (
    <nav className={styles.dock} aria-label="주요 탐색">
      {ITEMS.map(({ href, label, Icon }) => {
        const active = pathname === href || pathname.startsWith(`${href}/`);
        return (
          <Link
            key={href}
            href={href}
            className={styles.item}
            aria-current={active ? 'page' : undefined}
          >
            <Icon size={20} aria-hidden />
            <span className={styles.label}>{label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
