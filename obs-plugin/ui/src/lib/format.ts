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

// 붙여넣은 글에서 페어링 코드를 고른다. 앞뒤 공백이나 안내 문구가 섞여 와도(「 KQ4M-7X2P」·「코드: KQ4M-7X2P」) 받는다.
//   1. 하이픈으로 이은 4자-4자 — 웹이 복사해 주는 모양이라 가장 믿을 만하다
//   2. 붙은 8자 · 공백 하나로 나뉜 4자 4자 — 여럿이면 숫자가 든 것을 먼저(낱말 「PokeClip」을 코드로 집지 않게),
//      숫자가 든 것이 없으면 맨 뒤 것(코드는 보통 안내 문구 뒤에 온다)
//   3. 그런 토막이 없으면 입력할 때처럼 앞에서부터 유효한 글자를 모은다
const HYPHENATED_CODE = /(?<![0-9A-Za-z])([0-9A-Za-z]{4})\s*[-–—]\s*([0-9A-Za-z]{4})(?![0-9A-Za-z])/;
const LOOSE_CODE = /(?<![0-9A-Za-z])([0-9A-Za-z]{4})\s?([0-9A-Za-z]{4})(?![0-9A-Za-z])/g;

export function extractPairingCode(pasted: string): string {
  const complete = (raw: string) => {
    const code = formatPairingInput(raw);
    return isCompletePairingCode(code) ? code : '';
  };
  const hyphenated = HYPHENATED_CODE.exec(pasted);
  const fromHyphen = hyphenated ? complete(hyphenated[1] + hyphenated[2]) : '';
  if (fromHyphen) return fromHyphen;

  const tokens = [...pasted.matchAll(LOOSE_CODE)].map((m) => m[1] + m[2]).filter((raw) => complete(raw));
  const token = tokens.find((raw) => /\d/.test(raw)) ?? tokens[tokens.length - 1];
  return token ? complete(token) : formatPairingInput(pasted);
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

// 다음 재시도까지 남은 시간 — 「12초 뒤」. 독은 OBS와 같은 PC에서 돌아 시계가 같다.
export function formatCountdown(ms: number): string {
  const sec = Math.ceil(ms / 1000);
  return sec > 0 ? `${sec}초 뒤` : '곧';
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
