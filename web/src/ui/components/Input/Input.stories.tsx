import type { Meta, StoryObj } from '@storybook/react-vite';
import { Input } from './Input';
import { VStack } from '../Stack';

const meta: Meta<typeof Input> = {
  title: 'Components/Input',
  component: Input,
  parameters: { layout: 'padded' },
  args: { placeholder: '예: 마준', size: 'md' },
  argTypes: {
    size: { control: 'inline-radio', options: ['sm', 'md', 'lg'] },
    invalid: { control: 'boolean' },
    disabled: { control: 'boolean' },
  },
};
export default meta;
type Story = StoryObj<typeof Input>;

export const Playground: Story = {
  render: (args) => (
    <div style={{ maxWidth: 320 }}>
      <Input {...args} />
    </div>
  ),
};

export const States: Story = {
  render: () => (
    <VStack gap={3} style={{ maxWidth: 320 }}>
      <Input placeholder="기본" />
      <Input defaultValue="not-an-email" invalid />
      <Input placeholder="비활성" disabled />
    </VStack>
  ),
};
