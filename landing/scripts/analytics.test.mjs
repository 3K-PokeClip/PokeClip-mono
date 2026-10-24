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
  const scripts = [];
  let config = null;
  const window = {
    posthog: {
      init: (_key, options) => {
        config = options;
      },
    },
  };
  const context = {
    window,
    location: { hostname },
    URL,
    document: {
      createElement: () => ({}),
      head: { appendChild: (script) => scripts.push(script) },
      addEventListener: () => {},
    },
  };
  vm.runInNewContext(SOURCE, context);
  if (scripts.length === 0) return null;
  scripts[0].onload();
  return config;
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

test('대시보드 「이용 분석 참여」를 끈 선택(.pokeclip.com 쿠키)을 따른다 — 추적 금지도 존중한다', () => {
  const config = loadConfig();
  assert.equal(config.opt_out_capturing_persistence_type, 'cookie');
  assert.equal(config.cross_subdomain_cookie, true);
  assert.equal(config.respect_dnt, true);
});

test('null 이벤트는 null로 돌려준다', () => {
  assert.equal(loadBeforeSend()(null), null);
});
