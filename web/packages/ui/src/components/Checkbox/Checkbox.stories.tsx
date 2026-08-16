import type { Meta, StoryObj } from '@storybook/react-vite';
import { Checkbox } from './Checkbox';
import { VStack } from '../Stack';

const meta: Meta<typeof Checkbox> = {
  title: 'Components/Checkbox',
  component: Checkbox,
  parameters: { layout: 'padded' },
  args: { label: '다시 보지 않기' },
  argTypes: {
    indeterminate: { control: 'boolean' },
    disabled: { control: 'boolean' },
    size: { control: 'inline-radio', options: ['sm', 'md'] },
  },
};
export default meta;
type Story = StoryObj<typeof Checkbox>;

export const Playground: Story = {};

export const States: Story = {
  render: () => (
    <VStack gap={3} align="start">
      <Checkbox label="다시 보지 않기" defaultChecked />
      <Checkbox label="부분 선택 상태" indeterminate />
      <Checkbox label="비활성" disabled />
    </VStack>
  ),
};
