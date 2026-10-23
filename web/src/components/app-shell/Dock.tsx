'use client';

import { useLayoutEffect, useRef, useState, type MouseEvent, type PointerEvent } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { House, Radio, Scissors, Settings, type LucideIcon } from 'lucide-react';
import { useActiveChannel } from '@/features/channel/useActiveChannel';
import { useChannelStore } from '@/stores/channel';
import { DOCK_HREFS, dockIndexOf, dockTransitionType, type DockHref } from './dockTransition';
import { ViewTransition } from './ViewTransition';
import styles from './Dock.module.css';

// 독 4개 (IA v5.7). DS Tabs는 URL을 모르고 asChild가 없어 앱 자체 컴포넌트로 만든다.
// 아이콘은 lucide로 통일한다 — 크기는 CSS(.icon)가 셸 배율로 덮어쓰므로 size prop을 두지 않는다.
//
// 꾹 눌러 좌우로 끌면 알약이 손가락을 따라 칸을 옮겨 가고, 놓는 칸으로 간다 — 알림 설정 ModeSegment와
// 같은 손맛이다. 그쪽은 칸 폭이 같아 CSS로 옮기지만 독은 칸 폭이 달라 옮긴 뒤 옛 자리에서 미끄러뜨린다(FLIP).
//
// - 알약(지금 탭)을 잡으면 살짝 작아지고 칸을 넘을 때마다 미끄러져 간다. 다른 칸에 놓으면 그 탭으로 간다.
// - 다른 칸을 누르면 그 칸만 흐려졌다가 놓는 칸으로 간다 — 그냥 누르고 떼면 예전처럼 탭 이동이다.
// - 끌다가 지금 탭에 도로 놓거나 위아래로 CANCEL_SLOP 넘게 벗어나 놓으면 취소.
// - 이동은 링크의 click이 아니라 pointerup이 한다: 독이 포인터를 잡고 있어 click은 대개 독으로 가 링크에 닿지
//   않는다. 브라우저에 따라 링크로 가는 click은 막는다(onClickCapture). 키보드 Enter와 ⌘·Ctrl 클릭(새 탭)은
//   예전처럼 링크가 맡는다.
// - 끌어 놓은 칸은 주소가 바뀔 때까지 알약을 붙든다 — 놓자마자 옛 칸으로 돌아갔다가 전환으로 다시 오지 않게.
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

interface Press {
  pointerId: number;
  /** 알약(지금 탭)을 잡았나 — 잡았으면 알약이 따라오고, 아니면 누른 칸만 흐려진다 */
  grabbed: boolean;
  index: number;
  /** 다른 칸으로 옮겨 간 적이 있나 — 있으면 지금 탭에 도로 놓은 것은 다시 누르기가 아니라 취소다 */
  moved: boolean;
}

/** 독 기준 알약의 좌우 가장자리(px) */
interface PillBox {
  left: number;
  right: number;
}

// 놓는 자리가 독에서 이만큼(px) 벗어나면 취소로 본다 — ModeSegment와 같은 너그러움
const CANCEL_SLOP = 40;
// pointerup이 옮긴 뒤 이 안에 오는 click은 같은 누름의 것이다
const CLICK_AFTER_UP_MS = 1000;
// 알약이 칸을 옮겨 가는 곡선 — ModeSegment 손잡이와 같다
const PILL_MOVE: KeyframeAnimationOptions = {
  duration: 320,
  easing: 'cubic-bezier(0.32, 0.72, 0, 1)',
};

// offset 값으로 잰다 — 잡았을 때의 scale과 독의 translateX가 섞이지 않는다. 알약의 offsetParent는 칸이다
function boxOf(pill: HTMLElement): PillBox {
  const left = (pill.parentElement?.offsetLeft ?? 0) + pill.offsetLeft;
  return { left, right: left + pill.offsetWidth };
}

export function Dock({ badges }: { badges?: DockBadges } = {}) {
  const pathname = usePathname();
  const router = useRouter();
  // 남의 채널이면 설정 탭은 알림 설정으로 연다(POK-390): 설정 첫 화면(플러그인)은 그 채널에서 막혀 있다. 서버의 redirect는
  // 고른 채널(브라우저 저장값)을 못 읽어 여기서 가른다. 저장소의 원시 선택값이 아니라 해석된 관계로 본다: as-editor를 못
  // 읽으면 내 채널로 보이는데 저장값은 남기므로, 원시값으로 가르면 사이드바는 「스트리머」인데 설정 탭만 알림으로 간다
  // (로컬 리뷰 1라운드). 독은 ChannelScope 밖이라 컨텍스트 대신 훅을 쓴다.
  // 아직 안 섰으면(as-editor를 기다리는 중) 저장된 선택으로 가른다: 그때 /settings로 보내면 막힌 플러그인 화면에 남는다
  // (PR #323 codex 2차). 선 뒤에는 해석된 관계가 정본이다
  const active = useActiveChannel();
  const storedOther = useChannelStore((s) => s.selected !== null && s.selected !== s.userId);
  const othersChannel = active.ready ? active.relation === 'EDITOR' : storedOther;
  const hrefOf = (href: DockHref) =>
    href === '/settings' && othersChannel ? '/settings/notifications' : href;

  const items = useRef<(HTMLAnchorElement | null)[]>([]);
  const pill = useRef<HTMLSpanElement>(null);
  // 알약을 다른 칸으로 옮기기 직전 자리 — 옮긴 뒤 레이아웃 효과가 여기서부터 미끄러뜨린다
  const pillFrom = useRef<PillBox | null>(null);
  const upAt = useRef(-Infinity);
  const [press, setPress] = useState<Press | null>(null);
  const [dropped, setDropped] = useState<number | null>(null);
  // 주소가 바뀌면 붙든 칸을 놓는다 — 남겨 두면 나중에 다른 길로 그 주소에 돌아왔을 때 알약이 엉뚱한 칸에 선다
  const [seenPath, setSeenPath] = useState(pathname);
  if (seenPath !== pathname) {
    setSeenPath(pathname);
    setDropped(null);
  }

  const activeIndex = dockIndexOf(pathname);
  const restIndex = dropped ?? activeIndex;
  const pillIndex = press?.grabbed ? press.index : restIndex;

  useLayoutEffect(() => {
    const from = pillFrom.current;
    const el = pill.current;
    pillFrom.current = null;
    if (!from || !el || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const to = boxOf(el);
    // 새 칸의 inset -1px 기준으로 옛 가장자리에서 출발한다
    el.animate(
      [
        { left: `${from.left - to.left - 1}px`, right: `${to.right - from.right - 1}px` },
        { left: '-1px', right: '-1px' },
      ],
      PILL_MOVE,
    );
  }, [pillIndex]);

  // 알약이 다른 칸으로 옮겨 갈 참이면 지금 자리를 적어 둔다
  const willMovePill = (next: number) => {
    if (next !== pillIndex && pill.current) pillFrom.current = boxOf(pill.current);
  };

  // 칸 폭이 제각각이라 칸마다 잰다. 칸 사이 틈이나 독 밖이면 가장 가까운 칸
  const indexAt = (clientX: number) => {
    let best = 0;
    let bestGap = Infinity;
    items.current.forEach((el, i) => {
      if (!el) return;
      const r = el.getBoundingClientRect();
      const gap = Math.max(r.left - clientX, clientX - r.right, 0);
      if (gap < bestGap) {
        best = i;
        bestGap = gap;
      }
    });
    return best;
  };

  const go = (index: number) => {
    const href = DOCK_HREFS[index]!;
    // 같은 탭이거나 독 밖에서 들어오면 방향이 없다 → 타입을 싣지 않아 슬라이드가 돌지 않는다
    const direction = dockTransitionType(pathname, href);
    router.push(hrefOf(href), direction ? { transitionTypes: [direction] } : undefined);
  };

  const onPointerDown = (e: PointerEvent<HTMLElement>) => {
    upAt.current = -Infinity;
    // 가운데·오른쪽 버튼과 ⌘·Ctrl·Shift 클릭(새 탭·새 창)은 링크에 맡긴다
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || press) return;
    // 끄는 동안 글자가 잡히지 않게
    e.preventDefault();
    // 독 밖으로 끌어도 독이 이벤트를 계속 받게
    e.currentTarget.setPointerCapture(e.pointerId);
    const index = indexAt(e.clientX);
    setPress({ pointerId: e.pointerId, grabbed: index === pillIndex, index, moved: false });
  };

  const onPointerMove = (e: PointerEvent<HTMLElement>) => {
    if (!press || e.pointerId !== press.pointerId) return;
    const index = indexAt(e.clientX);
    if (index === press.index) return;
    if (press.grabbed) {
      willMovePill(index);
      // 알약이 칸을 넘을 때 짧은 진동 — 지원하는 폰(안드로이드)에서만, 나머지는 조용히 넘어간다
      navigator.vibrate?.(8);
    }
    setPress({ ...press, index, moved: true });
  };

  const onPointerUp = (e: PointerEvent<HTMLElement>) => {
    if (!press || e.pointerId !== press.pointerId) return;
    upAt.current = e.timeStamp;
    const index = indexAt(e.clientX);
    const r = e.currentTarget.getBoundingClientRect();
    const outside = e.clientY < r.top - CANCEL_SLOP || e.clientY > r.bottom + CANCEL_SLOP;
    if (outside || (press.moved && index === restIndex)) {
      willMovePill(restIndex);
      setPress(null);
      return;
    }
    if (press.grabbed && index !== restIndex) {
      willMovePill(index);
      setDropped(index);
    }
    setPress(null);
    go(index);
  };

  // 잡고 있던 포인터만 푼다 — 알약은 제자리로 돌아간다.
  // lostpointercapture는 pointerup 뒤에도 오지만 그때는 이미 풀린 뒤라 아무 일도 하지 않는다
  const onPointerLost = (e: PointerEvent<HTMLElement>) => {
    if (!press || e.pointerId !== press.pointerId) return;
    willMovePill(restIndex);
    setPress(null);
  };

  // pointerup이 이미 옮겼다 — 같은 누름의 click이 링크에 닿아 한 번 더 옮기지 않게.
  // 키보드 Enter의 click은 detail이 0이라 그대로 링크가 옮긴다
  const onClickCapture = (e: MouseEvent<HTMLElement>) => {
    if (e.detail > 0 && e.timeStamp - upAt.current < CLICK_AFTER_UP_MS) e.preventDefault();
  };

  // 휴대폰은 링크를 꾹 누르면 메뉴를 띄운다 — 누르는 동안만 막는다(마우스 오른쪽 버튼 메뉴는 그대로)
  const onContextMenu = (e: MouseEvent<HTMLElement>) => {
    if (press) e.preventDefault();
  };

  return (
    // 독에는 일부러 view-transition-name을 주지 않는다 — 이름이 붙은 요소는 전환이 도는 동안
    // 히트 테스팅에서 빠져 :hover가 풀리고, 펼쳐져 있던 라벨이 접혔다 되돌아온다.
    // 이름 없이 root 스냅샷에 실려도 root는 이동 없이 교체될 뿐이라 독은 제자리에 있다 (app/shell.css).
    <nav
      className={styles.dock}
      aria-label="주요 탐색"
      data-grabbing={press?.grabbed ? '' : undefined}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerLost}
      onLostPointerCapture={onPointerLost}
      onClickCapture={onClickCapture}
      onContextMenu={onContextMenu}
    >
      {ITEMS.map(({ href, label, Icon }, i) => {
        const badge = badges?.[href];
        const direction = dockTransitionType(pathname, href);
        return (
          <Link
            key={href}
            ref={(el) => {
              items.current[i] = el;
            }}
            href={hrefOf(href)}
            className={styles.item}
            aria-current={i === activeIndex ? 'page' : undefined}
            transitionTypes={direction ? [direction] : undefined}
            // 링크를 끌면 브라우저가 링크 끌어 놓기를 시작해 포인터를 빼앗는다
            draggable={false}
            data-pill={i === pillIndex ? '' : undefined}
            data-dimmed={press && !press.grabbed && press.index === i ? '' : undefined}
          >
            {/* 알약은 한 칸에만 렌더한다 — 둘이면 브라우저가 옛/새 알약을 한 쌍으로 보지 못해 이동 대신
                각자 페이드된다. 평소엔 활성 칸, 끄는 동안엔 손가락 아래 칸이다.
                링크가 아니라 span이어야 한다 — Dock.test.tsx가 link 4개를 순서대로 비교한다. */}
            {i === pillIndex && (
              <ViewTransition name="pc-dock-pill">
                <span
                  ref={pill}
                  className={styles.pill}
                  data-grabbed={press?.grabbed ? '' : undefined}
                  aria-hidden
                />
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
