import { redirect } from 'next/navigation';

// 편집기 진입 — 기본은 스튜디오형이다. 독 없는 (fullscreen) 그룹이라
// 나가는 길은 편집기 헤더의 「보관함으로」가 낸다.
// 시안은 스튜디오형·플로우형 둘 다 채택했고, 어느 쪽으로 열지는 나중에 설정
// (간편/정밀 모드)이 정한다. 그때 바꿀 곳이 여기 한 줄이 되도록 진입 경로를
// 형제 라우트 위에 따로 둔다 — 진입 링크는 계속 /clips/editor를 가리킨다.
//
// 🔴 주소 뒤의 값(?stream=&card= · ?recipe=)을 그대로 들고 넘어간다 — 떼어 버리면 카드의 「편집」이
// 빈 편집기로 떨어진다(2026-09-17 실방송 시험에서 발견).
export default async function ClipEditorPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const qs = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (typeof value === 'string') qs.set(key, value);
    else if (Array.isArray(value) && value[0] !== undefined) qs.set(key, value[0]);
  }
  const suffix = qs.toString();
  redirect(suffix ? `/clips/editor/studio?${suffix}` : '/clips/editor/studio');
}
