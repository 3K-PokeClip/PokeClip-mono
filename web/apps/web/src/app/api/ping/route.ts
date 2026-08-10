import { NextResponse } from 'next/server';

// TanStack Query 연동 확인용 최소 API 라우트.
// 실제 서비스 API가 생기면 이 패턴(app/api/**/route.ts)으로 확장한다.
export function GET() {
  return NextResponse.json({
    status: 'ok',
    serverTime: new Date().toISOString(),
  });
}
