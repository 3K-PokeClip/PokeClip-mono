import { ScreenContainer } from '@/components/app-shell/ScreenContainer';

export const metadata = { title: 'VOD 뷰어 · PokeClip' };

// 방송 › 지난 방송 › VOD 뷰어 — 빈 화면 (뷰어 본체는 시안 1c 별도 티켓).
// 목록(1f)의 행 클릭이 갈 자리라 함께 만든다. 링크가 404로 끝나면 이동이 아니라 고장이다.
//
// 레포의 첫 동적 라우트다 — Next 16에서 params는 Promise이고, PageProps<'라우트'>가
// 그 모양을 라우트 리터럴에서 만들어 준다(next typegen이 채운다).
export default async function VodViewerPage({ params }: PageProps<'/broadcast/vod/[streamId]'>) {
  const { streamId } = await params;
  return (
    <ScreenContainer>
      <h1>VOD 뷰어</h1>
      <p>{streamId}</p>
    </ScreenContainer>
  );
}
