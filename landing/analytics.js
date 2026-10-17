// 사용 통계(PostHog) — 소개 페이지와 약관·처리방침 페이지가 부른다.
// 대시보드(web/src/lib/analytics/client.ts)와 같은 프로젝트·같은 설정을 써서, 소개 페이지에서 대시보드로 넘어간 방문을
// 한 사람으로 잇는다(.pokeclip.com 쿠키 공유). 처리방침 2·7·9절이 이 동작을 적는다 — 설정을 바꾸면 처리방침도 함께 고친다.
// 운영 주소에서, 공개 키가 있을 때만 켠다. 로컬 서버·미리보기에서는 SDK를 내려받지도 않는다.
(function () {
  'use strict';

  // PostHog 프로젝트 공개 키(phc_…). 비어 있으면 켜지 않는다 — README 「사용 통계」
  var KEY = 'phc_pvpFknL2AmFADNjtTQ8ftB7dCabfsmVGczkvJZbpLkfw';
  var HOST = 'https://us.i.posthog.com';
  // 대시보드의 posthog-js와 같은 판을 고정하고 무결성 해시를 단다(다른 외부 스크립트와 같은 규칙)
  var SDK = 'https://cdn.jsdelivr.net/npm/posthog-js@1.435.8/dist/array.js';
  var SDK_INTEGRITY = 'sha384-rY2zO8Ngur2p6a4jr9NGUaTh1Cd8pfDFJ825VvwuC7NjS0dhQnOfLhDTOF4KUdEj';

  if (!KEY) return;
  if (location.hostname !== 'pokeclip.com' && location.hostname !== 'www.pokeclip.com') return;

  // 주소의 쿼리·해시를 뗀다 — 홍보 링크의 utm_* 는 SDK가 먼저 따로 뽑아 두므로 남는다
  function scrubUrl(value) {
    return value.replace(/[?#].*$/, '');
  }

  function scrubProperties(properties) {
    if (!properties) return;
    Object.keys(properties).forEach(function (key) {
      var value = properties[key];
      if (typeof value === 'string') {
        if (/pathname$/.test(key) || /^https?:\/\//i.test(value)) properties[key] = scrubUrl(value);
      } else if ((key === '$set' || key === '$set_once') && value && typeof value === 'object') {
        scrubProperties(value);
      }
    });
  }

  function beforeSend(event) {
    if (!event) return null;
    scrubProperties(event.properties);
    scrubProperties(event.$set);
    scrubProperties(event.$set_once);
    return event;
  }

  var script = document.createElement('script');
  script.src = SDK;
  script.integrity = SDK_INTEGRITY;
  script.crossOrigin = 'anonymous';
  script.async = true;
  script.onload = function () {
    if (!window.posthog || typeof window.posthog.init !== 'function') return;
    window.posthog.init(KEY, {
      api_host: HOST,
      defaults: '2026-08-29',
      persistence: 'localStorage+cookie',
      cross_subdomain_cookie: true,
      person_profiles: 'identified_only',
      capture_pageview: true,
      respect_dnt: true,
      autocapture: false,
      rageclick: false,
      capture_dead_clicks: false,
      capture_heatmaps: false,
      capture_exceptions: false,
      capture_performance: false,
      disable_session_recording: true,
      disable_surveys: true,
      disable_product_tours: true,
      disable_conversations: true,
      disable_web_experiments: true,
      // 피처 플래그 요청은 before_send를 거치지 않고 첫 방문 주소를 쿼리째 싣는다 — 쓰지 않으니 끈다
      advanced_disable_flags: true,
      before_send: beforeSend,
    });
  };
  document.head.appendChild(script);
})();
