import type { Meta, StoryObj } from '@storybook/react-vite';
import { Avatar, AvatarGroup } from './Avatar';

const meta: Meta<typeof AvatarGroup> = {
  title: 'Components/AvatarGroup',
  component: AvatarGroup,
  parameters: { layout: 'centered' },
  args: { max: 3, size: 'md' },
};
export default meta;
type Story = StoryObj<typeof AvatarGroup>;

export const Default: Story = {
  render: (args) => (
    <AvatarGroup {...args}>
      <Avatar name="가" />
      <Avatar name="나" />
      <Avatar name="다" />
      <Avatar name="라" />
      <Avatar name="마" />
    </AvatarGroup>
  ),
};
