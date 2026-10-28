// 「이전 화면으로」가 브라우저 뒤로 가기를 써도 되는지(POK-491). 브라우저의 실제 기록 항목으로 판단한다 — Navigation API는
// 지금 항목과 같은 오리진으로 이어진 항목만 보여 준다: 구글 로그인처럼 다른 오리진을 지나 온 항목은 그 앞이 안 보이고,
// router.replace로 바뀐 항목은 하나로 친다. 앞서 경로(pathname)가 바뀔 때마다 앞 화면으로 쌓았더니 OAuth 콜백 → returnTo 같은
// replace도 앞 화면으로 세어, 로그인 뒤 편집기의 「이전」이 앱 밖(구글 동의 화면)으로 나갔다(PR #454 1차 리뷰).
//
// 앞 항목이 로그인 흐름(/login · /auth · /oauth)이면 뒤로 가지 않는다. Navigation API가 없는 브라우저는 앞 화면이 없는 것으로
// 본다 — 앱 밖으로 나가는 것보다 정해 둔 화면(보관함)이 낫다.

/** 뒤로 돌아가면 안 되는 앞 항목 — 로그인·연동 콜백은 다시 밟으면 오류다 */
const AUTH_FLOW = /^\/(login|auth|oauth)(\/|$)/;

/** Navigation API에서 쓰는 만큼만(타입 정의가 없는 TS lib에서도 읽히게) */
interface HistoryEntryLike {
  index: number;
  url: string | null;
}

interface NavigationLike {
  currentEntry: HistoryEntryLike | null;
  entries(): HistoryEntryLike[];
}

/** 이 탭에서 바로 앞 항목이 앱 안 화면이다 — 뒤로 가도 앱 안에 남는다 */
export function hasInAppHistory(): boolean {
  if (typeof window === 'undefined') return false;
  const navigation = (window as Window & { navigation?: NavigationLike }).navigation;
  const current = navigation?.currentEntry;
  if (navigation === undefined || current == null || current.index <= 0) return false;
  const previous = navigation.entries()[current.index - 1];
  if (previous?.url == null) return false;
  const url = new URL(previous.url);
  return url.origin === window.location.origin && !AUTH_FLOW.test(url.pathname);
}
