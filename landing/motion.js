// PokeClip 랜딩 시안 (POK-269) — P2 모션: GSAP + ScrollTrigger + SplitText (+ Lenis는 Full만).
// 모드: full(정밀 포인터 데스크톱, 핀·스크럽·포키 스테이지) / lite(핀 없이 스크럽·진입 리빌) / reduce(정적, 이 파일은 바로 빠진다)
(() => {
  const root = document.documentElement;
  const done = () => root.classList.remove('motion-pending');
  const mode = root.dataset.mode;
  const LP = window.PC_LP;
  if (mode === 'reduce' || !window.gsap || !window.ScrollTrigger || !LP) {
    done();
    return;
  }
  const full = mode === 'full';
  root.classList.add('motion-on', full ? 'motion-full' : 'motion-lite');

  const { gsap, ScrollTrigger } = window;
  gsap.registerPlugin(ScrollTrigger, ...(window.SplitText ? [window.SplitText] : []));
  ScrollTrigger.config({ ignoreMobileResize: true });
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const clamp01 = gsap.utils.clamp(0, 1);
  const seg = (p, a, b) => clamp01((p - a) / (b - a)); // 진행률 p를 [a,b] 구간의 0..1로

  // P1의 IntersectionObserver 기반 전환은 모션 타임라인이 대신한다
  LP.observers.lineIO.disconnect();
  if (full) LP.observers.stepIO.disconnect();

  // ===== Lenis (Full만) =====
  let lenis = null;
  if (full && window.Lenis) {
    lenis = new window.Lenis({ lerp: 0.1, smoothWheel: true });
    lenis.on('scroll', ScrollTrigger.update);
    gsap.ticker.add((t) => lenis.raf(t * 1000));
    gsap.ticker.lagSmoothing(0);
  }
  // 앵커 이동: 핀 섹션은 핀이 끝난 뒤 요소가 핀 끝 위치로 옮겨져 있어서, 요소 위치로 가면 핀 끝(마지막 장면)에 떨어진다.
  // 핀을 감싼 pin-spacer의 시작점으로 가고, 맨 위(#top)는 정확히 0, 핀이 아닌 섹션만 내비 높이만큼 비켜 간다
  function anchorY(target) {
    if (target.id === 'top') return 0;
    const spacer = target.parentElement?.classList.contains('pin-spacer') ? target.parentElement : null;
    const y = (spacer || target).getBoundingClientRect().top + window.scrollY;
    return spacer ? y : y - 72;
  }
  $$('a[href^="#"]').forEach((a) =>
    a.addEventListener('click', (e) => {
      const id = a.getAttribute('href');
      const target = id.length > 1 && document.querySelector(id);
      if (!target) return;
      e.preventDefault();
      if (lenis) lenis.scrollTo(anchorY(target), { duration: 1.4 });
      else window.scrollTo({ top: anchorY(target), behavior: 'smooth' });
      // 기본 이동을 막았으니 포커스도 직접 옮긴다 — 안 옮기면 「본문으로 건너뛰기」 뒤 다음 Tab이 내비로 돌아가 화면이 다시 위로 튄다
      if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
      target.focus({ preventScroll: true });
    }),
  );

  // ===== S1 히어로: 로드 인트로 =====
  const h1 = $('.hero h1');
  const lines = window.SplitText ? window.SplitText.create(h1, { type: 'lines', mask: 'lines', linesClass: 'h1-line' }).lines : [h1];
  const intro = gsap.timeline({ defaults: { ease: 'expo.out' } });
  intro
    .from('.nav__pill', { y: -24, opacity: 0, duration: 0.9 }, 0.05)
    .from('.hero__scene', { opacity: 0, duration: 1.4, ease: 'power2.out' }, 0)
    .from('.chip--beta', { y: 16, opacity: 0, duration: 0.8 }, 0.2)
    .from(lines, { yPercent: 105, duration: 1.0, stagger: 0.12 }, 0.28)
    .from('.hero__copy .lead', { y: 20, opacity: 0, duration: 0.8 }, 0.62)
    .from('.hero__copy .btn', { y: 16, opacity: 0, duration: 0.7, stagger: 0.08 }, 0.74)
    .from(['.hero__poster img', '.hero__video'], { scale: 1.08, duration: 2.2, ease: 'power2.out' }, 0);
  done();

  // 히어로 스크롤: Full은 핀 + 인셋 카드 → 풀블리드(clip-path), Lite는 스케일만
  if (full) {
    gsap
      .timeline({ scrollTrigger: { trigger: '.hero', start: 'top top', end: '+=110%', pin: true, scrub: 0.6 } })
      .fromTo('.hero__card', { clipPath: 'inset(10px round 40px)' }, { clipPath: 'inset(0px round 0px)', ease: 'none' }, 0)
      .fromTo('.hero__scene', { scale: 1.14 }, { scale: 1, ease: 'none' }, 0)
      .to('.hero__copy', { y: -60, opacity: 0, ease: 'power1.in', duration: 0.6 }, 0.4)
      .to(['.hero__scroll', '.hero__media .slot-label'], { opacity: 0, duration: 0.2 }, 0);
  } else {
    gsap.fromTo('.hero__scene', { scale: 1.08 }, { scale: 1, ease: 'none', scrollTrigger: { trigger: '.hero', start: 'top top', end: 'bottom top', scrub: true } });
  }

  // ===== S2 공감: 가라오케 (활성 줄 확대 + 글자 채움) =====
  const kLines = $$('[data-karaoke] li');
  function karaoke(p) {
    const n = kLines.length;
    const f = p * n;
    const idx = Math.min(n - 1, Math.floor(f));
    kLines.forEach((l, i) => {
      l.classList.toggle('is-active', i === idx);
      l.style.setProperty('--fill', i < idx ? '100%' : i === idx ? `${Math.min(100, (f - idx) * 160)}%` : '0%');
    });
  }
  karaoke(0);
  // lite(모바일·태블릿)는 핀이 없으면 리스트가 화면을 지나는 짧은 거리 안에 네 줄이 다 칠해져 너무 빠르다.
  // 섹션이 한 화면에 들어오면 이 섹션만 화면 가운데에 잠깐 멈춰 1.1화면 동안 칠하고, 안 들어오면 칠하는 구간만 길게 잡는다
  const fits = (el) => el.offsetHeight <= window.innerHeight * 0.96;
  const painFits = fits($('.pain'));
  ScrollTrigger.create(
    full
      ? { trigger: '.pain', start: 'top top', end: '+=150%', pin: true, scrub: true, onUpdate: (s) => karaoke(s.progress) }
      : painFits
        ? { trigger: '.pain', start: 'center center', end: '+=110%', pin: true, scrub: 0.4, onUpdate: (s) => karaoke(s.progress) }
        : { trigger: '.karaoke', start: 'top 85%', end: 'bottom 15%', scrub: true, onUpdate: (s) => karaoke(s.progress) },
  );

  // ===== S3 채팅 급증 감지 =====
  const chat = $('[data-chat] ul');
  chat.innerHTML += chat.innerHTML + chat.innerHTML;
  const rate = $('[data-chat-rate]');
  const status = $('[data-status]');
  const peak = $('[data-peak]');
  const card = $('[data-jumpcard]');
  const wave = $('[data-wave]');
  const signals = $$('[data-signal]');
  let hit = null;
  function setHit(on) {
    if (on === hit) return;
    hit = on;
    status.classList.toggle('is-hit', on);
    status.innerHTML = on ? '<i></i>하이라이트 감지' : '<i class="spinner" aria-hidden="true"></i>분석 중…';
    peak.classList.toggle('is-on', on);
    card.classList.toggle('is-on', on);
  }
  function detect(p) {
    const w = 0.12 + 0.88 * seg(p, 0, 0.55); // 그래프가 그려진 비율
    wave.style.clipPath = `inset(0 ${((1 - w) * 100).toFixed(2)}% 0 0)`;
    const surge = seg(w, LP.PEAK_AT - 0.14, LP.PEAK_AT + 0.02) * (1 - 0.6 * seg(w, LP.PEAK_AT + 0.14, 1));
    rate.textContent = `분당 ${Math.round(42 + 276 * surge)}`;
    // 신호 셋 — 채팅과 목소리는 급증과 함께, 후원은 한 박자 늦게 몰린다
    const gift = seg(w, LP.PEAK_AT - 0.04, LP.PEAK_AT + 0.06) * (1 - 0.5 * seg(w, LP.PEAK_AT + 0.16, 1));
    signals.forEach((el) => {
      const kind = el.dataset.signal;
      const v = kind === 'chat' ? 0.26 + 0.66 * surge : kind === 'donation' ? 0.1 + 0.62 * gift : 0.3 + 0.54 * surge;
      el.style.setProperty('--v', v.toFixed(3));
      el.querySelector('[data-signal-val]').textContent =
        kind === 'chat' ? `×${(1 + 2.4 * surge).toFixed(1)}` : kind === 'donation' ? `${Math.round(5 * gift)}건` : surge > 0.6 ? '높음' : surge > 0.25 ? '올라감' : '보통';
    });
    setHit(w >= LP.PEAK_AT + 0.02);
  }
  if (full) {
    // S3 → S4: 계단식 그라데이션 막대 커튼 — 핀 끝부분에서 좁은 마젠타 막대부터 솟아 화면을 덮고,
    // S4가 올라오는 동안 위로 빠진다. CSS의 translateY(101%)를 GSAP이 y(px)로 읽으므로 y:0으로 지운다
    const bars = $$('[data-curtain] i');
    gsap.set(bars, { y: 0, yPercent: 101 });
    const tl = gsap.timeline({ scrollTrigger: { trigger: '.detect', start: 'top top', end: '+=220%', pin: true, scrub: 0.5, onUpdate: (s) => detect(s.progress) } });
    tl.to(chat, { yPercent: -55, ease: 'power2.in', duration: 0.6 }, 0)
      .fromTo('.stage__prop', { scale: 0, opacity: 0 }, { scale: 1, opacity: 1, ease: 'back.out(2)', stagger: 0.05, duration: 0.12 }, 0.3)
      .fromTo(card, { opacity: 0, y: 60, scale: 0.85, rotate: -6 }, { opacity: 1, y: 0, scale: 1, rotate: 2, ease: 'back.out(1.7)', duration: 0.14 }, 0.5)
      .to(bars, { yPercent: 0, ease: 'power2.inOut', stagger: { each: 0.06, from: 'end' }, duration: 0.2 }, 0.78)
      // 막대가 화면을 다 덮은 뒤 S3 내용을 숨긴다 — 빠질 때 계단 사이 틈으로 돋보기 포키·말풍선이 비치지 않게
      .to('.detect > .container', { autoAlpha: 0, duration: 0.001 }, '>');
    // 빠질 때는 y(px)를 움직인다 — 들어올 때의 yPercent와 같은 속성을 두 트윈이 다투지 않게.
    // 순서는 들어올 때의 역순: 화면을 덮은 다크 막대가 먼저 빠지고 색 막대가 계단처럼 뒤따른다
    gsap.to(bars, {
      y: () => -window.innerHeight * 1.02,
      ease: 'power2.inOut',
      stagger: { each: 0.08, from: 'start' },
      scrollTrigger: { trigger: '.how', start: 'top 95%', end: 'top 10%', scrub: 0.5, invalidateOnRefresh: true },
    });
  } else {
    detect(0);
    ScrollTrigger.create({ trigger: '.stage', start: 'top 70%', end: 'bottom 45%', scrub: true, onUpdate: (s) => detect(s.progress) });
  }

  // ===== S4 작동 방식: Full은 핀 + 아코디언 + 라이더 =====
  if (full) {
    const fill = $('[data-rider-fill]');
    const rider = $('[data-rider-poki]');
    let cur = 0;
    ScrollTrigger.create({
      trigger: '.how',
      start: 'top top',
      end: '+=300%',
      pin: true,
      scrub: true,
      onUpdate: (s) => {
        const n = Math.min(4, Math.floor(s.progress * 4) + 1);
        if (n !== cur) {
          cur = n;
          LP.setStep(n);
        }
        fill.style.width = `${Math.max(4, s.progress * 100)}%`;
        rider.style.setProperty('--x', `${s.progress * 100}%`);
        rider.classList.toggle('is-alt', Math.floor(s.progress * 60) % 2 === 1); // 달리기 2프레임
      },
    });
  }

  // ===== S5 BGM 분리 =====
  const bgmRow = $('[data-bgm-track]');
  const bgmToggle = bgmRow.querySelector('.tracks__toggle');
  function setBgmMuted(on) {
    bgmRow.classList.toggle('is-muted', on);
    bgmToggle.classList.toggle('is-on', !on);
    bgmToggle.classList.toggle('tracks__toggle--off', on);
    bgmToggle.textContent = on ? '제외' : '켜짐';
  }
  setBgmMuted(false);
  const trackRows = $$('.tracks__list li');
  if (full) {
    gsap.set(['.bgm .section-head > *', '.tracks'], { opacity: 0, y: 40 });
    gsap.set(trackRows, { opacity: 0, x: -24 });
    let muted = false;
    gsap
      .timeline({
        scrollTrigger: {
          trigger: '.bgm', start: 'top top', end: '+=200%', pin: true, scrub: 0.5,
          onUpdate: (s) => {
            const m = s.progress > 0.72;
            if (m !== muted) setBgmMuted((muted = m));
          },
        },
      })
      .to('[data-split-left]', { xPercent: -140, opacity: 0, filter: 'blur(8px)', ease: 'power2.in', duration: 0.3 }, 0.02)
      .to('[data-split-right]', { xPercent: 140, opacity: 0, filter: 'blur(8px)', ease: 'power2.in', duration: 0.3 }, 0.02)
      .to('[data-split-pill]', { scaleX: 9, scaleY: 7, opacity: 0, ease: 'power2.in', duration: 0.25 }, 0.12)
      .to('.bgm .section-head > *', { opacity: 1, y: 0, stagger: 0.04, duration: 0.18, ease: 'power2.out' }, 0.28)
      .to('.tracks', { opacity: 1, y: 0, duration: 0.18, ease: 'power2.out' }, 0.34)
      .to(trackRows, { opacity: 1, x: 0, stagger: 0.035, duration: 0.12, ease: 'power2.out' }, 0.42)
      .to({}, { duration: 0.3 });
  } else {
    // lite는 좌우로 조금만 벌린다 — 단어 폭의 %로 밀면 긴 "게임 사운드"가 먼저 화면 밖으로 잘린다
    gsap.to('[data-split-left]', { x: -24, opacity: 0.25, ease: 'none', scrollTrigger: { trigger: '.split', start: 'top 85%', end: 'top 25%', scrub: true } });
    gsap.to('[data-split-right]', { x: 24, opacity: 0.25, ease: 'none', scrollTrigger: { trigger: '.split', start: 'top 85%', end: 'top 25%', scrub: true } });
    ScrollTrigger.create({ trigger: '.tracks', start: 'center 65%', onEnter: () => setBgmMuted(true), onLeaveBack: () => setBgmMuted(false) });
  }

  // ===== S6 스트리머 × 편집자: Full은 카드 스택 =====
  if (full) {
    const [a, b, c] = $$('.stack__card');
    gsap.set([b, c], { yPercent: 130 });
    gsap
      .timeline({ scrollTrigger: { trigger: '.team', start: 'top top', end: '+=150%', pin: true, scrub: 0.5 } })
      .to(b, { yPercent: 0, y: 44, ease: 'power2.out', duration: 1 }, 0)
      .to(a, { scale: 0.94, rotateX: -6, opacity: 0.55, ease: 'power2.out', duration: 1 }, 0)
      .to(c, { yPercent: 0, y: 88, ease: 'power2.out', duration: 1 }, 1)
      .to(a, { scale: 0.88, y: -12, opacity: 0.35, duration: 1 }, 1)
      .to(b, { scale: 0.94, rotateX: -6, opacity: 0.6, duration: 1 }, 1)
      .to({}, { duration: 0.4 });
  }

  // ===== S7 기능 갤러리: Full은 가로 핀 스크럽, Lite는 자동 재생 =====
  const gallery = $('[data-gallery]');
  const track = $('[data-gallery-track]');
  const gDots = $$('[data-gallery-dots] i');
  const cards = $$('.fcard', track);
  const setDot = (i) => gDots.forEach((d, j) => d.classList.toggle('is-on', j === i));
  if (full) {
    const dist = () => Math.max(0, track.scrollWidth - gallery.clientWidth + 2 * parseFloat(getComputedStyle(gallery).paddingLeft));
    gsap.to(track, {
      x: () => -dist(),
      ease: 'none',
      scrollTrigger: {
        trigger: '.features', start: 'top top', end: () => `+=${dist()}`, pin: true, scrub: 0.5, invalidateOnRefresh: true,
        onUpdate: (s) => setDot(Math.min(cards.length - 1, Math.round(s.progress * (cards.length - 1)))),
      },
    });
  } else {
    let pauseUntil = 0;
    let visible = false;
    gallery.addEventListener('pointerdown', () => (pauseUntil = Date.now() + 8000), { passive: true });
    ScrollTrigger.create({ trigger: gallery, start: 'top 80%', end: 'bottom 20%', onToggle: (s) => (visible = s.isActive) });
    setInterval(() => {
      if (!visible || Date.now() < pauseUntil || document.hidden) return;
      // 지금 보이는 카드에서 이어 간다 — 따로 센 번호를 쓰면 사용자가 직접 넘긴 뒤 앞 카드로 되감긴다.
      // 끝 카드는 스크롤 끝에 막혀 제 자리까지 못 가고 스냅이 앞 카드로 되돌리니(900px에서 1736 → 1302),
      // 스크롤로 닿는 마지막 카드에 왔으면 처음으로 돌아간다. 「스크롤 끝에 닿았나」로 보면 스냅 때문에 끝에서 멈춘다
      const step = cards[1] ? cards[1].offsetLeft - cards[0].offsetLeft : 1;
      const last = Math.min(cards.length - 1, Math.floor((gallery.scrollWidth - gallery.clientWidth + 2) / step));
      const cur = Math.round(gallery.scrollLeft / step);
      const next = cur >= last ? 0 : cur + 1;
      gallery.scrollTo({ left: cards[next].offsetLeft - cards[0].offsetLeft, behavior: 'smooth' });
    }, 3600);
  }

  // ===== S8 숫자 롤링 + 마키 속도 =====
  $$('.numbers dd[data-count]').forEach((dd) => {
    const to = Number(dd.dataset.count);
    const node = dd.firstChild;
    const o = { v: to === 0 ? 3 : 0 };
    ScrollTrigger.create({
      trigger: dd, start: 'top 85%', once: true,
      onEnter: () => gsap.to(o, { v: to, duration: 1.2, ease: 'power3.out', onUpdate: () => (node.nodeValue = String(Math.round(o.v))) }),
    });
  });
  if (full) {
    const anims = $$('[data-marquee]').flatMap((el) => el.getAnimations());
    let boost = 0;
    ScrollTrigger.create({ trigger: '.bands', start: 'top bottom', end: 'bottom top', onUpdate: (s) => (boost = Math.min(4, Math.abs(s.getVelocity()) / 500)) });
    gsap.ticker.add(() => {
      boost *= 0.92;
      anims.forEach((a) => (a.playbackRate = 1 + boost));
    });
  }

  // S11 최종 CTA: 화면에 잠깐 멈춰 점프 → 축하(main.js)가 끝까지 보이게 한다.
  // Full은 다른 핀 섹션처럼 한 화면 높이로 맞춰(CSS) 위에서 멈추고, lite는 한 화면에 들어올 때만 가운데에서 멈춘다
  const ctaSec = $('.cta');
  const ctaFits = fits(ctaSec);
  if (full) ScrollTrigger.create({ trigger: ctaSec, start: 'top top', end: '+=90%', pin: true });
  else if (ctaFits) ScrollTrigger.create({ trigger: ctaSec, start: 'center center', end: '+=90%', pin: true });
  // lite의 핀 여부(공감·CTA)는 지금 화면 높이로 한 번 정한다. 기기를 돌려 판정이 뒤집히면 새로고침한다 — 그대로 두면
  // 화면보다 긴 섹션이 가운데 고정돼 잘리고, 핀만 다시 만들면 refresh 순서가 맨 뒤로 밀려 아래 트리거들이 그 핀 간격을 모른다.
  // resize가 아니라 회전만 본다 — 모바일 주소창이 접히며 높이가 바뀔 때마다 판정이 흔들리면 스크롤 중에 새로고침된다
  if (!full) {
    window.matchMedia('(orientation: portrait)').addEventListener('change', () => {
      if (fits($('.pain')) !== painFits || fits(ctaSec) !== ctaFits) location.reload();
    });
  }

  // ===== 공통 진입 리빌 (핀 타임라인이 맡는 요소는 제외) =====
  // 핀을 모두 만든 뒤에 만든다 — ScrollTrigger는 생성 순서대로 refresh해서, 핀보다 먼저 만든 트리거는 그 핀 간격을
  // 모른다. 앞에 두었을 때 full(1800×1009)에서 S8~S10 리빌 start가 11,289px 앞서 화면 밖에서 끝났다
  const revealSel = [
    '.pain .eyebrow', '.pain .h2',
    '.detect .section-head > *',
    '.how .section-head > *', '.how .rider',
    '.team .section-head > *',
    '.features .section-head > *',
    '.platforms .section-head > *', '.logos__item', '.shorts', '.numbers > div',
    '.pricing .section-head > *', '.plan',
    '.faq .section-head > *', '.faq__list details',
    '.cta__inner > :not(.cta__poki)', // CTA 포키는 점프 시퀀스(main.js)가 등장시킨다
  ];
  if (!full) revealSel.push('.bgm .section-head > *', '.tracks', '.step', '.stack__card', '.fcard', '.stage > :not(.jumpcard)');
  const reveals = $$(revealSel.join(','));
  gsap.set(reveals, { opacity: 0, y: 24 });
  ScrollTrigger.batch(reveals, {
    start: 'top 88%',
    once: true,
    onEnter: (batch) => gsap.to(batch, { opacity: 1, y: 0, duration: 0.8, ease: 'expo.out', stagger: 0.08, overwrite: true }),
  });

  // Full: 섹션 제목 단어별 블러 리빌(스크럽). 위 리빌이 숨긴 제목을 다시 보이게 하므로 리빌 뒤에 둔다
  if (full && window.SplitText) {
    ['.team .h2', '.features .h2', '.platforms .h2'].forEach((sel) => {
      const el = $(sel);
      gsap.set(el, { opacity: 1, y: 0 });
      const words = window.SplitText.create(el, { type: 'words' }).words;
      gsap.fromTo(
        words,
        { opacity: 0, y: 24, filter: 'blur(12px)' },
        { opacity: 1, y: 0, filter: 'blur(0px)', stagger: 0.08, ease: 'none', scrollTrigger: { trigger: el, start: 'top 85%', end: 'top 45%', scrub: 0.6 } },
      );
    });
  }

  // ===== 포키 스테이지 (Full): 섹션의 포키 자리를 따라 날아다닌다 =====
  if (full) {
    const stage = $('[data-poki-stage]');
    const poses = new Map($$('[data-pose]', stage).map((img) => [img.dataset.pose, img]));
    const anchors = $$('[data-poki-inline]').filter((el) => el.dataset.pokiInline !== 'cheer');
    const SIZE = 420;
    const cur = { x: 0, y: 0, s: 300, o: 0 };
    const tgt = { ...cur };
    let pose = null;
    let first = true;
    function setPose(name) {
      if (name === pose) return;
      pose = name;
      poses.forEach((img, key) => img.classList.toggle('is-on', key === name));
    }
    const curtainBars = $$('[data-curtain] i');
    gsap.ticker.add(() => {
      const vh = window.innerHeight;
      // S3→S4 막대가 화면에 걸려 있는 동안은 포키를 즉시 숨긴다(계단 틈으로 이전 포즈가 비치지 않게)
      if (curtainBars.some((b) => { const r = b.getBoundingClientRect(); return r.bottom > 0 && r.top < vh; })) {
        cur.o = tgt.o = 0;
        stage.style.opacity = '0';
        return;
      }
      let best = null;
      let bestD = Infinity;
      for (const el of anchors) {
        if (el.closest('.container')?.style.visibility === 'hidden') continue; // 커튼 뒤로 숨긴 섹션의 자리
        const r = (el.querySelector('img') || el).getBoundingClientRect();
        if (r.bottom < vh * 0.05 || r.top > vh * 0.95 || r.width === 0) continue;
        const d = Math.abs(r.top + r.height / 2 - vh / 2);
        if (d < bestD) {
          bestD = d;
          best = { el, r };
        }
      }
      if (best) {
        tgt.x = best.r.left + best.r.width / 2;
        tgt.y = best.r.top + best.r.height / 2;
        tgt.s = Math.min(best.r.width, SIZE);
        tgt.o = 1;
        setPose(best.el.dataset.pokiInline);
        if (first) {
          Object.assign(cur, tgt, { o: 0 });
          first = false;
        }
      } else {
        tgt.o = 0;
      }
      cur.x += (tgt.x - cur.x) * 0.12;
      cur.y += (tgt.y - cur.y) * 0.12;
      cur.s += (tgt.s - cur.s) * 0.12;
      cur.o += (tgt.o - cur.o) * 0.15;
      const tilt = gsap.utils.clamp(-14, 14, (tgt.x - cur.x) * 0.03);
      stage.style.opacity = cur.o.toFixed(3);
      stage.style.transform = `translate3d(${(cur.x - SIZE / 2).toFixed(1)}px, ${(cur.y - SIZE / 2).toFixed(1)}px, 0) scale(${(cur.s / SIZE).toFixed(3)}) rotate(${tilt.toFixed(2)}deg)`;
    });
  }

  // 폰트·이미지 로드 후 위치 재계산
  document.fonts?.ready.then(() => ScrollTrigger.refresh());
  window.addEventListener('load', () => ScrollTrigger.refresh());
  // FAQ를 열고 닫으면 그 아래 CTA 핀 위치가 바뀐다(5개를 다 열면 322px). 연달아 눌러도 한 프레임에 한 번만 다시 잰다
  let faqRefresh = 0;
  $$('.faq__list details').forEach((d) =>
    d.addEventListener('toggle', () => {
      cancelAnimationFrame(faqRefresh);
      faqRefresh = requestAnimationFrame(() => ScrollTrigger.refresh());
    }),
  );
})();
