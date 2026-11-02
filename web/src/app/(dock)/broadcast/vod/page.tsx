import { VodListScreen } from '@/features/broadcast/vod/VodListScreen';

export const metadata = { title: '지난 방송 · PokeClip' };

// 방송 › 지난 방송 목록 (디자인 1f). 슬라이드 래퍼와 사이드바는 broadcast/layout이 갖고,
// 본문 폭은 VodListScreen이 자체 <main>에서 잡는다 (라이브 대시보드와 같은 구조).
export default function VodListPage() {
  return <VodListScreen />;
}
