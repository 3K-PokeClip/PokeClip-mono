import type { Meta, StoryObj } from '@storybook/react-vite';
import { Button } from './Button';
import { IconButton } from '../IconButton';
import { HStack } from '../Stack';

const Heart = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
    <path d="M8 14s-5-3.2-5-7a3 3 0 0 1 5-2 3 3 0 0 1 5 2c0 3.8-5 7-5 7z" />
  </svg>
);

const meta: Meta<typeof Button> = {
  title: 'Components/Button',
  component: Button,
  args: { children: '재생하기', variant: 'solid', size: 'md' },
  argTypes: {
    variant: { control: 'select', options: ['solid', 'soft', 'outline', 'ghost', 'danger'] },
    size: { control: 'inline-radio', options: ['sm', 'md', 'lg'] },
    loading: { control: 'boolean' },
    fullWidth: { control: 'boolean' },
    disabled: { control: 'boolean' },
  },
};
export default meta;
type Story = StoryObj<typeof Button>;

export const Playground: Story = {};

export const Variants: Story = {
  render: (args) => (
    <HStack gap={2} wrap>
      {(['solid', 'soft', 'outline', 'ghost', 'danger'] as const).map((v) => (
        <Button key={v} {...args} variant={v}>
          {v}
        </Button>
      ))}
    </HStack>
  ),
};

export const Sizes: Story = {
  render: (args) => (
    <HStack gap={2} align="center">
      {(['sm', 'md', 'lg'] as const).map((s) => (
        <Button key={s} {...args} size={s}>
          {s}
        </Button>
      ))}
    </HStack>
  ),
};

export const WithIcons: Story = {
  render: (args) => (
    <HStack gap={2}>
      <Button {...args} iconStart={<Heart />}>
        좋아요
      </Button>
      <Button {...args} variant="soft" iconEnd={<Heart />}>
        팔로우
      </Button>
    </HStack>
  ),
};

export const Loading: Story = { args: { loading: true } };

export const IconOnly: Story = {
  render: () => (
    <HStack gap={2} align="center">
      <IconButton aria-label="좋아요" variant="soft">
        <Heart />
      </IconButton>
      <IconButton aria-label="좋아요" variant="solid">
        <Heart />
      </IconButton>
      <IconButton aria-label="좋아요" variant="ghost" size="lg">
        <Heart />
      </IconButton>
    </HStack>
  ),
};
