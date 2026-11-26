package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 발표 데모의 「채팅 이벤트」 대본. 시청자가 없는 데모 방송에서 판별기가 반응을 잡게 하려고 임시 채팅을 만든다.
 *
 * <p>🔴 <b>숫자가 판별기(chat-detector) 기본 설정에 기대고 있다.</b> 그쪽 값을 바꾸면 여기도 본다.
 * <ul>
 *   <li>워밍업: 「채팅이 있던 5초 창 24개」가 쌓여야 판정을 시작한다. 시청자가 없는 방송에는 그 창이 없어서
 *       급증만 넣으면 카드가 안 뜬다. 그래서 {@link #calm}이 지난 창마다 한 건씩 채운다(사용자 결정, 2026-11-22)
 *   <li>되돌아보기 150초: 판별기는 지난 150초 안의 창만 다시 센다. 평소 채팅을 그보다 앞에 두면 안 세어진다
 *   <li>최소 건수 10 · 배율 3: 급증 창은 평소(창당 1건)의 수십 배다. 실제 시청자 채팅이 창당 20건쯤까지 있어도 넘는다
 * </ul>
 */
final class DemoChatScript {

    static final long WINDOW_MS = 5_000;

    /** 평소 채팅을 채우는 지난 창 수. 워밍업 24 + 여유 2, 26창 = 130초라 되돌아보기 150초 안이다 */
    static final int CALM_WINDOWS = 26;

    /** 급증을 몇 번에 나눠 넣나. 틱 간격({@link DemoChatBurst})이 250ms면 10초 동안 흐른다 */
    static final int BURST_TICKS = 40;

    /**
     * 누른 뒤 이 틱까지는 같은 방송에서 또 눌러도 무시한다. 틱 간격이 250ms면 45초다. 실제 잠금은 마지막 급증 틱부터
     * 남은 틱만큼 잰다({@link DemoChatBurst#cooldown}) — 실행기가 밀렸다가 밀린 틱을 몰아 돌아도 급증 사이가 안 줄어든다.
     *
     * <p>🔴 판별기 사건 묶기에 기댄다: 튄 창 뒤 20초(episode-gap) 안에 또 튀면 한 사건으로 묶고 카드는 튐이 멈추고
     * 20초가 지나야 나간다. 급증 10초 + 창 정렬 최대 5초 + 간격 20초 + 유예 2초 ≈ 37초(2026-11-25 운영 실측 37.5초)라 그
     * 앞에 또 누르면 카드가 그만큼 밀린다. 2026-11-26 운영: 23초 뒤 두 번째 키가 급증을 이어 붙여 카드가 첫 키 63초 뒤에
     * 나왔고, 발표자는 그 전에 「안 잡혔다」고 보고 수동 마킹을 했다. 판별기 간격을 바꾸면 여기도 본다
     */
    static final int LOCK_TICKS = 180;

    static final int PER_TICK = 3;

    private static final String[] REACTIONS = {
            "ㅋㅋㅋㅋㅋㅋ", "와 미쳤다", "클립각", "ㄷㄷㄷㄷ", "방금 뭐임", "레전드", "이게 되네", "와아아아",
            "실화냐", "개웃기네 ㅋㅋ", "다시 보여줘", "역대급", "ㅋㅋㅋㅋ 이거지", "소름", "대박", "미쳤네 진짜"};

    private static final String[] CALM = {"ㅎㅇ", "안녕하세요", "오늘 뭐해요", "ㅋㅋ", "굿", "재밌다", "오 좋네요"};

    /** 실제 시청자처럼 보이는 가상 닉네임. 실존 인물·채널 이름은 쓰지 않는다 */
    private static final String[] NICKNAMES = {
            "감자튀김왕", "새벽세시", "고양이집사", "퇴근하고싶다", "치킨은반반", "졸린판다", "민트초코러버", "월요병환자",
            "라면한그릇", "구경꾼1호", "눈팅전문", "하늘다람쥐", "떡볶이중독", "야식요정", "출근싫어", "방구석여포",
            "콜라는제로", "주말만기다림", "고구마말랭이", "솜사탕구름", "삼겹살천국", "택배기다리는중", "밤샘장인", "귤까먹는중",
            "멍멍이산책", "아아한잔", "알람끄는요정", "편의점털이", "붕어빵파", "호떡러버", "게임은핑계", "코코아한잔",
            "노래방가자", "비오는날", "느긋한거북이", "치즈볼", "오늘도무사히", "랜선집사", "도토리묵", "반숙계란"};

    private DemoChatScript() {
    }

    /**
     * 한 번 누른 동안의 시청자 플랫폼. 닉네임마다 무작위로 한 번 정한다(사용자 결정, 2026-11-27): 채팅 번호의 짝·홀로 정하던
     * 때는 채팅창 아이콘이 치지직 · SOOP 번갈아 나와 지어낸 티가 났다. 같은 사람이 줄마다 플랫폼을 바꿔도 티가 나므로 사람 단위로 정한다
     */
    record Audience(List<Platform> byNickname) {

        Platform of(int viewer) {
            return byNickname.get(viewer % byNickname.size());
        }
    }

    /** 누를 때마다 새로 정한다. 둘 중 하나로만 쏠릴 확률은 닉네임 40개라 2의 39제곱분의 1이다 */
    static Audience audience(RandomGenerator random) {
        List<Platform> platforms = new ArrayList<>(NICKNAMES.length);
        for (int i = 0; i < NICKNAMES.length; i++) {
            platforms.add(random.nextBoolean() ? Platform.CHZZK : Platform.SOOP);
        }
        return new Audience(List.copyOf(platforms));
    }

    /** 지금 창 바로 앞 창부터 {@link #CALM_WINDOWS}개 창에 한 건씩. 지금 창은 비운다 */
    static List<DemoChat> calm(long nowMillis, Audience audience) {
        long currentWindowStart = nowMillis - Math.floorMod(nowMillis, WINDOW_MS);
        List<DemoChat> chats = new ArrayList<>(CALM_WINDOWS);
        for (int i = 1; i <= CALM_WINDOWS; i++) {
            long at = currentWindowStart - i * WINDOW_MS + 1_000;
            int viewer = 900 + i % 8;
            chats.add(chat(viewer, CALM[i % CALM.length], at, audience));
        }
        return chats;
    }

    /** 급증 {@code index}번째 틱. 보낸 사람이 틱마다·건마다 달라 같은 ms에 넣어도 지문이 안 겹친다 */
    static List<DemoChat> tick(int index, long nowMillis, Audience audience) {
        List<DemoChat> chats = new ArrayList<>(PER_TICK);
        for (int j = 0; j < PER_TICK; j++) {
            int n = index * PER_TICK + j;
            chats.add(chat(n, REACTIONS[n % REACTIONS.length], nowMillis, audience));
        }
        return chats;
    }

    private static DemoChat chat(int viewer, String text, long at, Audience audience) {
        String id = "demo-viewer-%03d".formatted(viewer);
        return new DemoChat(audience.of(viewer), id, NICKNAMES[viewer % NICKNAMES.length], text, at);
    }
}
