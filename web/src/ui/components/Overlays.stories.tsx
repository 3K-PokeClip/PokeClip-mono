import type { Meta, StoryObj } from '@storybook/react-vite';
import { Tooltip } from './Tooltip';
import { Popover } from './Popover';
import { Dialog } from './Dialog';
import { Drawer } from './Drawer';
import { ToastProvider, useToast } from './Toast';
import { Button } from './Button';
import { VStack, HStack } from './Stack';
import { Text } from './Text';

const meta: Meta = { title: 'Components/Overlays', parameters: { layout: 'centered' } };
export default meta;
type Story = StoryObj;

export const TooltipExample: Story = {
  name: 'Tooltip',
  render: () => (
    <Tooltip content="클립을 저장합니다">
      <Button variant="soft">저장</Button>
    </Tooltip>
  ),
};

export const PopoverExample: Story = {
  name: 'Popover',
  render: () => (
    <Popover>
      <Popover.Trigger>
        <Button variant="outline">필터 ▾</Button>
      </Popover.Trigger>
      <Popover.Content>
        <VStack gap={2} align="start">
          <Text weight="semibold">정렬 기준</Text>
          <Text tone="secondary" size="sm">
            인기순 · 최신순 · 시청자순
          </Text>
          <Button size="sm" variant="soft">
            적용
          </Button>
        </VStack>
      </Popover.Content>
    </Popover>
  ),
};

export const DialogExample: Story = {
  name: 'Dialog',
  render: () => (
    <Dialog>
      <Dialog.Trigger>
        <Button variant="danger">클립 삭제</Button>
      </Dialog.Trigger>
      <Dialog.Content>
        <Dialog.Title>클립을 삭제할까요?</Dialog.Title>
        <Dialog.Description>삭제한 클립은 복구할 수 없습니다.</Dialog.Description>
        <HStack gap={2} justify="flex-end">
          <Dialog.Close>
            <Button variant="ghost">취소</Button>
          </Dialog.Close>
          <Dialog.Close>
            <Button variant="danger">삭제</Button>
          </Dialog.Close>
        </HStack>
      </Dialog.Content>
    </Dialog>
  ),
};

export const DrawerExample: Story = {
  name: 'Drawer',
  render: () => (
    <Drawer side="right">
      <Drawer.Trigger>
        <Button variant="outline">채팅 열기</Button>
      </Drawer.Trigger>
      <Drawer.Content>
        <Drawer.Title>실시간 채팅</Drawer.Title>
        <Drawer.Description>오른쪽에서 슬라이드되는 드로어입니다.</Drawer.Description>
        <Drawer.Close>
          <Button variant="ghost">닫기</Button>
        </Drawer.Close>
      </Drawer.Content>
    </Drawer>
  ),
};

function ToastDemo() {
  const { toast } = useToast();
  return (
    <HStack gap={2}>
      <Button
        variant="soft"
        onClick={() =>
          toast({ title: '저장 완료', description: '클립이 저장되었습니다.', variant: 'success' })
        }
      >
        성공 토스트
      </Button>
      <Button
        variant="soft"
        onClick={() =>
          toast({ title: '오류 발생', description: '다시 시도해 주세요.', variant: 'danger' })
        }
      >
        오류 토스트
      </Button>
    </HStack>
  );
}
export const ToastExample: Story = {
  name: 'Toast',
  render: () => (
    <ToastProvider>
      <ToastDemo />
    </ToastProvider>
  ),
};
