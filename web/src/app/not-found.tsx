import { NotFoundScreen } from '@/features/errors/NotFoundScreen';

// 라우터 마지막 폴백 (POK-204 · 시안 1t). 이 파일 하나가 두 경우를 다 받는다 —
// 어떤 경로에도 안 걸린 주소, 그리고 상세 화면이 notFound()를 던진 경우.
// 루트에 두는 게 중요하다: (dock) 안에 두면 AuthGuard가 먼저 걸려 비로그인
// 사용자에게 404 대신 /login이 뜬다.
// not-found.tsx는 metadata export를 지원하지 않는다 — 탭 제목은 루트 레이아웃의 것을 쓴다.
export default function NotFound() {
  return <NotFoundScreen />;
}
