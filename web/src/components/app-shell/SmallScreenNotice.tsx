import Image from 'next/image';

// 휴대폰 크기 화면에서 앱 대신 보이는 안내 (POK-610) — 「PC나 태블릿에서 접속해 주세요」.
// 보이고 숨는 것은 전부 CSS가 정한다(app/small-screen.css — 기준·가리는 방식도 거기 적었다).
// 이 컴포넌트는 늘 그려져 있고 큰 화면에서는 display: none이다. 그래서 JS 없이 첫 그림부터 맞는다
// (훅도 상태도 없는 서버 컴포넌트다).
//
// 루트 레이아웃이 <body> **바로 아래**에 둔다 — CSS가 `body > :not(.pc-small-screen)`으로 나머지를 가리므로
// 한 겹이라도 감싸면 감싼 요소째 가려져 안내까지 사라진다(app/layout.test.tsx가 그 자리를 지킨다).

/** small-screen.css의 선택자와 같은 이름 — 바꾸면 둘을 같이 바꾼다(SmallScreenNotice.test.tsx가 맞춰 본다) */
export const SMALL_SCREEN_CLASS = 'pc-small-screen';

export function SmallScreenNotice() {
  return (
    <main className={SMALL_SCREEN_CLASS} aria-labelledby="small-screen-title">
      {/* 큰 화면에서는 부모가 display: none이라 이 그림은 받지 않는다(기본 lazy) — priority를 붙이면 모든 기기가 받는다 */}
      <Image
        className={`${SMALL_SCREEN_CLASS}__poki`}
        src="/brand/poki-wave.webp"
        alt=""
        width={480}
        height={480}
      />
      <h1 id="small-screen-title" className={`${SMALL_SCREEN_CLASS}__title`}>
        PC나 태블릿에서 접속해 주세요
      </h1>
      <p className={`${SMALL_SCREEN_CLASS}__text`}>
        PokeClip은 큰 화면에 맞춰 만들어져
        <br />
        휴대폰 화면에서는 쓸 수 없어요.
      </p>
      {/* PC에서 창을 좁혀도 이 안내가 뜬다 — 그 사람에게 「PC로 접속하라」는 말은 답이 아니다.
          접속 주소는 적지 않는다(2026-11-19 사용자 결정) */}
      {/* 기준이 CSS px이라 PC에서 브라우저 배율을 키워도 이 안내가 뜬다 — 창을 더 넓힐 수 없는 사람에게 갈 길을 같이 적는다
          (PR #539 2차 리뷰: 1366px 노트북을 250%로 확대하면 546px) */}
      <p className={`${SMALL_SCREEN_CLASS}__hint`}>
        PC에서 보고 있다면 브라우저 창을 넓히거나 화면 확대 배율을 줄여 주세요
      </p>
    </main>
  );
}
