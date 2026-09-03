import { forwardRef, type ComponentPropsWithoutRef, type ReactNode } from 'react';
import clsx from 'clsx';
import styles from './EmptyState.module.css';

// 「아직 없다」 빈 상태 카드 (시안 1f · 1l ④) — 점선 카드 안에 아이콘 원·제목·설명이 세로로 선다.
//
// 지난 방송 목록(VodListScreen)과 편집자 관리(EditorSettingsScreen)가 같은 카드를 각자 CSS로 들고
// 있던 것을 승격했다(ConfirmDialog 선례). 셋째(라이브 오프라인, POK-227)가 같은 문법을 쓰기로 정해져
// 있어 「세 번째가 원하면」 관행보다 한 발 먼저 올렸다. 두 사용처의 차이가 아이콘과 문자열뿐이라 프롭도
// 그것만 받는다 — 카드 안 액션이 필요한 시안이 생기면 그때 슬롯을 연다.
//
// 폭은 정하지 않는다 — 부모가 정한다(지난 방송은 본문 전폭, 편집자 관리는 .screen의 860u, POK-231).
// 「이 기간에 없다」처럼 필터 결과가 비는 자리는 이 카드가 아니다 — 다른 말이라 다른 모양이다
// (VodListScreen .filterEmpty).

export interface EmptyStateProps extends Omit<
  ComponentPropsWithoutRef<'div'>,
  'title' | 'children'
> {
  /** 장식 아이콘(lucide 21px 등). 래퍼가 aria-hidden으로 숨기므로 아이콘 자체엔 a11y 속성을 주지 않는다. */
  icon: ReactNode;
  /** 「아직 ~이 없어요」 한 줄. 헤딩이 아니라 문단이다. */
  title: string;
  /** 무엇을 하면 채워지는지 — 한두 문장. */
  description: string;
}

export const EmptyState = forwardRef<HTMLDivElement, EmptyStateProps>(function EmptyState(
  { icon, title, description, className, ...rest },
  ref,
) {
  return (
    <div ref={ref} className={clsx(styles.root, className)} {...rest}>
      <span className={styles.icon} aria-hidden="true">
        {icon}
      </span>
      <p className={styles.title}>{title}</p>
      <p className={styles.description}>{description}</p>
    </div>
  );
});
