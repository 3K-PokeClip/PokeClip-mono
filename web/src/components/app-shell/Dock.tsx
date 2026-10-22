'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { House, Radio, Scissors, Settings, type LucideIcon } from 'lucide-react';
import { useActiveChannel } from '@/features/channel/useActiveChannel';
import { useChannelStore } from '@/stores/channel';
import { DOCK_HREFS, dockTransitionType, isDockHrefActive, type DockHref } from './dockTransition';
import { ViewTransition } from './ViewTransition';
import styles from './Dock.module.css';

// 독 4개 (IA v5.7). DS Tabs는 URL을 모르고 asChild가 없어 앱 자체 컴포넌트로 만든다.
// 아이콘은 lucide로 통일한다 — 크기는 CSS(.icon)가 셸 배율로 덮어쓰므로 size prop을 두지 않는다.
const ITEM_META: Record<DockHref, { label: string; Icon: LucideIcon }> = {
  '/home': { label: '홈', Icon: House },
  '/broadcast': { label: '방송', Icon: Radio },
  // 디자인의 클립 아이콘은 가위다 (clapperboard 아님)
  '/clips': { label: '클립', Icon: Scissors },
  '/settings': { label: '설정', Icon: Settings },
};

// 순서는 DOCK_HREFS에서 그대로 가져온다 — 따로 나열하면 전환 방향과 조용히 어긋날 수 있다.
const ITEMS = DOCK_HREFS.map((href) => ({ href, ...ITEM_META[href] }));

/** 항목별 알림 수. 실데이터가 붙기 전까진 비어 있다. */
export type DockBadges = Partial<Record<DockHref, number>>;

export function Dock({ badges }: { badges?: DockBadges } = {}) {
  const pathname = usePathname();
  // 남의 채널이면 설정 탭은 알림 설정으로 연다(POK-390): 설정 첫 화면(플러그인)은 그 채널에서 막혀 있다. 서버의 redirect는
  // 고른 채널(브라우저 저장값)을 못 읽어 여기서 가른다. 저장소의 원시 선택값이 아니라 해석된 관계로 본다: as-editor를 못
  // 읽으면 내 채널로 보이는데 저장값은 남기므로, 원시값으로 가르면 사이드바는 「스트리머」인데 설정 탭만 알림으로 간다
  // (로컬 리뷰 1라운드). 독은 ChannelScope 밖이라 컨텍스트 대신 훅을 쓴다.
  // 아직 안 섰으면(as-editor를 기다리는 중) 저장된 선택으로 가른다: 그때 /settings로 보내면 막힌 플러그인 화면에 남는다
  // (PR #323 codex 2차). 선 뒤에는 해석된 관계가 정본이다
  const active = useActiveChannel();
  const storedOther = useChannelStore((s) => s.selected !== null && s.selected !== s.userId);
  const othersChannel = active.ready ? active.relation === 'EDITOR' : storedOther;

  return (
    // 독에는 일부러 view-transition-name을 주지 않는다 — 이름이 붙은 요소는 전환이 도는 동안
    // 히트 테스팅에서 빠져 :hover가 풀리고, 펼쳐져 있던 라벨이 접혔다 되돌아온다.
    // 이름 없이 root 스냅샷에 실려도 root는 이동 없이 교체될 뿐이라 독은 제자리에 있다 (app/shell.css).
    <nav className={styles.dock} aria-label="주요 탐색">
      {ITEMS.map(({ href, label, Icon }) => {
        const active = isDockHrefActive(pathname, href);
        const badge = badges?.[href];
        // 같은 탭이거나 독 밖에서 들어오면 방향이 없다 → 타입을 싣지 않아 슬라이드가 돌지 않는다
        const direction = dockTransitionType(pathname, href);
        return (
          <Link
            key={href}
            href={href === '/settings' && othersChannel ? '/settings/notifications' : href}
            className={styles.item}
            aria-current={active ? 'page' : undefined}
            transitionTypes={direction ? [direction] : undefined}
          >
            {/* 활성 항목에만 렌더해야 브라우저가 옛/새 알약을 한 쌍으로 보고 사이를 보간한다.
                링크가 아니라 span이어야 한다 — Dock.test.tsx가 link 4개를 순서대로 비교한다. */}
            {active && (
              <ViewTransition name="pc-dock-pill">
                <span className={styles.pill} aria-hidden />
              </ViewTransition>
            )}
            <Icon className={styles.icon} strokeWidth={1.8} aria-hidden />
            <span className={styles.label}>{label}</span>
            {badge ? <span className={styles.badge}>{badge}</span> : null}
          </Link>
        );
      })}
    </nav>
  );
}
