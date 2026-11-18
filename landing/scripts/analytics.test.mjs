// landing/analytics.js 정제(before_send) 회귀 테스트. 의존성 없이 node만으로 돈다.
//
//   node --test 'landing/scripts/*.test.mjs'
//
// analytics.js는 브라우저에서 바로 도는 즉시 실행 함수라 내보내는 것이 없다. 가짜 location·document·window로 vm에서 돌리고,
// SDK 스크립트의 onload를 손으로 불러 posthog.init에 넘어간 설정의 before_send를 꺼내 검사한다.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const SOURCE = readFileSync(new URL('../analytics.js', import.meta.url), 'utf8');

function loadBeforeSend(hostname = 'pokeclip.com') {
  return loadConfig(hostname)?.before_send ?? null;
}

function loadConfig(hostname = 'pokeclip.com') {
  return loadPage({ hostname })?.config ?? null;
}

// 주소(search·hash)까지 정해 페이지를 연다. SDK가 받은 capture 호출과 주소창을 고친 값도 돌려준다
function loadPage({ hostname = 'pokeclip.com', pathname = '/', search = '', hash = '', source = SOURCE } = {}) {
  const scripts = [];
  const captures = [];
  const replaced = [];
  let config = null;
  const window = {
    posthog: {
      init: (_key, options) => {
        config = options;
      },
      // 속성 객체는 vm 안에서 만들어져 원형이 다르다 — 비교할 수 있게 이쪽 객체로 옮겨 담는다
      capture: (name, properties) => captures.push([name, { ...properties }]),
    },
  };
  const context = {
    window,
    location: { hostname, pathname, search, hash },
    history: { state: null, replaceState: (_state, _title, url) => replaced.push(url) },
    URL,
    URLSearchParams,
    document: {
      createElement: () => ({}),
      head: { appendChild: (script) => scripts.push(script) },
      addEventListener: () => {},
    },
  };
  vm.runInNewContext(source, context);
  if (scripts.length === 0) return null;
  scripts[0].onload();
  return { config, captures, replaced };
}

test('운영 주소가 아니면 SDK를 내려받지 않는다', () => {
  assert.equal(loadBeforeSend('127.0.0.1'), null);
});

test('우리 주소는 쿼리·해시만 떼고, 바깥 유입 주소는 origin만 남긴다', () => {
  const beforeSend = loadBeforeSend();
  const out = beforeSend({
    event: '$pageview',
    properties: {
      $current_url: 'https://pokeclip.com/?utm_source=chzzk&email=a@b.c#top',
      $pathname: '/',
      $referrer: 'https://chzzk.naver.com/abcdef0123456789?x=1',
    },
    $set_once: { $initial_referrer: 'https://youtu.be/dQw4w9WgXcQ' },
  });
  assert.equal(out.properties.$current_url, 'https://pokeclip.com/');
  assert.equal(out.properties.$referrer, 'https://chzzk.naver.com');
  assert.equal(out.$set_once.$initial_referrer, 'https://youtu.be');
  assert.doesNotMatch(JSON.stringify(out), /abcdef0123456789|dQw4w9WgXcQ|a@b\.c/);
});

test('홍보 링크 값은 README 표의 값만 두고 나머지는 other — utm_term·광고 클릭 식별자는 지운다', () => {
  const beforeSend = loadBeforeSend();
  const out = beforeSend({
    event: '$pageview',
    properties: {
      utm_source: 'discord',
      utm_medium: 'post',
      utm_campaign: 'launch_2026_10',
      utm_content: 'banner',
      utm_term: '게임하는너구리',
      fbclid: 'IwAR-secret',
    },
    $set_once: {
      $initial_utm_source: 'a@b.c',
      $initial_utm_medium: 'newsletter',
      $initial_utm_content: 'abcdef0123456789abcdef0123456789',
      $initial_gclid: 'Cj0-secret',
    },
  });
  assert.deepEqual(out.properties, {
    utm_source: 'discord',
    utm_medium: 'post',
    utm_campaign: 'launch_2026_10',
    utm_content: 'banner',
  });
  assert.deepEqual(out.$set_once, {
    $initial_utm_source: 'other',
    $initial_utm_medium: 'other',
    $initial_utm_content: 'other', // 모양만 맞는 값(채널 ID 꼴)도 목록에 없으면 other
  });
});

test('메일 홍보 링크 값(README 표)은 그대로 남는다 — 시안 구분은 draft_a·b·c만', () => {
  const beforeSend = loadBeforeSend();
  const tags = {
    utm_source: 'email',
    utm_medium: 'dm',
    utm_campaign: '2026_11_streamer_outreach',
    utm_content: 'draft_b',
  };
  const out = beforeSend({
    event: '$pageview',
    properties: { ...tags, $session_entry_utm_source: 'email', $session_entry_utm_content: 'draft_d' },
    $set_once: { $initial_utm_source: 'email', $initial_utm_campaign: '2026_11_streamer_outreach' },
  });
  assert.deepEqual(out.properties, { ...tags, $session_entry_utm_source: 'email', $session_entry_utm_content: 'other' });
  assert.deepEqual(out.$set_once, { $initial_utm_source: 'email', $initial_utm_campaign: '2026_11_streamer_outreach' });
});

const MAIL_TAGS = 'utm_source=email&utm_medium=dm&utm_campaign=2026_11_streamer_outreach&utm_content=draft_a';

test('안내 메일 링크의 수신 코드(?r=)는 꼴이 맞을 때만 outreach_link_opened로 한 번 보낸다', () => {
  const page = loadPage({ search: `?${MAIL_TAGS}&r=k3f7x2ab`, hash: '#pricing' });
  assert.deepEqual(page.captures, [['outreach_link_opened', { outreach_code: 'k3f7x2ab' }]]);
  // 주소창에서는 r만 지운다 — 홍보 링크 꼬리표와 해시는 그대로다
  assert.deepEqual(page.replaced, [`/?${MAIL_TAGS}#pricing`]);

  // 그 이벤트가 정제를 거쳐도 코드는 남고, 주소 속성에는 실리지 않는다
  const out = page.config.before_send({
    event: 'outreach_link_opened',
    properties: { outreach_code: 'k3f7x2ab', $current_url: `https://pokeclip.com/?${MAIL_TAGS}&r=k3f7x2ab` },
  });
  assert.deepEqual(out.properties, { outreach_code: 'k3f7x2ab', $current_url: 'https://pokeclip.com/' });
});

test('꼴이 다른 값(이름·이메일·대문자·길이·앞뒤 덧붙임)은 보내지 않고 주소창에서만 지운다 — 코드가 없으면 아무것도 하지 않는다', () => {
  // 메일 꼬리표를 다 갖춘 링크에서 r 값만 바꾼다 — 꼴 검사만 시험한다. 앞에 글자가 붙은 값은 정규식의 ^를, 뒤에 붙은 값은 $를 지킨다
  const values = ['%EA%B9%9C%EC%93%B0', 'someone%40example.com', 'K3F7X2AB', 'k3f7x2a', 'k3f7x2ab1', 'k3f9x2ab', '', 'xk3f7x2ab', 'a%40b.k3f7x2ab'];
  for (const value of values) {
    const page = loadPage({ search: `?${MAIL_TAGS}&r=${value}` });
    assert.deepEqual(page.captures, [], value);
    assert.deepEqual(page.replaced, [`/?${MAIL_TAGS}`], value);
  }
  // 꼴이 맞아도 메일 링크 꼬리표(utm_source=email + 허용 목록의 utm_campaign)가 없으면 보내지 않는다
  // — 8글자 아이디(gamerkim)를 r에 넣은 링크가 섞이지 않게
  const notMail = [
    '?r=gamerkim',
    '?utm_source=email&r=k3f7x2ab',
    '?utm_source=chzzk&utm_campaign=2026_11_streamer_outreach&r=k3f7x2ab',
    '?utm_source=email&utm_campaign=2026_12_streamer_outreach&r=k3f7x2ab',
  ];
  for (const search of notMail) {
    const page = loadPage({ search });
    assert.deepEqual(page.captures, [], search);
    assert.equal(page.replaced.length, 1, search);
    assert.doesNotMatch(page.replaced[0], /[?&]r=/, search);
  }
  const plain = loadPage({ search: '?utm_source=chzzk&utm_medium=profile' });
  assert.deepEqual(plain.captures, []);
  assert.deepEqual(plain.replaced, []);
  assert.deepEqual(loadPage({ search: '?r=k3f7x2ab' }).replaced, ['/']);
});

test('세션 첫 진입($session_entry_*)·Meta 식별자($fbc·$fbp)도 같은 규칙이다 — SDK가 모든 이벤트에 싣는다 (PR #382 3차 리뷰)', () => {
  const beforeSend = loadBeforeSend();
  const out = beforeSend({
    event: 'landing_cta_clicked',
    properties: {
      $session_entry_utm_source: 'person@example.com',
      $session_entry_utm_medium: 'profile',
      $session_entry_utm_term: '스트리머이름',
      $session_entry_gclid: 'Cj0-secret',
      bfcid: 'bf-secret',
    },
    $set: { $fbc: 'fb.1.1700000000000.IwAR-secret', $fbp: 'fb.1.1700000000000.123' },
  });
  assert.deepEqual(out.properties, {
    $session_entry_utm_source: 'other',
    $session_entry_utm_medium: 'profile',
  });
  assert.deepEqual(out.$set, {});
  assert.doesNotMatch(JSON.stringify(out), /secret|person@|스트리머/);
});

test('대시보드 「이용 분석 참여」를 끈 선택(.pokeclip.com 쿠키)을 따른다 — 추적 금지도 존중한다', () => {
  const config = loadConfig();
  assert.equal(config.opt_out_capturing_persistence_type, 'cookie');
  assert.equal(config.cross_subdomain_cookie, true);
  assert.equal(config.respect_dnt, true);
});

test('null 이벤트는 null로 돌려준다', () => {
  assert.equal(loadBeforeSend()(null), null);
});

test('새 메일 캠페인은 UTM 허용 목록에 더하기만 하면 수신 코드도 읽는다', () => {
  const search = '?utm_source=email&utm_medium=dm&utm_campaign=2026_12_streamer_outreach&utm_content=draft_a&r=k3f7x2ab';
  const source = SOURCE.replace('|2026_11_streamer_outreach)$/', '|2026_11_streamer_outreach|2026_12_streamer_outreach)$/');
  assert.notEqual(source, SOURCE, '허용 목록의 캠페인 줄을 찾지 못함');
  assert.deepEqual(loadPage({ search }).captures, []); // 지금 목록에는 없다
  assert.deepEqual(loadPage({ search, source }).captures, [['outreach_link_opened', { outreach_code: 'k3f7x2ab' }]]);
});
