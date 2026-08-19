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

/** 키 이벤트에서 실제로 보는 것만 — React 합성 이벤트와 네이티브 KeyboardEvent 둘 다 만족한다 */
export type SeekKeyEvent = {
  key: string;
  repeat?: boolean;
  metaKey?: boolean;
  ctrlKey?: boolean;
  altKey?: boolean;
};

/**
 * 키 이벤트 → 시크 의도. 처리하지 않으면 null (호출부가 preventDefault를 걸지 않게).
 *
 * 키 문자열이 아니라 이벤트를 받는다 — 수정자·자동반복 판정이 키맵 안에 있어야
 * 시크바와 전역 단축키가 함께 고쳐진다. 키만 넘기면 호출부마다 가드를 다시 적어야 한다.
 *
 * WAI-ARIA slider 패턴을 따른다 — Up/Down도 값 조정 키이고, Up = 값 증가 = 라이브 엣지 쪽이다.
 * Home은 "창의 가장 오래된 지점"이라 창 크기와 무관한 toFraction(0)으로 표현한다.
 */
export function seekIntentForKey(event: SeekKeyEvent): SeekIntent | null {
  // 수정자 조합은 OS·브라우저 단축키다 — Cmd+←/Alt+←(뒤로 가기), Ctrl+Home(문서 처음).
  // 가로채면 시킹으로 처리한 뒤 preventDefault까지 걸어 뒤로 가기를 무력화한다.
  // Shift는 막지 않는다 — 플레이어에서 충돌하는 기본 동작이 없다.
  if (event.metaKey || event.ctrlKey || event.altKey) return null;
  // 자동반복은 버린다 — 화살표를 누르고 있으면 초당 수십 번 시크해 LL-HLS가 그때마다
  // 버퍼를 비우고 프래그먼트를 다시 받는다. 드래그를 "놓을 때 한 번만" 커밋하는 것과 같은 이유다.
  if (event.repeat) return null;
  switch (event.key) {
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
