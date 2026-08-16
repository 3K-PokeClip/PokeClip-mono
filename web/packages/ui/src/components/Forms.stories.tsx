import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Field } from './Field';
import { Input } from './Input';
import { Textarea } from './Textarea';
import { Checkbox } from './Checkbox';
import { Switch } from './Switch';
import { Tag } from './Tag';
import { VStack, HStack } from './Stack';

const meta: Meta = { title: 'Components/Forms', parameters: { layout: 'padded' } };
export default meta;
type Story = StoryObj;

export const Fields: Story = {
  render: () => (
    <VStack gap={5} style={{ maxWidth: 360 }}>
      <Field>
        <Field.Label>스트리머 이름</Field.Label>
        <Input placeholder="예: 마준" />
        <Field.Description>공개 프로필에 표시됩니다.</Field.Description>
      </Field>
      <Field required invalid>
        <Field.Label>이메일</Field.Label>
        <Input defaultValue="not-an-email" />
        <Field.Error>올바른 이메일 형식이 아닙니다.</Field.Error>
      </Field>
      <Field>
        <Field.Label>채널 소개</Field.Label>
        <Textarea placeholder="채널 소개를 입력하세요" />
      </Field>
    </VStack>
  ),
};

export const Toggles: Story = {
  render: function Toggles() {
    const [live, setLive] = useState(true);
    const [auto, setAuto] = useState(false);
    return (
      <VStack gap={3} align="start">
        <Checkbox label="다시 보지 않기" defaultChecked />
        <Checkbox label="부분 선택 상태" indeterminate />
        <Checkbox label="비활성" disabled />
        <Switch label="라이브 알림" checked={live} onChange={(e) => setLive(e.target.checked)} />
        <Switch label="자동 재생" checked={auto} onChange={(e) => setAuto(e.target.checked)} />
      </VStack>
    );
  },
};

export const Tags: Story = {
  render: function Tags() {
    const [tags, setTags] = useState(['저스트 채팅', '단독', '뉴비', '버추얼']);
    return (
      <HStack gap={2} wrap>
        {tags.map((t) => (
          <Tag key={t} onRemove={() => setTags(tags.filter((x) => x !== t))}>
            {t}
          </Tag>
        ))}
      </HStack>
    );
  },
};
