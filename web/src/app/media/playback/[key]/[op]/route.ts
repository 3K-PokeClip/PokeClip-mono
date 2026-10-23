// 로컬 녹화 재생 전달(POK-317). 웹은 녹화를 출입증 쿠키 경로 아래({base}/playback/<키>/list · /get)로 부르고, prod는 엣지가
// 그 주소를 녹화 재생 서버(MediaMTX playback)의 /list · /get?path=<키>로 고쳐 쓴다. 로컬에는 엣지가 없어 이 라우트가 같은 일을
// 한다: 로컬 NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL을 이 웹(…/media)으로 두고 MEDIA_PLAYBACK_ORIGIN에 재생 서버를 준다.
// MEDIA_PLAYBACK_ORIGIN이 없으면(prod · dev) 404라 이 길은 꺼져 있다.
// 🔴 path는 늘 경로의 키로 채우고 들어온 path는 버린다: 쿠키 정책은 쿼리를 못 막아, 믿으면 내 키 쿠키로 남의 녹화가 열린다.

const KEY_RE = /^[A-Za-z0-9_-]+$/;
/** /get에서 재생 서버로 옮기는 쿼리. 이 밖은 버린다 */
const GET_QUERY = ['start', 'duration', 'format'] as const;

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
    const res = await fetch(`${origin}/${op}?${query}`, { signal: request.signal });
    const headers = new Headers();
    const type = res.headers.get('content-type');
    if (type !== null) headers.set('content-type', type);
    return new Response(res.body, { status: res.status, headers });
  } catch {
    return new Response(null, { status: 502 });
  }
}
