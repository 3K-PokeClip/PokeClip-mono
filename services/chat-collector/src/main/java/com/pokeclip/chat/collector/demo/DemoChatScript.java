package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * 발표 데모의 「채팅 이벤트」 대본. 시청자가 없는 데모 방송에 몰려드는 임시 시청자 채팅을 만든다.
 *
 * <p>카드는 판별기(chat-detector)가 아니라 {@link DemoHighlight}가 {@link #CARD_AT_CHAT}번째 채팅에서 낸다(사용자 결정,
 * 2026-11-27). 판별기는 급증이 멈추고 사건 묶기 간격 20초를 더 기다려 누른 뒤 약 37초에야 카드를 냈고, 5초 창에 맞춰
 * 세므로 「몇 번째 채팅에서」를 맞출 수 없다. 판별기는 데모 채널({@code demo-…}) 채팅을 아예 안 센다 — 세면 같은 급증에
 * 카드가 한 장 더 선다
 */
final class DemoChatScript {

    /** 급증을 몇 번에 나눠 넣나. 틱 간격({@link DemoChatBurst})이 250ms면 10초 동안 흐른다 */
    static final int BURST_TICKS = 40;

    static final int PER_TICK = 3;

    /**
     * 이 번째 채팅을 넣는 순간 자동 하이라이트 카드를 낸다(사용자 결정, 2026-11-27: 「120건 중 70건에서 고정」). 3건씩 넣으므로
     * 24번째 틱(누른 뒤 5.75초)의 첫 채팅이다
     */
    static final int CARD_AT_CHAT = 70;

    private static final String[] REACTIONS = {
            "ㅋㅋㅋㅋㅋㅋ", "와 미쳤다", "클립각", "ㄷㄷㄷㄷ", "방금 뭐임", "레전드", "이게 되네", "와아아아",
            "실화냐", "개웃기네 ㅋㅋ", "다시 보여줘", "역대급", "ㅋㅋㅋㅋ 이거지", "소름", "대박", "미쳤네 진짜"};

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
     * 한 방송의 시청자 플랫폼. 닉네임마다 무작위로 한 번 정한다(사용자 결정, 2026-11-27): 채팅 번호의 짝·홀로 정하던
     * 때는 채팅창 아이콘이 치지직 · SOOP 번갈아 나와 지어낸 티가 났다. 같은 사람이 줄마다 플랫폼을 바꿔도 티가 나므로 사람 단위로 정한다
     */
    record Audience(List<Platform> byNickname) {

        Platform of(int viewer) {
            return byNickname.get(viewer % byNickname.size());
        }
    }

    /**
     * 방송마다 한 번 정한다(씨앗 = 방송 번호). 누를 때마다 새로 뽑으면 다시 누를 때 같은 닉네임이 반대 플랫폼으로 나오는데,
     * 채팅창은 최근 300줄을 남겨 앞 급증과 함께 보인다. 방송이 다르면 배정도 다르다
     */
    static Audience audienceOf(String streamId) {
        return audience(new SplittableRandom(streamId.hashCode()));
    }

    /** 둘 중 하나로만 쏠릴 확률은 닉네임 40개라 2의 39제곱분의 1이다 */
    static Audience audience(RandomGenerator random) {
        List<Platform> platforms = new ArrayList<>(NICKNAMES.length);
        for (int i = 0; i < NICKNAMES.length; i++) {
            platforms.add(random.nextBoolean() ? Platform.CHZZK : Platform.SOOP);
        }
        return new Audience(List.copyOf(platforms));
    }

    /**
     * 급증 {@code index}번째 틱. 보낸 사람이 틱마다·건마다 달라 같은 ms에 넣어도 지문이 안 겹친다. 채팅 순번(0부터)은
     * {@code index * PER_TICK + j}다 — {@link DemoChatBurst}가 그것으로 {@link #CARD_AT_CHAT}번째를 찾는다
     */
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
