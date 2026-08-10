'use client';

import { useQuery } from '@tanstack/react-query';
import { Badge, Button, Card, Container, Stack, useTheme, type Theme } from '@/ui';
import { useCounterStore } from '@/stores/counter';

interface PingResponse {
  status: string;
  serverTime: string;
}

async function fetchPing(): Promise<PingResponse> {
  const res = await fetch('/api/ping');
  if (!res.ok) throw new Error(`API 요청 실패: ${res.status}`);
  return res.json();
}

const THEMES: Theme[] = ['light', 'dark', 'system'];

// 개발용 데모 — 테마·Zustand·TanStack Query 사용 패턴 앵커 (제품 화면 아님)
export default function DevPage() {
  const { theme, resolvedTheme, setTheme } = useTheme();
  const count = useCounterStore((s) => s.count);
  const increment = useCounterStore((s) => s.increment);
  const decrement = useCounterStore((s) => s.decrement);
  const reset = useCounterStore((s) => s.reset);
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['ping'],
    queryFn: fetchPing,
  });

  return (
    <Container size="md" style={{ paddingBlock: 'var(--pc-space-8)' }}>
      <Stack gap={6}>
        <Stack gap={2}>
          <h1>PokeClip</h1>
          <p>디자인 시스템 + Next.js + TanStack Query + Zustand 통합 확인 페이지</p>
        </Stack>

        <Card padding={5}>
          <Stack gap={3}>
            <Stack direction="row" gap={2} align="center">
              <h2>테마</h2>
              <Badge tone="accent">{theme}</Badge>
              <Badge tone="neutral" variant="outline">
                resolved: {resolvedTheme}
              </Badge>
            </Stack>
            <Stack direction="row" gap={2}>
              {THEMES.map((t) => (
                <Button
                  key={t}
                  variant={theme === t ? 'solid' : 'outline'}
                  size="sm"
                  onClick={() => setTheme(t)}
                >
                  {t}
                </Button>
              ))}
            </Stack>
          </Stack>
        </Card>

        <Card padding={5}>
          <Stack gap={3}>
            <Stack direction="row" gap={2} align="center">
              <h2>Zustand 카운터</h2>
              <Badge tone="point">{count}</Badge>
            </Stack>
            <Stack direction="row" gap={2}>
              <Button size="sm" onClick={increment}>
                +1
              </Button>
              <Button size="sm" variant="soft" onClick={decrement}>
                -1
              </Button>
              <Button size="sm" variant="ghost" onClick={reset}>
                리셋
              </Button>
            </Stack>
          </Stack>
        </Card>

        <Card padding={5}>
          <Stack gap={3}>
            <h2>TanStack Query — /api/ping</h2>
            {isPending && <p>불러오는 중…</p>}
            {isError && <p>에러: {error.message}</p>}
            {data && (
              <Stack direction="row" gap={2} wrap>
                <Badge tone="success" variant="soft">
                  status: {data.status}
                </Badge>
                <Badge tone="info" variant="soft">
                  serverTime: {data.serverTime}
                </Badge>
              </Stack>
            )}
          </Stack>
        </Card>
      </Stack>
    </Container>
  );
}
