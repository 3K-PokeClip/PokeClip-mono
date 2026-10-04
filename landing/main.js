// PokeClip 랜딩 시안 (POK-269) — P1: 크롬·단계 전환·그래프·파형 등 GSAP 없이 도는 기본 동작.
// P2에서 GSAP + ScrollTrigger + Lenis 타임라인이 이 위에 얹힌다 (window.PC_LP로 노출).
(() => {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const root = document.documentElement;

  // ?clean — 에셋 자리 라벨 숨김(리뷰용 스크린샷), ?mode=full|lite|reduce — 모드 강제
  const params = new URLSearchParams(location.search);
  if (params.has('clean')) root.classList.add('is-clean');
  const forced = params.get('mode');
  if (forced === 'full' || forced === 'lite' || forced === 'reduce') root.dataset.mode = forced;
  const mode = root.dataset.mode;

  // ----- 프로토타입 링크: 실서비스 경로 안내 -----
  const toastEl = $('[data-toast]');
  let toastTimer;
  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add('is-on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('is-on'), 2400);
  }
  $$('[data-proto-link]').forEach((a) =>
    a.addEventListener('click', (e) => {
      e.preventDefault();
      toast(`실서비스에서는 ${a.getAttribute('href')}(Google 로그인)로 이동해요`);
    }),
  );

  // ----- 모바일 메뉴 -----
  const menuBtn = $('[data-menu-toggle]');
  const sheet = $('[data-sheet]');
  function setMenu(open) {
    menuBtn.setAttribute('aria-expanded', String(open));
    sheet.hidden = !open;
  }
  menuBtn.addEventListener('click', () => setMenu(sheet.hidden));
  $$('[data-sheet-link]').forEach((a) => a.addEventListener('click', () => setMenu(false)));

  // ----- 내비: 히어로를 지나면 compact, 모바일은 내릴 때 숨기고 올릴 때 보임 -----
  const nav = $('[data-nav]');
  let lastY = window.scrollY;
  function onScroll() {
    const y = window.scrollY;
    const pastHero = y > window.innerHeight * 0.6;
    const goingDown = y > lastY + 2;
    const goingUp = y < lastY - 2;
    nav.classList.toggle('is-compact', pastHero);
    // 모바일·태블릿과 터치(lite) 기기는 내릴 때 내비를 숨긴다 — 가로 iPad에서 내비가 콘텐츠를 가리지 않게
    if ((window.innerWidth < 1024 || root.dataset.mode !== 'full') && sheet.hidden) {
      if (goingDown && pastHero) nav.classList.add('is-hidden');
      else if (goingUp || !pastHero) nav.classList.remove('is-hidden');
    } else {
      nav.classList.remove('is-hidden');
    }
    lastY = y;
  }
  window.addEventListener('scroll', onScroll, { passive: true });

  // ----- 하단 CTA 바: 히어로 CTA·최종 CTA·푸터가 보이는 동안 숨김 -----
  const bar = $('[data-cta-bar]');
  const covering = new Set();
  const barIO = new IntersectionObserver((entries) => {
    entries.forEach((e) => (e.isIntersecting ? covering.add(e.target) : covering.delete(e.target)));
    bar.classList.toggle('is-hidden', covering.size > 0);
  });
  [$('#top .hero__ctas'), $('#cta'), $('[data-footer]')].forEach((el) => el && barIO.observe(el));

  // ----- 섹션 인디케이터 -----
  const dots = new Map($$('[data-indicator] li').map((li) => [li.dataset.for, li]));
  const secIO = new IntersectionObserver(
    (entries) => {
      entries.forEach((e) => {
        if (!e.isIntersecting) return;
        dots.forEach((d) => d.classList.remove('is-on'));
        dots.get(e.target.id)?.classList.add('is-on');
      });
    },
    { rootMargin: '-50% 0px -50% 0px' },
  );
  $$('main > section').forEach((s) => secIO.observe(s));

  // ----- S2 가라오케 (P1: 줄이 화면 가운데를 지날 때 활성) -----
  const lines = $$('[data-karaoke] li');
  function setLine(i) {
    lines.forEach((l, j) => l.classList.toggle('is-active', j === i));
  }
  const lineIO = new IntersectionObserver(
    (entries) => entries.forEach((e) => e.isIntersecting && setLine(lines.indexOf(e.target))),
    { rootMargin: '-42% 0px -42% 0px' },
  );
  lines.forEach((l) => lineIO.observe(l));

  // ----- S3 채팅 급증 그래프 (말한 사람 수, 64% 지점에서 급증) -----
  const PEAK_AT = 0.46; // 데스크톱에서 점프카드(그래프 오른쪽 위)에 가리지 않는 위치
  function speakers(t) {
    // 잔물결 + 피크(가우시안). 결정적이라 매번 같은 모양
    const ripple = 0.22 + 0.05 * Math.sin(t * 23) + 0.04 * Math.sin(t * 57 + 1.3) + 0.03 * Math.sin(t * 101 + 0.7);
    const spike = 0.66 * Math.exp(-Math.pow((t - PEAK_AT) / 0.045, 2));
    const after = 0.12 * Math.exp(-Math.pow((t - (PEAK_AT + 0.08)) / 0.06, 2));
    return Math.min(0.96, ripple + spike + after);
  }
  function drawWave(svg, progress = 1) {
    const W = 600;
    const H = 220;
    const N = 96;
    const pts = [];
    for (let i = 0; i <= N; i++) {
      const t = i / N;
      if (t > progress) break;
      pts.push([t * W, H - 12 - speakers(t) * (H - 36)]);
    }
    const line = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ');
    const last = pts[pts.length - 1] || [0, H];
    const area = `${line} L${last[0].toFixed(1)} ${H} L0 ${H} Z`;
    const dotsSvg = pts
      .filter((_, i) => i % 3 === 0)
      .map(([x, y]) => `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="2.2" />`)
      .join('');
    svg.innerHTML = `
      <defs>
        <linearGradient id="wg-line" x1="0" x2="1" y1="0" y2="0"><stop offset="0" stop-color="#586fc4"/><stop offset="${PEAK_AT}" stop-color="#d63f9f"/><stop offset="1" stop-color="#7d93d9"/></linearGradient>
        <linearGradient id="wg-area" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="#d63f9f" stop-opacity=".28"/><stop offset="1" stop-color="#586fc4" stop-opacity="0"/></linearGradient>
      </defs>
      <g stroke="rgba(255,255,255,.06)">${[0.25, 0.5, 0.75].map((f) => `<line x1="0" x2="${W}" y1="${H * f}" y2="${H * f}"/>`).join('')}</g>
      <path d="${area}" fill="url(#wg-area)"/>
      <path d="${line}" fill="none" stroke="url(#wg-line)" stroke-width="2.5" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
      <g fill="rgba(238,240,243,.55)">${dotsSvg}</g>`;
    return { x: PEAK_AT, y: (H - 12 - speakers(PEAK_AT) * (H - 36)) / H };
  }
  const waveSvg = $('[data-wave]');
  const peakEl = $('[data-peak]');
  const peakPos = drawWave(waveSvg);
  // 그래프 영역(마크업 상 mock__bar 아래)에 피크 표시를 맞춘다
  function placePeak() {
    const box = waveSvg.getBoundingClientRect();
    const host = waveSvg.parentElement.getBoundingClientRect();
    peakEl.style.setProperty('--x', `${((box.left - host.left + box.width * peakPos.x) / host.width) * 100}%`);
    peakEl.style.setProperty('--y', `${box.top - host.top + box.height * peakPos.y}px`);
  }
  // 창 크기만이 아니라 그래프·그 상자의 크기가 바뀔 때마다 다시 맞춘다 — motion.js가 full에서 그래프를
  // min(320px, 32vh)로 줄이면 창은 그대로라 resize가 오지 않는다(1440×800에서 피크가 정점보다 약 12px 아래)
  const peakRO = new ResizeObserver(placePeak);
  peakRO.observe(waveSvg);
  peakRO.observe(waveSvg.parentElement);

  // ----- S4 단계 전환 (P1: 단계 텍스트가 화면 가운데에 오면 활성) -----
  const steps = $$('[data-step]');
  const riderFill = $('[data-rider-fill]');
  const riderPoki = $('[data-rider-poki]');
  const ticks = $$('.rider__ticks li');
  function setStep(n) {
    steps.forEach((s) => s.classList.toggle('is-active', Number(s.dataset.step) === n));
    ticks.forEach((t, i) => t.classList.toggle('is-on', i < n));
    const p = ((n - 1) / 3) * 100;
    riderFill.style.width = `${Math.max(p, 4)}%`;
    riderPoki.style.setProperty('--x', `${p}%`); // CSS가 양 끝에서 화면 안쪽으로 묶는다
  }
  const stepIO = new IntersectionObserver(
    (entries) => entries.forEach((e) => e.isIntersecting && setStep(Number(e.target.closest('[data-step]').dataset.step))),
    { rootMargin: '-45% 0px -45% 0px' },
  );
  steps.forEach((s) => stepIO.observe(s.querySelector('.step__text')));
  setStep(1);

  // ----- S5 트랙 파형 (시드 고정 의사난수) -----
  $$('[data-wave-seed]').forEach((el) => {
    let s = Number(el.dataset.waveSeed) * 7919;
    const rnd = () => (s = (s * 9301 + 49297) % 233280) / 233280;
    const n = 72;
    el.innerHTML = Array.from({ length: n }, (_, i) => {
      const env = 0.55 + 0.45 * Math.sin((i / n) * Math.PI * 3 + s);
      return `<i style="--h:${Math.round(18 + rnd() * 78 * env)}%"></i>`;
    }).join('');
  });

  // ----- S7 갤러리 점 -----
  const gallery = $('[data-gallery]');
  const gDots = $$('[data-gallery-dots] i');
  const cards = $$('.fcard', gallery);
  gallery.addEventListener(
    'scroll',
    () => {
      const step = cards[1] ? cards[1].offsetLeft - cards[0].offsetLeft : 1;
      const idx = Math.min(cards.length - 1, Math.round(gallery.scrollLeft / step));
      gDots.forEach((d, i) => d.classList.toggle('is-on', i === idx));
    },
    { passive: true },
  );

  // ----- S8 띠: 끊김 없는 반복을 위해 내용을 한 벌 더 붙인다(CSS 애니메이션이 -50%까지 이동) -----
  $$('[data-marquee]').forEach((track) => track.insertAdjacentHTML('beforeend', track.innerHTML));

  // ----- 영상: 히어로 루프 · 샘플 클립 (reduce는 포스터만, 재생하지 않는다) -----
  const reduce = mode === 'reduce';
  const heroVideo = $('[data-hero-video]');
  if (heroVideo && !reduce) {
    heroVideo.muted = true;
    heroVideo.src = window.matchMedia('(max-width: 767px)').matches ? heroVideo.dataset.srcMo : heroVideo.dataset.srcPc;
    // 영상 첫 프레임 = 포스터라서, 재생이 시작되면 포스터 위로 조용히 겹친다
    heroVideo.addEventListener('playing', () => heroVideo.classList.add('is-playing'), { once: true });
    new IntersectionObserver(([e]) => (e.isIntersecting ? heroVideo.play().catch(() => {}) : heroVideo.pause())).observe(heroVideo.closest('.hero'));
  }
  if (!reduce) {
    const videoIO = new IntersectionObserver(
      (entries) =>
        entries.forEach((e) => {
          const v = e.target;
          if (e.isIntersecting) {
            if (!v.getAttribute('src')) v.src = v.dataset.src; // 가까워질 때 처음 불러온다
            v.play().catch(() => {});
          } else if (v.getAttribute('src')) {
            v.pause();
          }
        }),
      { rootMargin: '150px 0px' },
    );
    $$('[data-lazy-video]').forEach((v) => {
      v.muted = true;
      videoIO.observe(v);
    });
  }

  // ----- S11: 포키가 뛰어 들어와 손을 흔든 뒤(4초 1회 재생 WebP) 축하 포즈로 넘어간다 -----
  const jumpFig = $('[data-jump]');
  if (jumpFig && !reduce) {
    root.classList.add('jump-ready');
    const jumpImg = $('.cta__jump', jumpFig);
    const jumpIO = new IntersectionObserver(
      ([e]) => {
        if (!e.isIntersecting) return;
        jumpIO.disconnect();
        const land = () => jumpFig.classList.add('is-landed');
        jumpImg.addEventListener(
          'load',
          () => {
            if (jumpFig.classList.contains('is-landed')) return; // 늦게 도착한 점프는 틀지 않는다
            jumpFig.classList.add('is-jumping');
            setTimeout(land, 3400);
          },
          { once: true },
        );
        // jump-ready가 축하 포즈와 소품을 숨겨 두었다 — 점프 WebP(525KB)가 실패하거나 5초 안에 안 오면 바로 축하 포즈를 보인다
        jumpImg.addEventListener('error', land, { once: true });
        setTimeout(() => jumpFig.classList.contains('is-jumping') || land(), 5000);
        jumpImg.src = jumpImg.dataset.src; // 화면에 들어온 순간 불러와야 처음 프레임부터 재생된다
      },
      { threshold: 0.4 },
    );
    jumpIO.observe(jumpFig);
  }

  window.PC_LP = { mode, toast, setStep, setLine, drawWave, speakers, PEAK_AT, observers: { lineIO, stepIO } };
})();
