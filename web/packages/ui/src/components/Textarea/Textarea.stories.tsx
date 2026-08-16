import type { Meta, StoryObj } from '@storybook/react-vite';
import { Textarea } from './Textarea';

const meta: Meta<typeof Textarea> = {
  title: 'Components/Textarea',
  component: Textarea,
  parameters: { layout: 'padded' },
  args: { placeholder: '채널 소개를 입력하세요' },
  argTypes: { invalid: { control: 'boolean' }, disabled: { control: 'boolean' } },
};
export default meta;
type Story = StoryObj<typeof Textarea>;

export const Playground: Story = {
  render: (args) => (
    <div style={{ maxWidth: 360 }}>
      <Textarea {...args} rows={4} />
    </div>
  ),
};
