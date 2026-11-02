import clsx from 'clsx';
import { useId, type ReactNode } from 'react';
import styles from './ChannelSettingsScreen.module.css';

// 디자인 1k 채널 행 카드의 표현 껍데기. 치지직·SOOP·유튜브 세 곳이 같은 규격을 쓴다.
// @/ui로 올리지 않는다 — 이 화면 전용 레이아웃이지 DS 프리미티브가 아니다.
//
// 행을 role="group"으로 묶고 채널명으로 이름을 준다 — 상태 배지·보조설명·버튼이 어느
// 채널의 것인지 낱개로는 알 수 없다. 「연동」 버튼이 행마다 있어 이름만으로는 구분되지 않는
// 문제도 같이 풀린다.
export function ChannelRow({
  icon,
  iconClassName,
  name,
  badge,
  meta,
  action,
}: {
  icon: ReactNode;
  /** 아이콘 타일 배경 — 플랫폼마다 다르다(치지직 인셋 / SOOP 흰 타일). */
  iconClassName?: string;
  name: string;
  badge?: ReactNode;
  meta: ReactNode;
  /** 버튼 하나 또는 둘(둘이면 복구 동작이 앞이다). 누를 것이 없으면 null. */
  action: ReactNode;
}) {
  const nameId = useId();

  return (
    <div className={styles.row} role="group" aria-labelledby={nameId}>
      <span className={clsx(styles.channelIcon, iconClassName)}>{icon}</span>
      <div className={styles.rowBody}>
        <div className={styles.rowTitleRow}>
          <span className={styles.rowName} id={nameId}>
            {name}
          </span>
          {badge}
        </div>
        <div className={styles.rowMeta}>{meta}</div>
      </div>
      {action !== null && <div className={styles.rowActions}>{action}</div>}
    </div>
  );
}
