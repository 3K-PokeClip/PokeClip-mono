import { NextResponse } from 'next/server';
import { loadPluginRelease } from '@/lib/pluginRelease/loadPluginRelease';

// 설정 → 플러그인 다운로드가 읽는 최신 설치 파일 정보 (POK-379). 로그인 없이 열린다 — 공개 Release의 정리본이다.
// next.config의 프록시는 afterFiles라 이 파일 라우트가 항상 먼저 잡힌다(백엔드로 새지 않는다).
// 빌드 때 GitHub를 부르지 않도록 요청마다 실행한다. 캐시는 로더의 모듈 메모가 맡는다.
export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    const { body, cache } = await loadPluginRelease();
    return NextResponse.json(body, {
      headers: {
        'Cache-Control': 'public, max-age=60, s-maxage=300, stale-while-revalidate=86400',
        'X-Cache': cache,
      },
    });
  } catch {
    // GitHub 원문 오류는 내보내지 않는다 — 화면은 「불러오지 못했어요」만 그린다
    return NextResponse.json(
      { error: 'upstream_unavailable' },
      { status: 503, headers: { 'Cache-Control': 'no-store' } },
    );
  }
}
