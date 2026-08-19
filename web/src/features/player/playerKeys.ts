import { SEEK_PAGE_SECONDS, SEEK_STEP_SECONDS } from './playerMath';

// 시킹 키맵 — 시크바(포커스 시)와 플레이어 전역 단축키가 공유한다 (POK-32).
// 두 곳에 키 분기를 적으면 언젠가 한쪽만 바뀌어 "키보드는 되는데 마우스는 안 되는"
// 계약3 4절 2번이 막으려던 종류의 불일치가 되살아난다.

/** 시크 의도 — 어느 훅으로 갈지는 호출부(PlayerSimulation 계약)가 정한다 */
export type SeekIntent =
  /** 상대 이동. 양수가 과거 방향이다 (PlayerSimulation.seekBy와 같은 부호 규약) */
  | { kind: 'by'; seconds: number }
  /** 창 안의 절대 위치 0..1 — 우측이 라이브 엣지 */
  | { kind: 'toFraction'; fraction: number }
  /** 명시적 라이브 복귀 (계약3 4절 3번) */
  | { kind: 'live' };

/**
 * 키 → 시크 의도. 처리하지 않는 키는 null (호출부가 preventDefault를 걸지 않게).
 *
 * WAI-ARIA slider 패턴을 따른다 — Up/Down도 값 조정 키이고, Up = 값 증가 = 라이브 엣지 쪽이다.
 * Home은 "창의 가장 오래된 지점"이라 창 크기와 무관한 toFraction(0)으로 표현한다.
 */
export function seekIntentForKey(key: string): SeekIntent | null {
  switch (key) {
    case 'ArrowLeft':
    case 'ArrowDown':
      return { kind: 'by', seconds: SEEK_STEP_SECONDS };
    case 'ArrowRight':
    case 'ArrowUp':
      return { kind: 'by', seconds: -SEEK_STEP_SECONDS };
    case 'PageDown':
      return { kind: 'by', seconds: SEEK_PAGE_SECONDS };
    case 'PageUp':
      return { kind: 'by', seconds: -SEEK_PAGE_SECONDS };
    case 'Home':
      return { kind: 'toFraction', fraction: 0 };
    case 'End':
      return { kind: 'live' };
    default:
      return null;
  }
}
