'use client';

import { useLayoutEffect, useRef, useState } from 'react';
import clsx from 'clsx';
import { LoadingRegion, Skeleton } from '@/ui';
import styles from './LibraryScreen.module.css';

/** 한 화면 위쪽을 채우는 줄 수 — 1440px 창의 5열 카드(약 214×380)면 두 줄이 900px 화면을 거의 채운다 */
const ROWS = 2;

/**
 * 열 수를 못 잴 때(서버 렌더 · jsdom)의 장 수. 1280px 창(사이드바 펼침)의 4열이면 두 줄이다 — 열 수를 재고 나면
 * 열 × ROWS로 바꾼다
 */
const FALLBACK_CARDS = 8;

const u = (n: number) => `calc(${n} * var(--pc-u))`;

/**
 * 격자의 실제 열 수. auto-fill이 정한 트랙 목록(「213.6px 213.6px …」)을 센다. 배치 전이라 풀리지 않은 값(「repeat(…)」 ·
 * 빈 값 — jsdom은 이 칸을 모른다)이면 0
 */
function columnCount(grid: HTMLElement): number {
  const tracks = (getComputedStyle(grid).gridTemplateColumns ?? '').trim().split(/\s+/);
  return tracks.every((track) => /^\d+(\.\d+)?px$/.test(track)) ? tracks.length : 0;
}

// 첫 읽기가 끝나기 전의 보관함 격자(POK-443). 로드 뒤 격자와 같은 클래스(.grid · .cardItem · .card)를 그대로 써서, 데이터가
// 와도 열 수·카드 크기(9:16 · 반경 12u)가 바뀌지 않는다. 패널이 열려 격자가 촘촘해져도 data-panel-open이 같은 규칙을 탄다.
// 칩 줄·개수는 편집본이 있을 때만 서므로 미리 그리지 않는다(편집본이 0개인 계정에서 거짓이 된다).
//
// 열 수는 창 폭 · 사이드바 접힘 · 패널에 따라 4~7열로 갈려서 장 수를 고정하면 둘째 줄이 비어 「편집본이 몇 개 없다」처럼
// 보인다(8장이면 5열 화면에서 3장, 7열에서 1장). 칠하기 전(layout effect)에 실제 열 수를 재어 두 줄을 채운다.
export function LibrarySkeleton({ panelOpen }: { panelOpen: boolean }) {
  const gridRef = useRef<HTMLUListElement>(null);
  const [cards, setCards] = useState(FALLBACK_CARDS);
  useLayoutEffect(() => {
    const columns = gridRef.current ? columnCount(gridRef.current) : 0;
    if (columns > 0) setCards(columns * ROWS);
  }, []);

  return (
    <LoadingRegion label="편집본을 불러오는 중">
      <ul ref={gridRef} className={styles.grid} data-panel-open={panelOpen} aria-hidden="true">
        {Array.from({ length: cards }, (_, i) => (
          <li key={i} className={styles.cardItem}>
            {/* Skeleton은 폭·높이·반경을 인라인으로 덮으므로 .card의 값(폭 100% · 높이는 9:16 비율 · 12u)을 다시 준다 */}
            <Skeleton
              className={clsx(styles.card, styles.cardSkeleton)}
              width="100%"
              height="auto"
              style={{ borderRadius: u(12) }}
            />
          </li>
        ))}
      </ul>
    </LoadingRegion>
  );
}
