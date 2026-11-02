// 로컬 녹화 재생 전달(POK-317). 웹은 녹화를 출입증 쿠키 경로 아래({base}/playback/<키>/list · /get)로 부르고 path 쿼리를 경로의
// 키와 같은 값으로 싣는다(POK-200). prod는 CloudFront · ALB가 쿼리를 그대로 넘기고(ALB는 /playback 접두사만 뗀다), Media 상자
// nginx가 path 하나 · 경로 키와 같음일 때만 재생 서버(MediaMTX playback)로 보낸다. 로컬에는 그 앞단이 없어 이 라우트가 재생 서버의
// /list · /get?path=<키>로 옮긴다: 로컬 NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL을 이 웹(…/media)으로 두고
// MEDIA_PLAYBACK_ORIGIN에 재생 서버를 준다. MEDIA_PLAYBACK_ORIGIN이 없으면(prod · dev) 404라 이 길은 꺼져 있다.
// 🔴 path는 늘 경로의 키로 다시 채우고 들어온 path는 버린다(nginx 대조와 겹치는 이중 방어): 쿠키 정책은 쿼리를 못 막아, 믿으면 내 키
// 쿠키로 남의 녹화가 열린다.

const KEY_RE = /^[A-Za-z0-9_-]+$/;
/** /get에서 재생 서버로 옮기는 쿼리. 이 밖은 버린다 */
const GET_QUERY = ['start', 'duration', 'format'] as const;
/** 재생 서버에 직접 붙던 때와 같게 옮기는 머리. 쿠키는 넘기지 않는다(재생 서버는 안 보고, 출입증이 샐 자리만 는다) */
const REQUEST_HEADERS = ['range'] as const;
const RESPONSE_HEADERS = [
  'content-type',
  'content-length',
  'content-range',
  'accept-ranges',
] as const;

export async function GET(
  request: Request,
  { params }: { params: Promise<{ key: string; op: string }> },
): Promise<Response> {
  const origin = (process.env.MEDIA_PLAYBACK_ORIGIN ?? '').replace(/\/+$/, '');
  const { key, op } = await params;
  if (origin === '' || !KEY_RE.test(key) || (op !== 'list' && op !== 'get'))
    return new Response(null, { status: 404 });

  const incoming = new URL(request.url).searchParams;
  const query = new URLSearchParams();
  if (op === 'get')
    for (const name of GET_QUERY) {
      const value = incoming.get(name);
      if (value !== null) query.set(name, value);
    }
  query.set('path', key);

  try {
    const sent = new Headers();
    for (const name of REQUEST_HEADERS) {
      const value = request.headers.get(name);
      if (value !== null) sent.set(name, value);
    }
    const res = await fetch(`${origin}/${op}?${query}`, { signal: request.signal, headers: sent });
    const headers = new Headers();
    for (const name of RESPONSE_HEADERS) {
      const value = res.headers.get(name);
      if (value !== null) headers.set(name, value);
    }
    return new Response(res.body, { status: res.status, headers });
  } catch {
    return new Response(null, { status: 502 });
  }
}
