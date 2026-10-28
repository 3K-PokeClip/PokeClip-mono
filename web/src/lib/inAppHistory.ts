import { useEffect } from 'react';
import { usePathname } from 'next/navigation';

// 「이전 화면으로」가 브라우저 뒤로 가기를 써도 되는지(POK-491). 이 탭에서 앱 안으로 지나온 화면(pathname)을 쌓아 둔다.
// 모듈 값이라 새로고침·새 탭·주소 직접 열기면 비어 있다 — 그때 뒤로 가면 앱 밖(또는 아무 데도 아닌 곳)으로 나가므로
// 부르는 쪽이 정한 화면으로 간다.
//
// 브라우저 뒤로 가기로 바로 앞 화면에 돌아오면 한 칸 뺀다: 새 탭으로 연 편집기 → 보관함 → (브라우저 뒤로) 편집기에서
// 「이전」이 다시 뒤로 가면 앱 밖으로 나간다. 같은 화면 안 주소 값만 바뀌는 이동(?recipe= 교체 등)은 세지 않는다.

/** 오래 쓴 탭에서 끝없이 자라지 않게 — 「앞 화면이 있나」만 보므로 깊이는 의미가 없다 */
const MAX_TRAIL = 50;

const trail: string[] = [];

export function recordPathname(pathname: string): void {
  if (trail[trail.length - 1] === pathname) return;
  if (trail.length >= 2 && trail[trail.length - 2] === pathname) {
    trail.pop();
    return;
  }
  trail.push(pathname);
  if (trail.length > MAX_TRAIL) trail.shift();
}

/** 이 탭에서 앱 안 앞 화면이 있다 — 뒤로 가도 앱 안에 남는다 */
export function hasInAppHistory(): boolean {
  return trail.length > 1;
}

/** 앱 루트가 한 번 부른다. 화면을 옮길 때마다 지금 경로를 기록한다 */
export function useInAppHistoryTracker(): void {
  const pathname = usePathname();
  useEffect(() => {
    recordPathname(pathname);
  }, [pathname]);
}

export function resetInAppHistoryForTests(): void {
  trail.length = 0;
}
