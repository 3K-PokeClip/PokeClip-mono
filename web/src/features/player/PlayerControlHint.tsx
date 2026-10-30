import type { ReactNode } from 'react';
import styles from './GlassPlayer.module.css';

/**
 * 재생 막대 버튼을 호버하면 위쪽에 뜨는 이름과 단축키(POK-518). 단축키가 없으면 이름만 뜬다.
 * 공용 Tooltip이 아니다: Tooltip은 document.body에 떠서 플레이어 전체 화면에서 안 보이고, 재생 중 컨트롤이 숨어도 혼자 남는다.
 * 컨트롤 안에 두면 둘 다 저절로 맞는다. 낭독은 버튼 이름이 맡는다(말풍선은 aria-hidden)
 *
 * @param align 플레이어가 넘친 곳을 자르므로 줄 맨 앞 · 맨 뒤 버튼은 말풍선을 버튼 끝에 맞춘다
 */
export function PlayerControlHint({
  label,
  keys,
  align = 'center',
  children,
}: {
  label: string;
  keys?: string;
  align?: 'start' | 'center' | 'end';
  children: ReactNode;
}) {
  return (
    <span className={styles.hintAnchor}>
      {children}
      <span className={styles.hint} data-align={align} data-theme="dark" aria-hidden="true">
        {keys ? `${label} (${keys})` : label}
      </span>
    </span>
  );
}
