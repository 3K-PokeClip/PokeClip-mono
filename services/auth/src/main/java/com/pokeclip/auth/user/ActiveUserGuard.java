package com.pokeclip.auth.user;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.DataInconsistencyException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <b>「이 회원이 아직 살아있나」를 묻는 유일한 자리.</b> 회원에게 무언가를 새로 만들어 주는
 * 쓰기 경로는 전부 이 문을 지난다.
 *
 * <p><b>왜 필요한가 — 회원 행 락이 그것을 안 막는다</b>(PR #148 감사 실측).
 * {@link UserRepository#findByIdForUpdate}에 붙은 {@code PESSIMISTIC_WRITE}가 PostgreSQL에서 내는 것은
 * {@code FOR UPDATE}가 아니라 <b>{@code FOR NO KEY UPDATE}</b>다. 탐침으로 확정했다 —
 * {@code FOR KEY SHARE NOWAIT}가 <b>충돌 없이 통과</b>한다. 그 말은 <b>자식 표 INSERT(외래키 검사)가
 * 이 락에 안 막힌다</b>는 뜻이고, {@code pairing_codes}·{@code stream_keys}·{@code editor_delegations}·
 * 두 연동 표·{@code refresh_tokens}가 전부 거기 든다.
 * 즉 <b>「탈퇴가 도는 동안에는 새 것이 안 생긴다」는 애초에 성립하지 않았다.</b>
 *
 * <p>🔴 <b>그래서 락을 세게 바꾸는 것이 답이 아니다.</b> {@code findByIdForUpdate}를 진짜
 * {@code FOR UPDATE}로 올리면 자식 INSERT도 막히지만, 그 락은 토큰 회전·스트림키 재발급·채널 갱신이
 * <b>전부 쓰는 공유물</b>이라 그 경로들의 경합 성질이 통째로 달라진다. 대신 <b>탈퇴 표시를 본다.</b>
 *
 * <p>🔴 <b>확인을 자리마다 복붙하지 않는 이유</b> — 이 저장소에서 「같은 뿌리인데 한 자리만 고침」이
 * 네 세션 연속 났다(POK-118·120·121·174). {@code isWithdrawn()}을 자리마다 복붙하면 새 자리가
 * 생기는 날 조용히 빠진다. <b>전수 명부는 아래에 있고 {@code WithdrawalWriteGuardRegistryTest}가
 * 그 숫자를 기계로 센다.</b>
 *
 * <h2>이 문이 <b>못</b> 닫는 것</h2>
 *
 * <p><b>락을 안 잡는 갈래는 「읽고 나서 쓴다」 사이가 남는다.</b> {@link #requireAlive(Long, String)}은
 * 탈퇴 트랜잭션이 <b>커밋한 뒤에</b> 도착한 요청을 막는다 — 커밋 <b>전</b>(회수는 끝났고 아직 안 열린 창)에
 * 읽으면 살아있는 것으로 보인다. 그 창의 폭은 탈퇴 트랜잭션의 길이(수십 ms)다.
 * <b>완전히 닫으려면 {@link #requireAliveWithLock}을 써야 하고, 그것은 락을 이미 잡는 경로에서만 공짜다</b> —
 * 안 잡던 경로에 얹으면 잠금 순서가 새로 생긴다(페어링 교환은 실제로 그러면 안 된다:
 * 교환은 {@code pairing_codes} 행을 먼저 잠그는데 탈퇴는 회원 행을 먼저 잠근다 — 순서가 반대라
 * 사이클이 되고, 한쪽이 {@code REQUIRES_NEW}라 <b>PostgreSQL의 데드락 검출기가 그 사이클을 못 본다</b>).
 *
 * <p><b>{@code site}는 상수만 넘긴다.</b> 로그로 나가는 값이라 이메일·이름·채널 이름이 들어가면 안 된다
 * ({@code SecretLeakTest}가 본다).
 *
 * <h2>🔴 전수 명부 — 막는 자리 8자리 · 7파일 · 가드 밖 회원 행 락 6자리</h2>
 *
 * <p>🔴 <b>숫자는 바로 위 한 줄에만 있다 — 아래 표에는 세어 둔 수를 안 적는다.</b>
 * 두 군데 적으면 한쪽만 고치는 날이 오고, <b>이 세션에서만 그 일이 세 번</b> 났다
 * (「알려진 구멍」 22 · {@code sub} 명부 · 이 표). 검사가 읽는 것도 그 한 줄이라,
 * 숫자가 하나면 검사도 하나면 된다.
 *
 * <p><b>그 한 줄을 {@code WithdrawalWriteGuardRegistryTest}가 기계로 센다.</b> 자리가 늘거나 줄면
 * 빨간불이고, 그때 그 줄을 고치면서 아래 표에 행을 더하게 된다. 사람 눈으로 세는 명부가 <b>두 번 연속 틀렸기 때문에</b>
 * ({@code TokenSubjectRegistryTest} javadoc) 처음부터 기계에 맡긴다.
 *
 * <p><b>세는 기준은 「회원에게 무언가를 새로 만들어 주는 쓰기 경로」다.</b> 🔴 그런데 그 낱말만으로는
 * <b>이름 수정을 못 센다</b> — 거기는 만들어 주는 것이 아니라 <b>탈퇴가 지운 것을 되돌리는</b> 자리다.
 * 기준을 「탈퇴가 회수한 것을 되살리는 모든 쓰기」로 읽어야 전수가 된다.
 *
 * <h3>막는 자리</h3>
 * <table border="1">
 * <caption>탈퇴 확인이 있는 쓰기 경로</caption>
 * <tr><th>자리</th><th>무엇을 막나</th><th>경합 창</th></tr>
 * <tr><td>{@code StreamKeyService.ensureKey}</td><td>스트림키 신규 발급 · 페어링 코드 발급 · 교환</td>
 *     <td><b>남는다</b>(락 없음)</td></tr>
 * <tr><td>{@code PhotoAttacher.currentVersion}</td><td>창고에 올라가는 사진 <b>파일</b></td>
 *     <td><b>남는다</b>(락 없음)</td></tr>
 * <tr><td>{@code PhotoAttacher.attach}</td><td>{@code users}의 사진 칸 둘</td>
 *     <td><b>남는다</b>(락 없음)</td></tr>
 * <tr><td>{@code ChzzkLinkWriter.create}</td><td>치지직 연동 행 + secrets 둘</td><td>없다(락과 함께)</td></tr>
 * <tr><td>{@code YoutubeLinkWriter.create}</td><td>유튜브 연동 행 + secrets 둘</td><td>없다(락과 함께)</td></tr>
 * <tr><td>{@code UserService.updateName}</td><td>익명화된 이름의 <b>되돌리기</b></td>
 *     <td><b>남는다</b>(락 없음)</td></tr>
 * <tr><td>{@code InvitationService.invite}</td><td>탈퇴자가 <b>보내는</b> 초대</td>
 *     <td><b>남는다</b>(락 없음)</td></tr>
 * <tr><td>🔴 {@code TokenService.rotate}</td><td>일괄 폐기를 <b>넘어 살아남은 갱신 표</b>의 회전</td>
 *     <td>없다(락과 함께)</td></tr>
 * </table>
 *
 * <p>🔴 <b>{@code TokenService.rotate}가 이 표에서 가장 늦게 들어왔다</b>(사용자 결정 2026-08-31).
 * 로그인이 도는 중에 탈퇴가 커밋되면 {@code refresh_tokens} INSERT가 일괄 폐기를 넘어 태어나고,
 * 그 표는 <b>무기한</b> 새 접근 표를 찍어낸다 — auth 창구는 필터가 전부 막지만 <b>clip은 표를 독립으로
 * 검증하므로</b>(ADR-049) PRD가 적은 「남은 접근 표 최대 30분」이 <b>그 계정에서 거짓</b>이 된다.
 * 어차피 잡던 락과 함께 보므로 조회가 안 늘고 창도 안 남는다.
 * 던지는 것이 {@code AuthException}이라 {@code rotate}의 {@code noRollbackFor}가 그대로 덮는다 —
 * <b>재사용 감지의 무효화가 롤백되면 안 된다</b>는 그 성질은 안 건드렸다.
 *
 * <p><b>가드가 아닌 방법으로 닫은 자리</b> — 탈퇴자를 초대 <b>상대</b>로 고르는 경로는
 * {@code UserRepository.findAliveByEmail}이 조회 단계에서 닫는다. 답이 401이 아니라
 * 404여야 해서(부르는 쪽 계정은 멀쩡하다) 가드를 안 쓴다.
 *
 * <h3>가드 밖 회원 행 락 — 전부 이유가 있다</h3>
 * <table border="1">
 * <caption>{@code findByIdForUpdate}를 그대로 쓰는 자리</caption>
 * <tr><th>자리</th><th>왜 면제인가</th></tr>
 * <tr><td>{@code WithdrawalService.withdraw}</td><td>탈퇴 자신이다. 막으면 두 번째 호출의 멱등 판정이 사라진다</td></tr>
 * <tr><td>{@code ChzzkLinkWriter.revoke}</td><td>탈퇴가 익명화 <b>전에</b> 부른다. 막으면 탈퇴가 자기 가드에 걸린다</td></tr>
 * <tr><td>{@code YoutubeLinkWriter.revoke}</td><td>위와 같다(쌍둥이)</td></tr>
 * <tr><td>{@code ChzzkTokenRefresher}</td><td>탈퇴가 연동을 닫아 살아있는 링크가 없다 → 아무 일도 안 한다</td></tr>
 * <tr><td>{@code YoutubeTokenRefresher}</td><td>위와 같다(쌍둥이)</td></tr>
 * <tr><td>{@code StreamKeyService.rotate}</td><td>탈퇴가 살아있는 키를 폐기해 {@code findAlive}가 빈손 → 404</td></tr>
 * </table>
 *
 * <p>🔴 <b>로그아웃({@code TokenService.logout})에는 일부러 안 넣었다.</b> <b>끊는 동작</b>이라
 * 탈퇴자에게 해가 없고 오히려 살아남은 표를 스스로 죽이는 쪽이다. 그리고 그 자리가
 * <b>「입구 필터가 {@code permitAll} 경로를 안 막는다」를 재는 유일한 갈래</b>가 됐다 —
 * 재발급에 확인이 생기면서 거기서는 <b>필터를 지워도 초록</b>이 되기 때문이다
 * ({@code WithdrawnAccountBlockTest}의 로그아웃 쌍).
 *
 * @see UserRepository#findByIdForUpdate
 */
@Component
@RequiredArgsConstructor
public class ActiveUserGuard {

    private static final Logger log = LoggerFactory.getLogger(ActiveUserGuard.class);

    private final UserRepository users;

    /**
     * 표에서 읽어 확인한다. <b>락을 안 잡는다</b> — 위 「못 닫는 것」이 그대로 걸린다.
     *
     * <p>회원 행이 없으면 {@link DataInconsistencyException}이다(401 + ERROR 로그).
     * 🔴 <b>{@code ChzzkLinkWriter}·{@code YoutubeLinkWriter}는 그 자리에서 {@code IllegalStateException}
     * (=500)을 던지고 있었다.</b> 같은 사실에 답이 둘이던 것을 여기로 모으면서 401로 통일했다 —
     * 셋 중 다수(토큰 회전·사진·이름)가 이미 401이었고, 「토큰의 주인이 없다」는 우리 표가 어긋난 것이지
     * 서버가 터진 것이 아니다. <b>오늘 도달하는 경로는 없다</b>(서명 검증을 통과한 표만 여기 온다).
     */
    public User requireAlive(Long userId, String site) {
        return requireAlive(users.findById(userId)
                .orElseThrow(() -> new DataInconsistencyException(
                        AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", userId)), site);
    }

    /**
     * <b>회원 행 락과 함께</b> 읽어 확인한다. 락을 이미 잡던 경로만 이것을 쓴다 —
     * 그 경로에서는 표 조회가 하나도 안 늘고, 탈퇴와 <b>직렬화</b>되므로 위의 창이 아예 없다
     * ({@code FOR NO KEY UPDATE}끼리는 서로 충돌한다).
     */
    public User requireAliveWithLock(Long userId, String site) {
        return requireAlive(users.findByIdForUpdate(userId)
                .orElseThrow(() -> new DataInconsistencyException(
                        AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", userId)), site);
    }

    /**
     * <b>이미 손에 든 회원</b>을 확인한다 — 표를 다시 읽지 않는다.
     * 부르는 쪽이 자기 일 때문에 이미 읽은 경우에 쓴다(초대의 상대방 등).
     *
     * <p>🔴 <b>손에 든 것이 얼마나 오래된 값인지는 부르는 쪽 책임이다.</b> 다른 트랜잭션에서 읽어
     * 오래 들고 있던 객체를 넘기면 이 검사는 그 시점의 사실을 볼 뿐이다.
     */
    public User requireAlive(User user, String site) {
        if (user.isWithdrawn()) {
            // 입구 필터의 auth.withdrawn.blocked와 이름이 갈려야 한다 — 그래야 「필터가 막았다」와
            // 「필터를 지나 쓰기 직전에 막았다」가 로그에서 구분된다. 뒤엣것이 곧 경합이 실제로
            // 일어났다는 신호다(태스크 3·7의 로그 이름을 가른 것과 같은 이유).
            //
            // 🔴 이 WARN도 건수로 알람을 걸지 마라. 페어링 교환은 permitAll이라 코드를 쥔 쪽이
            // 반복해 두드리면 줄이 계속 난다(auth.failed·reuse_detected와 같은 함정).
            log.warn("auth.withdrawn.write_blocked userId={} site={}", user.getId(), site);
            throw new AuthException(AuthFailure.WITHDRAWN_ACCOUNT, "탈퇴한 회원이다");
        }
        return user;
    }
}
