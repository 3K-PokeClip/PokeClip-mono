import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Combobox } from './Combobox';

const GAMES = [
  { value: 'gta', label: 'GTA' },
  { value: 'minecraft', label: '마인크래프트' },
  { value: 'lol', label: '리그 오브 레전드' },
  { value: 'valorant', label: '발로란트' },
  { value: 'overwatch', label: '오버워치' },
  { value: 'apex', label: '에이펙스 레전드' },
];

const meta: Meta<typeof Combobox> = {
  title: 'Components/Combobox',
  component: Combobox,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof Combobox>;

export const Default: Story = {
  render: function Default() {
    const [v, setV] = useState<string | undefined>(undefined);
    return (
      <div style={{ width: 280 }}>
        <Combobox
          options={GAMES}
          value={v}
          onValueChange={setV}
          placeholder="게임 검색…"
          aria-label="게임 검색"
        />
      </div>
    );
  },
};
