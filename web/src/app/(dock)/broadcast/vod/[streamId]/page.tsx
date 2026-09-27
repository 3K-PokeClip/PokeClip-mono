import { LiveScreen } from '@/features/broadcast/livenow/LiveScreen';

export const metadata = { title: '지난 방송 · PokeClip' };

// 지난 방송 상세 = 라이브 대시보드와 같은 화면이다(위키 결정: 「지난 방송에서 하나를 열면
// 라이브 대시보드와 같은 화면이 뜬다」). 방송 번호는 경로에서 읽는다(streamSelection.vodStreamIdFromPath).
export default function VodViewerPage() {
  return <LiveScreen />;
}
