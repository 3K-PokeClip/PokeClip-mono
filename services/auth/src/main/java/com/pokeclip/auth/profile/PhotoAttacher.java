package com.pokeclip.auth.profile;

import com.pokeclip.auth.user.ActiveUserGuard;
import com.pokeclip.auth.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 표 갱신만 담당한다. ProfilePhotoService에서 떼어낸 이유는 트랜잭션 경계다 —
 * Spring의 {@code @Transactional}은 프록시로 동작해서, 같은 클래스의 메서드를 직접 부르면
 * 프록시를 우회하고 어노테이션이 무시된다({@code UserCreator}가 떼어진 이유와 정확히 같다).
 *
 * <p><b>창고 호출은 여기 들어오지 않는다.</b> DB 커넥션을 쥔 채 외부 HTTP(최대 8초)를 기다리면
 * 동시 요청이 풀 크기(10)를 넘는 순간 사진과 무관한 로그인·토큰 회전까지 멈춘다
 * (「알려진 구멍」 9·10번 — 풀 10·동시 25에서 21/25 실패·30초 마비 실측).
 */
@Component
@RequiredArgsConstructor
class PhotoAttacher {

    private final ActiveUserGuard activeUserGuard;

    /**
     * 락을 잡지 않는다. 같은 회원이 사진을 동시에 두 번 올리면 마지막이 이기고 그만이다 —
     * 🔴 <b>다만 「어느 쪽이 이겨도 표와 파일이 맞는다」고 쓰면 거짓이다</b>(PR #133 codex P2).
     * 두 업로드가 같은 순간에 표를 읽으면 <b>같은 반대 자리</b>를 고르고, 그러면 나중 쓰기가
     * 앞 쓰기를 덮은 뒤 앞 트랜잭션이 커밋할 수 있다 — 표에는 앞의 버전, 자리에는 뒤의 바이트다.
     * <b>자리를 가르기 전에도 같았다</b>(고정 이름이면 마지막 쓰기가 항상 이겼다). 새로 생긴 성질이
     * 아니라서 그대로 두지만, 어긋나지 않는다고 말하지는 않는다. 어느 쪽이든 <b>그 회원 자신의
     * 사진 둘 중 하나</b>라 남의 것이 새지는 않는다
     * ({@code UserService.updateName}과 같은 판단).
     *
     * <p>🔴 <b>탈퇴한 회원이면 여기서 거절한다</b>(PR #148 codex C2, 재현함). 이 자리가 없으면
     * 탈퇴 정리가 <b>이미 지나간 뒤</b> 표의 사진 칸이 다시 채워지고 그 주소는 밖에서 200이다 —
     * 표를 아무리 뒤져도 「지웠다」로 보이는데 사진이 나간다.
     * <b>조회는 하나도 안 는다</b>: 어차피 읽던 그 회원을 가드가 대신 읽어 준다.
     *
     * <p>🔴 <b>여기서 던지면 부르는 쪽이 방금 창고에 쓴 파일을 도로 지운다</b>
     * ({@code ProfilePhotoService.attachOrDiscard}, PR #149 codex P1). <b>그 삭제를 이 안으로
     * 옮기지 마라</b> — 외부 HTTP라 커넥션을 쥔 채 기다리게 되고, 그것이 위 문단이 막고 있는 바로 그것이다.
     *
     * <p><b>락은 여전히 안 잡는다.</b> 그래서 「읽고 나서 쓴다」 사이의 창이 남는다 — 다만 그 사이에
     * 외부 호출이 없어 마이크로초 단위이고, 8초짜리 창고 호출은 이 검사보다 <b>앞</b>에 있다
     * ({@link #currentVersion}). 완전히 없애려면 {@code requireAliveWithLock}인데 그것은 이 경로에
     * 회원 행 락을 새로 얹는 것이라, 위 문단이 안 잡는다고 적은 그 대가를 그대로 치른다.
     */
    @Transactional
    User attach(long userId, long version) {
        User user = activeUserGuard.requireAlive(userId, "profile.photo.attach");
        user.attachPhoto(PhotoStorage.keyOf(userId, version), PhotoStorage.instantOf(version));
        return user;
    }

    /**
     * 지금 어느 자리를 쓰고 있나 — 없으면 {@code null}.
     *
     * <p>올리는 쪽이 <b>반대 자리</b>를 고르려면 이 값이 필요하다. 읽기 하나가 늘지만
     * 올리는 경로는 사람이 누르는 자리라 드물고, <b>꺼내는 경로는 이 조회를 안 탄다</b> —
     * 거기서 표를 읽으면 존재가 시간으로 새기 때문이다.
     *
     * <p>🔴 <b>탈퇴 확인이 여기에도 있는 이유는 지키는 것이 다르기 때문이다.</b> 이 조회는
     * {@code ProfilePhotoService.upload}가 <b>창고에 쓰기 전에</b> 하는 유일한 표 접근이다 —
     * 여기서 막으면 탈퇴자의 사진이 <b>창고에 아예 안 올라간다.</b> {@link #attach}에서만 막으면
     * 표는 지켜지지만 파일은 이미 창고에 올라간 뒤다(감사가 C2의 대가로 적어 둔 자리).
     * <b>여기는 파일, 저기는 표.</b> 한쪽만 두면 나머지가 열린다.
     *
     * <p><b>그 「이미 올라간 파일」은 이제 {@code ProfilePhotoService}가 거절할 때 도로 지운다</b>
     * (PR #149 codex P1). 그래도 이 관문을 지우면 안 된다 — 이쪽은 <b>애초에 안 쓰는 것</b>이고
     * 저쪽은 <b>쓴 뒤에 지우는 것</b>이라, 지우는 호출이 실패하면 저쪽만으로는 파일이 남는다.
     *
     * <p>회원이 없으면 이제 {@code null}이 아니라 던진다. 뒤의 {@code attach}가 같은 사실로 이미
     * 던지던 것이라 사용자가 받는 답은 같고, <b>창고에 파일을 쓰기 전</b>으로 앞당겨질 뿐이다.
     */
    @Transactional(readOnly = true)
    Long currentVersion(long userId) {
        User user = activeUserGuard.requireAlive(userId, "profile.photo.upload");
        return user.getProfilePhotoUpdatedAt() == null
                ? null : PhotoStorage.versionOf(user.getProfilePhotoUpdatedAt());
    }
}
