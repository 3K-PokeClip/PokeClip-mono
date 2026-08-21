import type { Meta, StoryObj } from '@storybook/react-vite';
import { ToastProvider, useToast } from './Toast';
import { Button } from '../Button';
import { HStack, Stack } from '../Stack';
import { Text } from '../Text';

const meta: Meta<typeof ToastProvider> = {
  title: 'Components/ToastProvider',
  component: ToastProvider,
  parameters: { layout: 'centered' },
};
export default meta;
type Story = StoryObj<typeof ToastProvider>;

/** 톤 5종 — 자동 닫힘이 톤마다 다르다. 오류·진행은 사용자가 닫을 때까지 남는다. */
function TonesDemo() {
  const { toast, update, dismissAll } = useToast();
  return (
    <Stack gap={3}>
      <HStack gap={2}>
        <Button
          variant="outline"
          size="sm"
          onClick={() =>
            toast({
              tone: 'success',
              title: '클립이 발행되었습니다',
              description: '승급전 마지막 한타 역전 · 너구리 GAMES',
              action: { label: '보기', onClick: () => {} },
            })
          }
        >
          성공 · 5초
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() =>
            toast({
              tone: 'info',
              title: '하이라이트 3개를 감지했어요',
              description: '오늘 방송 · 검토 대기함에 추가되었습니다',
              action: { label: '검토', onClick: () => {} },
            })
          }
        >
          정보 · 5초
        </Button>
        {/* 시안의 「VOD 만료 3일 + 카드 저장」은 쓰지 않는다 — 만료일이 지나면 삭제가
            원칙이라(ADR-004) 「카드 저장」이 구제 동작이 된다. 정책과 충돌하지 않는
            경고로 대체한다. */}
        <Button
          variant="outline"
          size="sm"
          onClick={() =>
            toast({
              tone: 'warning',
              title: '채널 연동이 곧 만료돼요',
              description: '재인증하지 않으면 예약된 업로드가 중단됩니다',
              action: { label: '재인증', onClick: () => {} },
            })
          }
        >
          경고 · 7초
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() =>
            toast({
              tone: 'error',
              title: '업로드에 실패했습니다',
              description: '유튜브 연동이 만료되었어요 · 재인증이 필요합니다',
              action: { label: '재인증', onClick: () => {} },
            })
          }
        >
          오류 · 안 닫힘
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            const id = toast({
              tone: 'progress',
              // 퍼센트는 제목이 아니라 진행 바가 들고 있다 — 제목을 갱신하면
              // 라이브 리전이 갱신마다 다시 읽혀 다른 안내를 덮는다.
              title: '클립 업로드 중',
              description: '고민상담 레전드 사연 · 약 40초 남음',
              progress: 0,
              action: { label: '취소', onClick: () => {} },
            });
            let pct = 0;
            const tick = setInterval(() => {
              pct += 10;
              if (pct >= 100) {
                clearInterval(tick);
                // 진행 → 결과. 톤이 바뀌면 그 톤의 자동 닫힘으로 타이머를 다시 걸고,
                // 끝난 일에 「취소」가 남지 않도록 액션도 함께 뗀다.
                update(id, {
                  tone: 'success',
                  title: '업로드를 마쳤습니다',
                  description: '보관함에서 볼 수 있어요',
                  progress: null,
                  action: null,
                });
                return;
              }
              update(id, { progress: pct });
            }, 400);
          }}
        >
          진행 · 안 닫힘
        </Button>
      </HStack>
      <HStack gap={2}>
        <Button
          variant="soft"
          size="sm"
          onClick={() => {
            const seq = ['info', 'success', 'warning', 'progress', 'error'] as const;
            seq.forEach((tone, i) =>
              setTimeout(
                () => toast({ tone, title: `${i + 1}번째 알림`, description: `톤: ${tone}` }),
                i * 420,
              ),
            );
          }}
        >
          5개 연속
        </Button>
        <Button variant="ghost" size="sm" onClick={dismissAll}>
          모두 닫기
        </Button>
      </HStack>
      <Text tone="muted" size="sm">
        스택에 포인터를 올리거나 안쪽 버튼에 포커스를 주면 타이머가 멈춥니다. Esc는 최신 토스트를
        닫습니다.
      </Text>
    </Stack>
  );
}

export const Default: Story = {
  render: () => (
    <ToastProvider>
      <TonesDemo />
    </ToastProvider>
  ),
};

/** 시안 `1l` 재현 — 되돌릴 수 있는 일에만 「되돌리기」가 붙는다. */
function UndoBoundaryDemo() {
  const { toast } = useToast();
  return (
    <Stack gap={3}>
      <HStack gap={2}>
        <Button
          variant="outline"
          size="sm"
          onClick={() =>
            toast({
              tone: 'success',
              title: '기본 편집자로 변경했어요',
              description: '박편집 · 업로드 전 내 승인이 필요해요',
              undo: () => {},
            })
          }
        >
          권한 변경 (되돌릴 수 있다)
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() =>
            // 회수는 대기 중이던 승인 요청을 무효로 만든다 — 모달로 확인받고
            // 토스트는 결과만 알린다. `destructive`가 undo를 타입으로 막는다.
            toast({
              tone: 'success',
              destructive: true,
              title: '박편집의 편집 권한을 회수했어요',
              description: '편집자 1 / 3 · 대기 중이던 승인 요청 1건 무효',
            })
          }
        >
          권한 회수 (되돌릴 수 없다)
        </Button>
      </HStack>
      <Text tone="muted" size="sm">
        토스트는 되돌릴 수 없는 작업의 확인 수단이 아닙니다. 삭제·연동 해제처럼 파괴적인 동작은
        모달로 확인받고, 토스트는 결과만 알립니다.
      </Text>
    </Stack>
  );
}

export const UndoBoundary: Story = {
  name: '되돌리기 경계',
  render: () => (
    <ToastProvider>
      <UndoBoundaryDemo />
    </ToastProvider>
  ),
};
