'use client';

// 탈퇴 확정 → 완료 화면 사이의 1회용 표식 (POK-206).
//
// 세션 정리를 탈퇴 화면에서 못 하는 사정이 있다: `clearTokens()`가 도는 순간 AuthGuard가
// 리렌더되고 그 이펙트가 의도적 종료 표식과 무관하게 `/login`으로 보낸다. 같은 틱에 먼저
// 호출한 `/goodbye` 이동은 나중에 온 그것에 밀려 완료 화면이 뜰 틈이 없다. 그래서 이동을
// 먼저 하고 **가드 밖에 나간 뒤** `/goodbye`가 세션을 접는다.
//
// 그 대가로 「진짜 탈퇴로 온 것」과 「주소창에 /goodbye를 친 것」을 가를 표식이 필요하다 —
// 이것이 없으면 로그인한 사람이 주소를 치는 것만으로 로그아웃된다.

const KEY = 'pc-withdrawn';

/** 탈퇴 확정이 이동 직전에 남긴다. */
export function markWithdrawn() {
  try {
    sessionStorage.setItem(KEY, '1');
  } catch {
    /* 저장 실패 — 완료 화면이 세션을 접지 못하는 것까지만 감수 (아직 로그인 상태) */
  }
}

/** 완료 화면이 마운트될 때 읽으면서 지운다 — 새로고침으로 두 번 접히지 않게. */
export function consumeWithdrawn(): boolean {
  try {
    const marked = sessionStorage.getItem(KEY) !== null;
    sessionStorage.removeItem(KEY);
    return marked;
  } catch {
    return false;
  }
}
