import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Slider } from './Slider';
import { Select } from './Select';
import { Combobox } from './Combobox';
import { VStack } from './Stack';
import { Text } from './Text';

const meta: Meta = { title: 'Components/Advanced Forms', parameters: { layout: 'padded' } };
export default meta;
type Story = StoryObj;

const SORT = [
  { value: 'popular', label: '인기순' },
  { value: 'recent', label: '최신순' },
  { value: 'viewers', label: '시청자순' },
  { value: 'soon', label: '준비 중', disabled: true },
];

const GAMES = [
  { value: 'gta', label: 'GTA' },
  { value: 'minecraft', label: '마인크래프트' },
  { value: 'lol', label: '리그 오브 레전드' },
  { value: 'valorant', label: '발로란트' },
  { value: 'overwatch', label: '오버워치' },
  { value: 'apex', label: '에이펙스 레전드' },
];

export const SliderExample: Story = {
  name: 'Slider',
  render: function SliderStory() {
    const [v, setV] = useState(40);
    return (
      <VStack gap={3} style={{ width: 320 }}>
        <Text tone="secondary" size="sm">
          볼륨: {v}
        </Text>
        <Slider value={v} onValueChange={setV} label="볼륨" />
      </VStack>
    );
  },
};

export const SelectExample: Story = {
  name: 'Select',
  render: function SelectStory() {
    const [v, setV] = useState<string | undefined>(undefined);
    return (
      <div style={{ width: 240 }}>
        <Select
          options={SORT}
          value={v}
          onValueChange={setV}
          placeholder="정렬 기준"
          aria-label="정렬 기준"
        />
      </div>
    );
  },
};

export const ComboboxExample: Story = {
  name: 'Combobox',
  render: function ComboboxStory() {
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
