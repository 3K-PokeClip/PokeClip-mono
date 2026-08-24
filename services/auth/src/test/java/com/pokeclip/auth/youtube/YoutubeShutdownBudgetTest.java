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
 * 종료 예산 — <b>두 정리 스레드 풀의 대기 시간 합</b>이 우리가 문서화하고 인프라에 요구한 종료 유예 안에 드는가.
 *
 * <p>🔴 스프링은 {@code @PreDestroy}를 <b>순차로</b> 부른다. 치지직과 유튜브가 각각 자기 풀을 기다리므로
 * <b>합이 곧 예산</b>이다. 예전에는 10 + 10 + 강제 2 = <b>최대 22초</b>로 문서화한 15초를 넘겼다(봇 4판 P2-3) —
 * 넘기면 오케스트레이터가 SIGKILL로 끊어 <b>어차피 유실되면서 배포만 느려진다.</b>
 *
 * <p>숫자를 세 곳에서 읽어 맞춘다: 유튜브 상수 · <b>치지직 상수</b>(리플렉션 — package-private이고
 * <b>고치지 않는다</b>. revoke 2회가 남아 그 10초가 지금도 필요하다) · <b>README의 「종료 유예 N초 이상」</b>.
 * 셋 중 하나만 바뀌어도 여기서 걸린다 — 문서와 코드가 갈라지는 것을 사람 눈으로 막을 수 없어서 검사로 둔다.
 */
class YoutubeShutdownBudgetTest {

    private static final Path SERVICES = Path.of("..");

    /** README 유튜브 절의 「종료 유예 N초 이상」. 그 숫자가 인프라(1번)에 요구한 값이다. */
    private static Duration documentedGrace() throws IOException {
        String readme = Files.readString(SERVICES.resolve("README.md"));
        int youtubeSection = readme.indexOf("### 유튜브 채널 연동 (POK-121)");
        assertThat(youtubeSection).as("README에서 유튜브 절을 못 찾았다").isGreaterThan(0);
        Matcher m = Pattern.compile("\\*\\*종료 유예 (\\d+)초 이상\\*\\*").matcher(readme.substring(youtubeSection));
        assertThat(m.find()).as("유튜브 절에서 「종료 유예 N초 이상」 문장을 못 찾았다").isTrue();
        return Duration.ofSeconds(Long.parseLong(m.group(1)));
    }

    /** 치지직 상수는 package-private이라 리플렉션으로 읽는다 — <b>읽기만 한다.</b> */
    private static Duration chzzkShutdownWait() throws Exception {
        Field f = Class.forName("com.pokeclip.auth.chzzk.ChzzkCleanupExecutor").getDeclaredField("SHUTDOWN_WAIT");
        f.setAccessible(true);
        return (Duration) f.get(null);
    }

    @Test
    void 두_정리_풀의_대기_합이_문서화한_종료_유예_안이다() throws Exception {
        Duration chzzk = chzzkShutdownWait();
        Duration youtube = YoutubeCleanupExecutor.SHUTDOWN_WAIT.plus(YoutubeCleanupExecutor.FORCED_STOP_WAIT);
        Duration total = chzzk.plus(youtube);

        assertThat(total)
                .as("치지직 %s + 유튜브 %s = %s — 문서화한 종료 유예 %s를 넘는다. "
                        + "치지직은 revoke 2회가 남아 줄일 수 없으니 유튜브 쪽을 줄여라",
                        chzzk, youtube, total, documentedGrace())
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
