import type { Meta, StoryObj } from '@storybook/react-vite';
import { Dialog } from './Dialog';
import { Button } from '../Button';
import { HStack } from '../Stack';

const meta: Meta<typeof Dialog> = {
  title: 'Components/Dialog',
  component: Dialog,
  parameters: { layout: 'centered' },
};
export default meta;
type Story = StoryObj<typeof Dialog>;

export const Default: Story = {
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
