package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.DataInconsistencyException;
import com.pokeclip.auth.token.RefreshTokenRepository;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
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
