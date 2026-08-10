import type { Meta, StoryObj } from '@storybook/react-vite';
import { ToastProvider, useToast } from './Toast';
import { Button } from '../Button';
import { HStack } from '../Stack';

const meta: Meta<typeof ToastProvider> = {
  title: 'Components/ToastProvider',
  component: ToastProvider,
  parameters: { layout: 'centered' },
};
export default meta;
type Story = StoryObj<typeof ToastProvider>;

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

export const Default: Story = {
  render: () => (
    <ToastProvider>
      <ToastDemo />
    </ToastProvider>
  ),
};
