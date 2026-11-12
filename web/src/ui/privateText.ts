/**
 * 사람을 가리키거나 사람이 쓴 글자(이름·이메일·제목 등)를 감싸는 클래스 (POK-572). 앱의 세션 녹화(PostHog)가 이 요소 안의
 * 글자를 `*`로 가린다(`@/lib/analytics/scrub` maskReplayText) — 녹화는 그 밖의 글자(단추·메뉴·안내문)를 그대로 담는다.
 * 화면도 이 값을 여기서 가져다 쓴다: 디자인 시스템은 앱의 분석 코드를 가져오지 않고, 분석 모듈은 테스트가 통째로 목 하는
 * 일이 많아 상수를 거기 두면 그 테스트가 깨진다. 값은 posthog-js 기본 `maskTextClass`와 같다.
 */
export const PRIVATE_TEXT_CLASS = 'ph-mask';
