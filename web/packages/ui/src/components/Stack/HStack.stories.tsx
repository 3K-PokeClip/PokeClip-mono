import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ReactNode } from 'react';
import { HStack } from './Stack';

const meta: Meta<typeof HStack> = {
  title: 'Components/HStack',
  component: HStack,
  parameters: { layout: 'padded' },
  args: { gap: 3 },
};
export default meta;
type Story = StoryObj<typeof HStack>;

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

export const Default: Story = {
  render: (args) => (
    <HStack {...args}>
      <Tile>H1</Tile>
      <Tile>H2</Tile>
      <Tile>H3</Tile>
    </HStack>
  ),
};
