import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ReactNode } from 'react';
import { Stack } from './Stack';

const meta: Meta<typeof Stack> = {
  title: 'Components/Stack',
  component: Stack,
  parameters: { layout: 'padded' },
  args: { direction: 'column', gap: 3 },
  argTypes: {
    direction: { control: 'inline-radio', options: ['row', 'column'] },
    gap: { control: { type: 'number' } },
    wrap: { control: 'boolean' },
  },
};
export default meta;
type Story = StoryObj<typeof Stack>;

const Tile = ({ children }: { children?: ReactNode }) => (
  <div
    style={{
      display: 'grid',
      placeItems: 'center',
      minWidth: 64,
      minHeight: 48,
      padding: '8px 12px',
      background: 'var(--pc-color-accent-subtle)',
      color: 'var(--pc-color-accent-text)',
      borderRadius: 'var(--pc-radius-md)',
      fontSize: 13,
    }}
  >
    {children}
  </div>
);

export const Playground: Story = {
  render: (args) => (
    <Stack {...args}>
      <Tile>1</Tile>
      <Tile>2</Tile>
      <Tile>3</Tile>
    </Stack>
  ),
};
