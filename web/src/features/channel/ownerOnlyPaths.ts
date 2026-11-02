// 채널 주인만 여는 설정 화면(POK-390). 이 주소로 가는 버튼은 남의 채널에서 비활성으로 둔다: 도착하면 OwnerOnly가 막으니
// 노출은 없지만, 「막는 메뉴는 숨기지 않고 비활성」(PRD)과 맞추려는 것이다. Side는 항목의 ownerOnly 표시로 따로 가른다.
const OWNER_ONLY_PATHS: readonly string[] = [
  '/settings/channels',
  '/settings/editors',
  '/settings/plugin',
];

export function isOwnerOnlyPath(href: string): boolean {
  return OWNER_ONLY_PATHS.includes(href);
}
