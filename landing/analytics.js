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

  // 주소의 쿼리·해시를 뗀다 — 홍보 링크의 utm_* 는 SDK가 먼저 따로 뽑아 두므로 남는다.
  // 우리 주소가 아닌 곳(유입 경로)은 origin만 남긴다 — 방송 채널·영상 주소는 경로에 채널 ID·영상 ID가 실린다(처리방침 13절)
  function scrubUrl(value) {
    if (/^https?:\/\//i.test(value)) {
      try {
        var url = new URL(value);
        if (url.hostname !== 'pokeclip.com' && !/\.pokeclip\.com$/.test(url.hostname)) {
          return url.origin;
        }
      } catch (e) {
        // 주소처럼 생겼는데 못 읽으면 아래에서 쿼리만이라도 뗀다
      }
    }
    return value.replace(/[?#].*$/, '');
  }

  // 홍보 링크 값은 README 「홍보 링크와 UTM」 표에 적은 값만 그대로 두고 나머지는 other로 바꾼다.
  // 자유 문자열(utm_term)과 광고 클릭 식별자는 지운다 — 대시보드 web/src/lib/analytics/scrub.ts와 같은 규칙이다
  var UTM_ALLOWED = {
    utm_source: /^(youtube|chzzk|soop|discord|kakao|x|naver|instagram)$/,
    utm_medium: /^(video_description|profile|community|post|dm|ad)$/,
    utm_campaign: /^(launch_2026_10)$/,
    utm_content: /^(pinned_comment|banner)$/,
  };
  var UTM_KEY = /^(?:\$initial_)?(utm_[a-z]+)$/;
  var CLICK_ID_KEY =
    /^(?:\$initial_)?(?:gad_source|mc_cid|gclid|gclsrc|dclid|gbraid|wbraid|fbclid|msclkid|twclid|li_fat_id|igshid|ttclid|rdt_cid|epik|qclid|sccid|oppref|irclid|_kx)$/;

  // 홍보 링크 속성이면 정리하고 true
  function scrubCampaign(properties, key, value) {
    if (CLICK_ID_KEY.test(key)) {
      delete properties[key];
      return true;
    }
    var utm = UTM_KEY.exec(key);
    if (!utm) return false;
    var allowed = UTM_ALLOWED[utm[1]];
    if (!allowed) delete properties[key];
    else if (typeof value === 'string' && value !== '' && !allowed.test(value))
      properties[key] = 'other';
    return true;
  }

  function scrubProperties(properties) {
    if (!properties) return;
    Object.keys(properties).forEach(function (key) {
      var value = properties[key];
      if (scrubCampaign(properties, key, value)) return;
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
      // 대시보드 설정 › 계정의 「이용 분석 참여」를 끈 회원의 선택(.pokeclip.com 쿠키)을 같은 브라우저의 소개 페이지도 따른다
      opt_out_capturing_persistence_type: 'cookie',
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

  // 시작 버튼(data-cta="위치") 클릭 — 곧 대시보드로 넘어가므로 기다리지 않고 비콘으로 바로 보낸다.
  // SDK가 아직 안 붙었으면 버린다(이동을 막지 않는다)
  document.addEventListener('click', function (event) {
    var link = event.target && event.target.closest ? event.target.closest('a[data-cta]') : null;
    if (!link || !window.posthog || !window.posthog.__loaded) return;
    window.posthog.capture(
      'landing_cta_clicked',
      { position: link.getAttribute('data-cta') },
      { send_instantly: true, transport: 'sendBeacon' },
    );
  });
})();
