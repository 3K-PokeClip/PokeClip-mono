package com.pokeclip.auth.youtube;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종료 예산 — <b>세 정리 스레드 풀의 대기 시간 합</b>이 우리가 문서화하고 인프라에 요구한 종료 유예 안에 드는가.
 *
 * <p>🔴 스프링은 {@code @PreDestroy}를 <b>순차로</b> 부른다. 치지직·유튜브·탈퇴가 각각 자기 풀을
 * 기다리므로 <b>합이 곧 예산</b>이다. 예전에는 10 + 10 + 강제 2 = <b>최대 22초</b>로 문서화한 15초를
 * 넘겼다(봇 4판 P2-3) — 넘기면 오케스트레이터가 SIGKILL로 끊어 <b>어차피 유실되면서 배포만 느려진다.</b>
 *
 * <p>🔴 <b>이 파일은 이름만 유튜브지 성격은 「전 서버 예산 검사」다.</b> 세 번째 풀(탈퇴, POK-171)이
 * 생겼을 때 이 검사는 <b>여전히 둘만 더해 14 ≤ 20을 재고 통과했다</b> — 이름과 목적이 둘 다 거짓이 되는
 * 자리라, 풀을 더하는 사람이 여기 한 줄을 같이 더해야 한다. 그것만은 기계가 대신 못 한다.
 *
 * <p>숫자를 네 곳에서 읽어 맞춘다: 유튜브 상수 · <b>치지직 상수</b>(리플렉션 — package-private이고
 * <b>고치지 않는다</b>. revoke 2회가 남아 그 10초가 지금도 필요하다) · <b>탈퇴 상수</b>(리플렉션, 같은 이유) ·
 * <b>README의 「종료 유예 N초 이상」</b>.
 * 넷 중 하나만 바뀌어도 여기서 걸린다 — 문서와 코드가 갈라지는 것을 사람 눈으로 막을 수 없어서 검사로 둔다.
 */
class YoutubeShutdownBudgetTest {

    private static final Path SERVICES = Path.of("..");

    /** README의 유튜브 절만 잘라 낸다 — 치지직 절에도 같은 문구가 있어 그것에 속지 않으려고. */
    private static String youtubeSection() throws IOException {
        String readme = Files.readString(SERVICES.resolve("README.md"));
        int start = readme.indexOf("### 유튜브 채널 연동 (POK-121)");
        assertThat(start).as("README에서 유튜브 절을 못 찾았다").isGreaterThan(0);
        int next = readme.indexOf("\n### ", start + 1);
        return next > 0 ? readme.substring(start, next) : readme.substring(start);
    }

    /** README 유튜브 절의 「종료 유예 N초 이상」. 그 숫자가 인프라(1번)에 요구한 값이다. */
    private static Duration documentedGrace() throws IOException {
        Matcher m = Pattern.compile("\\*\\*종료 유예 (\\d+)초 이상\\*\\*").matcher(youtubeSection());
        assertThat(m.find()).as("유튜브 절에서 「종료 유예 N초 이상」 문장을 못 찾았다").isTrue();
        return Duration.ofSeconds(Long.parseLong(m.group(1)));
    }

    /** 치지직 상수는 package-private이라 리플렉션으로 읽는다 — <b>읽기만 한다.</b> */
    private static Duration chzzkShutdownWait() throws Exception {
        return waitConstant("com.pokeclip.auth.chzzk.ChzzkCleanupExecutor", "SHUTDOWN_WAIT");
    }

    /**
     * 탈퇴 풀(POK-171)도 package-private이라 같은 방식으로 읽는다. <b>강제 대기까지 더해야</b> 실제 예산이다 —
     * 그쪽은 유튜브처럼 시한을 넘기면 인터럽트하고 1초를 더 기다린다.
     */
    private static Duration withdrawalWait() throws Exception {
        return waitConstant("com.pokeclip.auth.withdrawal.WithdrawalCleanupExecutor", "SHUTDOWN_WAIT")
                .plus(waitConstant("com.pokeclip.auth.withdrawal.WithdrawalCleanupExecutor", "FORCED_STOP_WAIT"));
    }

    private static Duration waitConstant(String className, String field) throws Exception {
        Field f = Class.forName(className).getDeclaredField(field);
        f.setAccessible(true);
        return (Duration) f.get(null);
    }

    /**
     * 🔴 <b>서술의 숫자도 상수와 맞아야 한다.</b> 아래 예산 검사는 「종료 유예 N초 이상」 문구만 보므로,
     * 바로 옆의 「최대 N초 기다린다(넘기면 인터럽트하고 M초 더)」가 낡아도 못 잡았다 —
     * 실제로 상수를 3초/1초로 줄이고 그 문장을 10초/2초로 남겨 봇 리뷰가 잡았다(6판 claude).
     * <b>우리가 만든 문서 검사가 자기 옆 문장을 못 본 자리다.</b>
     */
    @Test
    void README가_적은_정리_대기_숫자가_상수와_같다() throws IOException {
        Matcher m = Pattern.compile("최대 (\\d+)초 기다린다\\(넘기면 인터럽트하고 (\\d+)초 더\\)")
                .matcher(youtubeSection());
        assertThat(m.find()).as("유튜브 절에서 정리 대기 서술을 못 찾았다 — 문장이 사라졌거나 형식이 바뀌었다").isTrue();

        assertThat(Duration.ofSeconds(Long.parseLong(m.group(1))))
                .as("README의 정리 대기(%s초)가 SHUTDOWN_WAIT과 다르다", m.group(1))
                .isEqualTo(YoutubeCleanupExecutor.SHUTDOWN_WAIT);
        assertThat(Duration.ofSeconds(Long.parseLong(m.group(2))))
                .as("README의 강제 정지 대기(%s초)가 FORCED_STOP_WAIT과 다르다", m.group(2))
                .isEqualTo(YoutubeCleanupExecutor.FORCED_STOP_WAIT);
    }

    @Test
    void 세_정리_풀의_대기_합이_문서화한_종료_유예_안이다() throws Exception {
        Duration chzzk = chzzkShutdownWait();
        Duration youtube = YoutubeCleanupExecutor.SHUTDOWN_WAIT.plus(YoutubeCleanupExecutor.FORCED_STOP_WAIT);
        Duration withdrawal = withdrawalWait();
        Duration total = chzzk.plus(youtube).plus(withdrawal);

        assertThat(total)
                .as("치지직 %s + 유튜브 %s + 탈퇴 %s = %s — 문서화한 종료 유예 %s를 넘는다. "
                        + "치지직은 revoke 2회가 남아 줄일 수 없고 탈퇴는 사진 창고(최대 8초)를 기다리니, "
                        + "줄일 수 있는 쪽부터 보고 그래도 안 되면 README와 인프라 요구를 함께 올려라",
                        chzzk, youtube, withdrawal, total, documentedGrace())
                .isLessThanOrEqualTo(documentedGrace());
    }

    /**
     * 유튜브 쪽이 치지직보다 <b>짧아야</b> 한다는 것 자체를 굳힌다 — 「왜 여기만 3초지」 하고 되돌리는 것을 막는다.
     * 근거는 {@link YoutubeCleanupExecutor#SHUTDOWN_WAIT} 주석에 있다(외부 HTTP가 거의 사라졌다).
     */
    @Test
    void 유튜브_대기가_치지직보다_짧다() throws Exception {
        assertThat(YoutubeCleanupExecutor.SHUTDOWN_WAIT)
                .as("유튜브 정리 잡은 대부분 DB 삭제뿐이라 치지직만큼 기다릴 이유가 없다")
                .isLessThan(chzzkShutdownWait());
    }
}
