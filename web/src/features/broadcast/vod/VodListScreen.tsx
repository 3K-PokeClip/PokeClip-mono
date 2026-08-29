'use client';

import { Video } from 'lucide-react';
import { Badge } from '@/ui';
import { useVodListMockState, type VodListOptions } from './useVodListMockState';
import { VodPeriodFilter } from './VodPeriodFilter';
import { VodRow } from './VodRow';
import styles from './VodListScreen.module.css';

// 시안 1f — 지난 방송 목록. VOD 뷰어(1c)로 가는 진입 관문이고, 60일 보관 기한을
// 행마다 D-day로 말한다. 목록도 세로로 쌓여 문서 스크롤로 내려간다(1b와 같다).
//
// 본문 랜드마크는 각 화면이 세운다 — broadcast/layout은 Side와 감싸는 div까지만 준다.
//
// 시안에 있으나 이 티켓에 없는 것 둘: 「카드 저장」 구제 동작(만료되면 지우는 것이 원칙이라
// ADR-004와 어긋나고, 시안 오류로 판정됐다)과 풀 VOD 다운로드 플로우(명세·계약에 없는
// 신규 기능이라 명세 개정이 먼저다). 행의 다운로드 버튼은 자리만 두고 비활성이다.
export function VodListScreen(options: VodListOptions = {}) {
  const { now, broadcasts, totalCount, visuals, filter, setFilter, customRange, setCustomRange } =
    useVodListMockState(options);

  return (
    <main className={styles.container}>
      <div className={styles.header}>
        <h1 className={styles.title}>지난 방송</h1>
        {totalCount > 0 ? (
          <Badge tone="neutral" variant="soft" size="sm">
            {totalCount}
          </Badge>
        ) : null}
        {totalCount > 0 ? (
          <VodPeriodFilter
            filter={filter}
            onFilterChange={setFilter}
            customRange={customRange}
            onCustomRangeChange={setCustomRange}
          />
        ) : null}
      </div>

      {totalCount === 0 ? (
        <div className={styles.emptyCard}>
          <span className={styles.emptyIcon}>
            <Video size={21} aria-hidden="true" />
          </span>
          <p className={styles.emptyTitle}>아직 지난 방송이 없어요</p>
          <p className={styles.emptyBody}>
            방송을 켜면 종료 후 VOD가 여기에 쌓여요. VOD는 60일 동안 보관되고, 만료 전에 풀 영상을
            내려받을 수 있어요.
          </p>
        </div>
      ) : broadcasts.length === 0 ? (
        // 「아직 없다」와 「이 기간에 없다」는 다른 말이다 — 빈 상태 카드를 재사용하면 거짓이 된다
        <p className={styles.filterEmpty}>이 기간에 끝난 방송이 없어요.</p>
      ) : (
        <ul className={styles.list} aria-label="지난 방송 목록">
          {broadcasts.map((item) => (
            <VodRow key={item.streamId} item={item} visual={visuals[item.streamId]} now={now} />
          ))}
        </ul>
      )}
    </main>
  );
}
