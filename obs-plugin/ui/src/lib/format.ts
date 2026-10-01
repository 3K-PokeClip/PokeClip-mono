const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

// 입력 중인 페어링 코드를 서버 규칙(CrockfordBase32.normalize)대로 다듬고 XXXX-XXXX 로 보여준다.
export function formatPairingInput(raw: string): string {
  const chars: string[] = [];
  for (const ch of raw.toUpperCase()) {
    const mapped = ch === 'I' || ch === 'L' ? '1' : ch === 'O' ? '0' : ch;
    if (CROCKFORD.includes(mapped)) chars.push(mapped);
    if (chars.length === 8) break;
  }
  const s = chars.join('');
  return s.length > 4 ? `${s.slice(0, 4)}-${s.slice(4)}` : s;
}

export function isCompletePairingCode(formatted: string): boolean {
  return formatted.replace('-', '').length === 8;
}

export function formatUptime(totalSec: number): string {
  const sec = Math.max(0, Math.floor(totalSec));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

export function formatKbps(kbps: number): string {
  if (!Number.isFinite(kbps) || kbps <= 0) return '0';
  return Math.round(kbps).toLocaleString('ko-KR');
}

// 스파크라인 SVG path — 값이 없으면 바닥선.
export function sparklinePath(values: number[], width: number, height: number): string {
  if (values.length === 0) return `M0 ${height} L${width} ${height}`;
  const max = Math.max(...values, 1);
  const step = values.length > 1 ? width / (values.length - 1) : width;
  return values
    .map((v, i) => {
      const x = (i * step).toFixed(1);
      const y = (height - (Math.max(0, v) / max) * (height - 2) - 1).toFixed(1);
      return `${i === 0 ? 'M' : 'L'}${x} ${y}`;
    })
    .join(' ');
}

// SSE 프레임 파서 — fetch 스트리밍용 (EventSource는 Authorization 헤더를 못 붙인다).
export interface SseFrame {
  event: string;
  data: string;
}

export function takeSseFrames(buffer: string): { frames: SseFrame[]; rest: string } {
  const frames: SseFrame[] = [];
  const normalized = buffer.replace(/\r\n/g, '\n');
  const parts = normalized.split('\n\n');
  const rest = parts.pop() ?? '';
  for (const part of parts) {
    let event = 'message';
    const data: string[] = [];
    for (const line of part.split('\n')) {
      if (line.startsWith(':')) continue;
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (data.length > 0) frames.push({ event, data: data.join('\n') });
  }
  return { frames, rest };
}
