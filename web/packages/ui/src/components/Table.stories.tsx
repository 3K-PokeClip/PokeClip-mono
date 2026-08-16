import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Table, type SortDirection } from './Table';
import { Badge } from './Badge';

const meta: Meta = { title: 'Components/Table', parameters: { layout: 'padded' } };
export default meta;
type Story = StoryObj;

const ROWS = [
  { name: '체리 of HANAVI', game: '하나비', viewers: 219, live: true },
  { name: '킴대츄', game: '마인크래프트', viewers: 36, live: true },
  { name: '나디', game: '저스트 채팅', viewers: 14, live: true },
  { name: '유묘링', game: '오버워치', viewers: 6, live: false },
];

export const Sortable: Story = {
  render: function SortableTable() {
    const [dir, setDir] = useState<SortDirection>('descending');
    const sorted = [...ROWS].sort((a, b) =>
      dir === 'ascending'
        ? a.viewers - b.viewers
        : dir === 'descending'
          ? b.viewers - a.viewers
          : 0,
    );
    return (
      <Table>
        <Table.Head>
          <Table.Row>
            <Table.HeaderCell>채널</Table.HeaderCell>
            <Table.HeaderCell>게임</Table.HeaderCell>
            <Table.HeaderCell
              sortable
              sortDirection={dir}
              onSort={() => setDir((d) => (d === 'descending' ? 'ascending' : 'descending'))}
            >
              시청자
            </Table.HeaderCell>
            <Table.HeaderCell>상태</Table.HeaderCell>
          </Table.Row>
        </Table.Head>
        <Table.Body>
          {sorted.map((r) => (
            <Table.Row key={r.name}>
              <Table.Cell>{r.name}</Table.Cell>
              <Table.Cell>{r.game}</Table.Cell>
              <Table.Cell>{r.viewers.toLocaleString()}명</Table.Cell>
              <Table.Cell>
                {r.live ? (
                  <Badge tone="danger" variant="soft">
                    LIVE
                  </Badge>
                ) : (
                  <Badge tone="neutral">종료</Badge>
                )}
              </Table.Cell>
            </Table.Row>
          ))}
        </Table.Body>
      </Table>
    );
  },
};
