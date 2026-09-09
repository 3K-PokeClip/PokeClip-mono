package com.pokeclip.chat.collector.persist;

/**
 * 표에 넣을 후원 한 건. {@code PersistableChat}과 같은 규칙으로 toString을 두지 않는다 —
 * 닉네임·후원 문구가 개인식별값이자 본문이다.
 *
 * <p><b>시각 칸이 하나뿐이다.</b> 치지직 후원 이벤트에는 시각이 없어서(공식 문서 확인)
 * 우리가 받은 시각만 남는다 — 채팅({@code message_time}, 치지직 시계)과 <b>축이 다르다</b>.
 *
 * @param payAmount    숫자로 못 읽으면 null. 그래도 후원 자체는 버리지 않는다
 * @param donationText 없으면 빈 문자열이다(표가 NOT NULL) — 「문구 없는 후원」이 정상이다
 */
public record PersistableDonation(
        String streamId,
        String channelId,
        String donatorChannelId,
        String donatorNickname,
        String donationType,
        Long payAmount,
        String donationText,
        long receivedAtMillis,
        /**
         * 🔴 이 프로세스가 받은 순번. <b>내용 해시가 아니라 순번인 이유</b>는
         * 「같은 사람이 같은 금액·문구로 연달아 두 번」이 정당한 후원인데 해시로는
         * 그것이 접히기 때문이다 — 시각을 우리가 찍으므로 <b>연속 수신의 99%가
         * 같은 밀리초</b>다(실측). 순번은 수신 시점에 매겨져 재시도 때도 그대로
         * 다시 들어가므로 재시도 중복만 접는다.
         */
        long receivedSeq) {

    public PersistableDonation {
        // PG TEXT가 거부하는 것은 사실상 NUL뿐이다. 채팅과 같은 자리(생성 지점)에서 지운다 —
        // 후원에는 지문 해시가 없으므로 「해시와 저장이 같은 본문」이라는 이유는 없지만,
        // 한 글자 때문에 배치 전체가 22021로 죽는 것은 똑같다.
        donatorNickname = donatorNickname == null ? null : donatorNickname.replace("\0", "");
        donationText = donationText == null ? "" : donationText.replace("\0", "");
        // 🔴 종류도 지운다 — 여기 빠져 있었다(POK-234 도장 감사, 실 PG 재현).
        // 이 칸은 치지직 봉투의 donationType이 그대로 오는 자리이고, 이 클래스의 앞 두 줄과
        // 달리 아무도 안 걸렀다. NUL이 하나 들어오면 실 PG가 invalid byte sequence 0x00 으로
        // 거부하는데, DonationPersister에는 「나쁜 한 건만 버리는」 격리가 없어
        // 배치가 되돌려지고 1초마다 영원히 재시도한다 — 그 방송의 후원 저장이 통째로 멎는다.
        // 채팅은 원본이 S3에 남지만 후원은 아카이브가 없어 되찾을 길이 없다.
        // null도 문구와 같이 빈 문자열로 접는다 — 표가 둘 다 NOT NULL이라 여기서
        // 안 접으면 저장 실패가 되고, 그 결말이 바로 위에 적은 것과 같다.
        // 지금 운영 경로에서는 디코더가 빈 문자열을 주므로 도달하지 않는다.
        // 그 보증이 디코더 한 줄에만 서 있어서 여기서도 막는다(문구와 같은 모양).
        donationType = donationType == null ? "" : donationType.replace("\0", "");
        // 🔴 식별자 둘도 같이 지운다 — 봇(codex P1)이 잡았다. 앞의 셋만 고치고 여기를
        // 빠뜨렸는데, 표가 이 둘도 NOT NULL TEXT라 결말이 정확히 같다.
        //
        // 🔴 <b>그리고 대가가 「그 방송」이 아니라 「모든 방송」이다.</b> 바구니가 프로세스에
        // 하나뿐이라(services/chat-collector CLAUDE.md 「공유 바구니 셋」) 한 방송의 나쁜
        // 후원 한 건이 배치를 물고 늘어지면 <b>그 바구니를 함께 쓰는 다른 방송의 후원까지</b>
        // 저장이 멎는다. 위 문단이 「그 방송」이라 적은 것은 범위를 좁게 본 것이다.
        //
        // 🔴 <b>「같은 뿌리인데 한 자리만」이 이 저장소에서 반복해 난 실수다.</b> 다음에
        // 이 생성자에 칸을 더하는 사람은 <b>표의 NOT NULL TEXT 칸 전부</b>를 세고 시작하라.
        channelId = channelId == null ? "" : channelId.replace("\0", "");
        donatorChannelId = donatorChannelId == null ? "" : donatorChannelId.replace("\0", "");
    }
}
