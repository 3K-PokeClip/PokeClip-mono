import type { Meta, StoryObj } from '@storybook/react-vite';
import { Progress } from './Progress';
import { VStack } from '../Stack';

const meta: Meta<typeof Progress> = {
  title: 'Components/Progress',
  component: Progress,
  parameters: { layout: 'padded' },
  args: { value: 72, label: '업로드 72%' },
  argTypes: { size: { control: 'inline-radio', options: ['sm', 'md'] } },
};
export default meta;
type Story = StoryObj<typeof Progress>;

export const Playground: Story = {
  render: (args) => (
    <div style={{ width: 320 }}>
      <Progress {...args} />
    </div>
  ),
};

export const States: Story = {
  render: () => (
    <VStack gap={4} style={{ width: 320 }}>
      <Progress value={30} label="업로드 30%" />
      <Progress value={72} label="업로드 72%" />
      <Progress value={null} label="처리 중" />
    </VStack>
  ),
};
