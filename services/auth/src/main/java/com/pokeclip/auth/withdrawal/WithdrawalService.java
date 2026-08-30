package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.DataInconsistencyException;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.streamkey.StreamKeyRepository;
import com.pokeclip.auth.streamkey.pairing.PairingCodeRepository;
import com.pokeclip.auth.token.RefreshTokenRepository;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * 탈퇴 — 한 트랜잭션. <b>회원 행 락 안에서</b> 돈다.
 *
 * <p>락을 잡는 이유는 토큰 회전({@code TokenService.rotate})·스트림키 재발급·채널 갱신이 <b>같은 락</b>을
 * 쓰기 때문이다. 안 잡으면 탈퇴가 도는 사이 재발급이 새 키를 만들어 <b>탈퇴한 계정에 살아있는 키가 남는다.</b>
 *
 * <p><b>외부 호출은 하나도 이 안에 없다</b> — 비밀값 삭제·사진 파일 삭제·치지직 토큰 무효화는 전부
 * 커밋 뒤로 뺀다. 커넥션을 쥔 채 기다리면 풀이 마른다(auth/CLAUDE.md 「알려진 구멍」 9·10).
 *
 * <p><b>이 메서드가 트랜잭션의 최상단이어야 한다.</b> 창구에 {@code @Transactional}을 붙이면 안 된다 —
 * 락과 익명화가 남의 트랜잭션 수명에 묶이고, 뒤 태스크가 더할 회수 단계의 롤백 범위가 여기서 갈린다.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final StreamKeyRepository streamKeyRepository;
    private final PairingCodeRepository pairingCodeRepository;
    private final ChzzkLinkWriter chzzkLinkWriter;
    private final YoutubeLinkWriter youtubeLinkWriter;

    @Transactional
    public void withdraw(Long userId) {
        User locked = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new DataInconsistencyException(
                        AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", userId));

        // 🔴 시각은 락 뒤에 잡는다 — ChzzkLinkWriter.create가 같은 이유로 그렇게 한다.
        //
        // 위 findByIdForUpdate는 기다린다. 같은 락을 쥔 채 치지직 HTTP(connect 2s + read 5s)를
        // 기다리는 경로가 있어서 최대 7초다(「알려진 구멍」 10). 이 줄이 락 앞에 있으면 그 대기만큼
        // 과거인 시각이 아래 회수 쿼리 넷과 익명화에 그대로 쓰이고, 대가가 둘이다:
        //   · consumeAliveOfUser가 expiresAt > :now로 고르므로 그 사이 만료된 코드가 「살아있다」로
        //     걸려 「방금 썼다」가 찍힌다 — 그 쿼리의 javadoc이 정반대를 약속하고 있다.
        //   · 락 대기 중 재연동이 커밋하면 새 행의 created_at이 now보다 나중인데 링크 해제가 그것을
        //     revoked_at = now로 닫아, 만들어지기 전에 닫힌 것으로 보이는 행이 남는다.
        //
        // 락을 얻은 뒤로 옮기면 둘 다 원리적으로 안 생긴다 — 이 시각 이후의 커밋은 락에 막혀 있다.
        Instant now = Instant.now();

        // 이미 탈퇴했으면 아무것도 안 한다. 전면 차단 필터가 앞에서 401로 막지만 겹치는 방어다 —
        // 이것이 없으면 두 번째 호출이 탈퇴 시각을 지금으로 밀어, 보관 기한을 세는 쪽이 잘못된 날짜를 본다.
        if (locked.isWithdrawn()) {
            return;
        }

        refreshTokenRepository.revokeAllOfUser(userId, now);

        // 🔴 송출 자격은 표 둘로 나뉘어 있고 둘 다 닫아야 회수가 끝난다.
        //
        // 키만 폐기하면 회수가 안 된다 — 페어링 교환은 로그인이 없어(코드 자체가 자격증명이다)
        // 전면 차단 필터가 못 막는데, 그 경로가 부르는 ensureKey는 "살아있는 키가 없다"를
        // 「아직 안 만들었다」로 읽고 새 키를 발급한다. 살아있는 코드 하나가 탈퇴자 명의의
        // 새 송출 자격이 되는 것이다.
        //
        // 반대로 코드만 닫아도 안 된다 — 이미 나간 streamid·passphrase로 계속 송출된다.
        //
        // 🔴 이 순서(폐기 → 회수)를 뒤집어도 안전하지만, 뒤집는 사람은 교환과의 경합을 다시 봐야 한다.
        // 지금 순서에서 안전한 이유: 교환은 markUsed로 코드 행을 먼저 잠그므로, 그 사이에 낀 탈퇴는
        // consumeAliveOfUser에서 막혀 교환보다 먼저 커밋하지 못한다. 그래서 교환은 항상 폐기 전의
        // 살아있는 키를 읽고(새로 만들지 않고) 그 키는 곧 이 트랜잭션이 폐기한다.
        streamKeyRepository.revokeAlive(userId, now);
        pairingCodeRepository.consumeAliveOfUser(userId, now);

        // 🔴 채널 연동은 기존 해제 경로를 그대로 부른다 — 여기에 UPDATE를 새로 쓰지 않는다.
        //
        // 표만 닫으면 절반이다. 그쪽 revoke가 커밋 뒤에 등록하는 것이 셋 더 있다:
        // 비밀값(토큰 원문) 삭제 · 정리 스레드 제출 · 치지직 토큰 무효화. 여기서 표를 직접 닫으면
        // 그 셋이 통째로 빠지고, 빠진 것이 조용하다 — 응답은 204고 표는 닫혀 있다.
        //
        // 🔴 새 폐기 사유도 만들지 않는다. USER_UNLINKED가 그대로 박혀야 두 연동의 정책 차이가
        // 자동으로 지켜진다 — 치지직은 커밋 뒤 토큰 무효화를 보내고 유튜브는 안 보낸다.
        // (구글 revoke는 「그 토큰」이 아니라 그 계정이 우리 앱에 준 동의 전부를 죽여, 같은 채널을
        // 방금 연동한 다른 회원의 grant까지 끊는다 — POK-121 실측·결정. YoutubeLinkWriter javadoc.)
        // 사유를 WITHDRAWN 같은 새 값으로 바꾸면 그쪽 상태 파생과 resolve의 UNLINKED 매핑이 갈린다.
        //
        // 🔴 둘 다 @Transactional(REQUIRED)이라 이 트랜잭션에 참여한다. 각자 users를 다시
        // findByIdForUpdate 하지만 같은 트랜잭션이라 락 재진입이고 무해하다.
        // 그 무해함은 「탈퇴가 락을 먼저 잡고 부른다」에 기대고 있다 — 이 호출을 락 밖으로
        // (예: 별도 트랜잭션·커밋 뒤로) 옮기는 사람은 같은 회원 행을 두 번 잠그는 것이 되므로
        // 경합 상대와 엇갈려 데드락 후보가 된다.
        chzzkLinkWriter.revoke(userId, now);
        youtubeLinkWriter.revoke(userId, now);

        // 🔴 비밀값(secrets)은 여기서 안 지운다 — 태스크 7의 커밋 뒤 전용 스레드로 간다.
        // afterCommit 안에서 REQUIRES_NEW인 secretStore.delete를 직접 부르면 원 커넥션을 쥔 채
        // 두 번째를 요구해 풀 데드락이 된다(auth/CLAUDE.md 「알려진 구멍」 9, 풀 10·동시 25 → 21건 실패).
        //
        // 🔴 그 정리를 붙이는 사람에게: passphrase_ref를 findByUserIdAndRevokedAtIsNull로 읽으려면
        // 반드시 위 revokeAlive 앞이어야 한다. 뒤에서 읽으면 빈손이 돌아오고 그것이 조용하다 —
        // 지울 것이 없다고 읽어 비밀값이 영영 남는다. (행 자체는 revoked_at만 채워진 채 살아 있으므로
        // "이 회원의 폐기된 키"를 따로 조회하는 길도 있다. 어느 쪽이든 한 곳에서만 읽는다.)

        // 🔴 락은 이 트랜잭션이 계속 쥐고 있다. 그래도 다시 읽는 이유는 회수 단계들이 쓰는
        // @Modifying(clearAutomatically = true) 쿼리가 영속성 컨텍스트를 비워, 맨 앞에서 락으로
        // 잡아 둔 회원 객체가 떨어져 나가기(detached) 때문이다. 그 객체를 고치면 커밋에 아무것도
        // 안 실리고 오류도 안 난다 — 응답은 204인데 이름·이메일·구글 식별자가 표에 그대로 남고
        // 탈퇴 시각도 안 찍힌다.
        //
        // 🔴 「익명화를 맨 앞으로 옮긴다」로 고치지 마라 — 우연히 듣는다. 그것이 듣는 이유는
        // revokeAlive·consumeAliveOfUser에 붙은 flushAutomatically = true가 비우기 전에 먼저
        // 밀어내 주기 때문인데, 위임·초대 쿼리에는 그것이 없다(기존 respond·cancel과 같은 모양).
        // 그 둘만 남는 순서로 누가 바꾸는 날 같은 결함이 조용히 돌아온다. 계획 검증 실측:
        //   익명화 먼저                                 → deleted_at 찍힘  (초록)
        //   익명화 먼저 + clearAutomatically만 있는 쿼리 → deleted_at=null (빨간불)
        //   마지막에 재조회                              → deleted_at 찍힘  (초록, 순서와 무관)
        //
        // 대가는 탈퇴 한 건당 SELECT 하나다. 탈퇴는 드문 요청이라 무시할 수 있다.
        userRepository.findById(userId)
                .orElseThrow(() -> new DataInconsistencyException(
                        AuthFailure.USER_NOT_FOUND, "락을 잡은 회원이 사라졌다", userId))
                .withdraw(now);

        // 🔴 이 줄의 뜻은 「표 변경이 끝났다」이지 「정리까지 끝났다」가 아니다. 정리 잡의 로그
        // (auth.withdrawal.cleanup.*)와 이름이 갈려야 「표는 바뀌었는데 사진 파일이 안 지워진
        // 회원」을 짝으로 찾을 수 있다.
        //
        // 커밋 뒤에 찍는 이유는 TokenService.logAfterCommit과 같다 — 롤백된 탈퇴가 이 줄을 남기면
        // 「지웠다」는 거짓 알리바이가 되고, 개인정보 삭제 문의에서 조사를 그 자리에서 멈추게 한다.
        // afterCommit은 같은 스레드 동기 실행이라 MDC 상관 ID도 살아 있다.
        logAfterCommit(() -> log.info("auth.withdrawal.completed userId={}", userId));
    }

    private void logAfterCommit(Runnable logging) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                logging.run();
            }
        });
    }
}
