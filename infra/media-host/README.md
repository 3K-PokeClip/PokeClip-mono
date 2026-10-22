# media-host — prod Media 상자 기동 체계

prod Media 상자[^box]를 띄우고 지키는 파일 모음이다. 상자 위에서 MediaMTX ·
nginx · 세그먼트 인덱서 사이드카[^sidecar]를 docker compose 로 한 묶음으로 돌리고,
systemd 유닛 하나가 그 묶음 전체를 띄우고 다시 띄운다. 계획 POK-299 의 M2 가 이
디렉터리를 만들었고, M9 의 user_data(인스턴스가 처음 뜰 때 도는 설치 스크립트)가
상자에 설치한다.

*   **무엇을 지키나:** 비밀은 환경변수로 넘기지 않고 메모리 파일 시스템
    (tmpfs — 재부팅하면 사라지는 `/run`)에만 둔다. MediaMTX 의 내부 포트
    (8888 · 9996 · 9997)는 밖에서 닿지 않는다. 재기동은 compose 전체 단위다.
*   **누가 읽나:** 상자를 운영하는 사람(SSM 으로 명령을 보내는 사람)과 M9 ·
    M15 · I1-1 처럼 이 파일을 입력으로 쓰는 작업.
*   **팀 로컬 환경과 다르다:** 루트 `docker-compose.yml` · `infra/compose/` 는
    로컬 개발용이고, `infra/dev-media/` 는 dev 상시 상자다. 이 디렉터리는 prod
    상자 전용이다.

## 파일 · 서비스 지도

저장소 파일과 상자 위 자리, 그리고 그 파일을 부르는 쪽이다.

| 저장소 파일 | 상자 위 자리 | 부르는 쪽 |
|---|---|---|
| `systemd/pokeclip-media.service` | `/etc/systemd/system/pokeclip-media.service` | systemd(부팅 · `systemctl`) |
| `bin/start-media.sh` | `/opt/pokeclip-media/bin/start-media.sh` | 유닛 `ExecStart` |
| `bin/stop-media.sh` | `/opt/pokeclip-media/bin/stop-media.sh` | 유닛 `ExecStop` · `ExecStopPost`(둘 다 인자 없음 — compose down + 렌더 삭제) |
| `bin/loopback-health.sh` · `paths-count.sh` · `paths-list.sh` · `secret-count.sh` | `/opt/pokeclip-media/bin/` | 운영자(SSM) — secret-count 의 `ssm` 모드만 kty 로컬 |
| `bin/lib/ops.sh` · `bin/lib/mtx_paths.sh` | `/opt/pokeclip-media/bin/lib/` | 위 스크립트가 source |
| `compose.yml.tmpl` | `/etc/pokeclip-media/compose.yml`(렌더본) | `start-media.sh` · `stop-media.sh` |
| `nginx/nginx.conf.tmpl` | `/etc/pokeclip-media/nginx.conf`(렌더본) | compose 가 nginx 에 읽기 전용 마운트(원본이 없으면 만들지 않음: `create_host_path: false`, nginx 기동만 실패) |
| `mediamtx.yml.tmpl` | `/etc/pokeclip-media/mediamtx.yml.tmpl`(토큰이 남은 렌더본) | `start-media.sh` 가 기동마다 비밀을 채움 |
| (M9 이 씀) | `/etc/pokeclip-media/media.env` | 유닛 `EnvironmentFile` · 운영 확인 스크립트 |
| (M9 이 씀) | `/root/.docker/config.json`(ECR 자격 도우미 설정) | docker · 시작 스크립트 선행 점검 |
| (시작 스크립트가 씀) | `/run/pokeclip-media/mediamtx.yml` · `mtx_api_pass` | MediaMTX · 사이드카(읽기 전용 bind — 원본이 없으면 만들지 않음: `create_host_path: false`, 그 컨테이너 기동만 실패) |
| `ops/force-diff.sh` | 설치하지 않음 | kty 로컬(force 복구 대조) |
| `images.json` | 설치하지 않음 | 시험 · O7c 미러링 |
| `test/` | 설치하지 않음 | 로컬 · CI 시험 |

세 템플릿은 Terraform `templatefile()` 문법이다. M9 user_data 가 렌더하고, 시험
(`test/lib/render.sh`)도 같은 함수를 부른다.

### 서비스

compose 프로젝트 이름은 `pokeclip-media` 다(유닛의 `MEDIA_COMPOSE_PROJECT`).

| 서비스 | 하는 일 | 엔진 재시작 | 로그가 가는 곳 |
|---|---|---|---|
| `mediamtx` | SRT 수신 · LL-HLS · 재생 서버 · 녹화. 망 칸[^netns] 주인이고 공개 포트를 연다 | `"no"` | 유닛 저널[^journal] + json-file |
| `nginx` | 앞단 프록시(live · playback · 제어면 · 헬스 포트) | `"no"` | 유닛 저널 + json-file |
| `sidecar` | 세그먼트 인덱서 · 업로더 · 관리 API 관측 | `on-failure` | journald 태그 `pokeclip-media-sidecar` |
| `ingest-auth` | 송출 인증 응답기 — **자리**[^slot](M15 가 채움) | `on-failure` | 태그 `pokeclip-media-ingest-auth` |
| `janitor` | 녹화 정리 — **자리** | `on-failure` | 태그 `pokeclip-media-janitor` |

*   **재기동 단위:** 시작 스크립트는 `mediamtx` · `nginx` 의 출력에만
    붙는다[^attach]. 둘 중 하나가 끝나면 compose 전체가 내려가고, 유닛이 20초 뒤
    전체를 다시 띄운다. 나머지 서비스는 엔진(dockerd)이 그 컨테이너만 다시 띄운다 — DB
    장애로 사이드카가 죽어도 송출은 끊기지 않는다.
*   **`unless-stopped` · `always` 를 쓰지 않는 이유:** 데몬 재시작 · 부팅 때
    엔진이 컨테이너를 스스로 띄워, systemd 와 기동자가 둘이 된다.
*   **볼륨:** `recordings`(녹화) · `hooks`(훅 스풀) · `dvr`(LL-HLS 조각). 사이드카는
    `recordings` · `hooks` 를 읽기 전용으로 붙인다. 녹화 삭제는 janitor 몫이다.

### 설정 파일

*   **`control_upstream`**(nginx 템플릿 변수): 제어면 상류 `<호스트>:<포트>`.
    **비어 있으면 제어면 경로(`/api/ingest/` · `/api/streams/`)가 모두 404** 이고
    상류 호출은 0이다. 제어면 서버(I1-6)가 배포되기 전의 기본값이다. 값을 넣으면
    그 경로를 그대로 넘긴다. 제어면 포트의 그 밖 경로는 언제나 404 다.
*   **nginx 는 9997(관리 API)로 넘기는 설정을 두지 않는다.** 렌더 결과에 그 번호가
    한 번도 나오지 않는지 `render_test.sh` 가 본다.
*   **`detached_log_driver`**(compose 템플릿 변수): prod 는 `"journald"`.
    `"json-file"` 은 journald 가 없는 로컬 엔진에서 띄울 때만 쓴다. 그 밖의 값은
    모두 journald 로 떨어진다.
*   **MediaMTX 설정은 기본값 전체를 대체한다.** 템플릿에 없는 키는 MediaMTX
    기본값이고, `paths: all_others:` 가 빠지면 모든 송출이 거부된다.

## 시작 · 정지

재기동 · 정지는 이 유닛으로만 한다(아래 운영 규칙 ①). SSM 으로 보내는 명령은
이 꼴이다(실행 직전 kty 승인).

```shell
systemctl restart pokeclip-media
systemctl stop pokeclip-media
systemctl start pokeclip-media
```

**저널은 SSM 으로 원문을 내지 않는다(운영 규칙 ③).** SSM 명령으로는 판정 줄의
**건수만** 낸다. 아래는 이번 기동(유닛이 active 가 된 시각) 이후의 CDN 비밀 차단
줄 수를 내는 꼴이다.

```shell
fail() { echo "BLOCKED COUNT FAIL $1"; exit 2; }
since="$(systemctl show -P ActiveEnterTimestamp pokeclip-media)" || fail since
[ -n "${since}" ] || fail since
log="$(journalctl -q -u pokeclip-media --since "${since}" -o cat --no-pager)" \
  || fail journal
n="$(printf '%s\n' "${log}" | awk -v a='MEDIA START BLOCKED: CDN secret unavailable' \
  'index($0, a) { n++ } END { print n + 0 }')" || fail count
echo "BLOCKED COUNT ${n}"
```

*   **종료 코드:** 0 = 셌다(0건 포함 — 판정은 출력 숫자로 한다) · 2 = 조회
    실패(`BLOCKED COUNT FAIL since|journal|count`). SSM 은 스크립트의 마지막
    명령 종료 코드를 실행 상태로 보고하므로, 조회 실패는 `exit 2` 로 직접
    끝낸다.
*   `grep -c` 를 쓰지 않는다 — 맞는 줄이 0이면 종료 1 이라, 0건이 SSM 에서
    실패로 보인다. `awk` 는 0건에도 0 을 내고 종료 0 이다.
*   `since` 가 비면(유닛이 한 번도 active 가 된 적 없음) 실패다. 빈 값으로
    `--since` 를 부르면 저널 전체를 세게 된다.

저널 원문은 상자 셸에서 직접 보고, SSM 명령 출력 · 기록으로 남기지 않는다.
nginx 요청 줄 · 서비스 진단이 섞여 있고, 비밀이 잘못 찍혔다면 그것을 SSM 기록에
다시 옮기게 된다.

**시작 스크립트(`start-media.sh`)가 하는 일 — 순서대로:**

1.  선행 점검 세 항목을 매번 같은 순서로 모두 본다: Compose 판(`docker compose
    version --short` = `MEDIA_EXPECTED_COMPOSE_VERSION`) · ECR 자격 도우미
    (`docker-credential-ecr-login`) · `${DOCKER_CONFIG}/config.json` 의
    `credHelpers` 에 ECR 호스트. 하나라도 실패하면 `MEDIA PREREQ FAIL <항목>`
    으로 끝낸다.
1.  CDN 비밀(MediaMTX `hlsCDNSecret`)을 Secrets Manager 에서 읽는다. argv 에는
    ARN 만 오른다. 실패 · 빈 값 · 허용 글자(`[A-Za-z0-9._+=-]`) 밖이면
    `MEDIA START BLOCKED: CDN secret unavailable`.
1.  사이드카용 관리 API 비밀번호를 새로 만든다(`/dev/urandom` 32바이트 → 16진
    64자). 저장하지 않는다.
1.  템플릿의 고정 토큰 두 곳(`@@HLS_CDN_SECRET@@` · `@@MTX_API_PASS@@` — 각각
    정확히 한 번)을 채워 `/run/pokeclip-media/mediamtx.yml`(10002 소유 0400)과
    `mtx_api_pass`(10001 소유 0400)로 둔다. 두 파일 모두 임시 파일에 다 쓴
    뒤에야 이름을 바꾼다 — 실행 중인 스택이 보는 옛 파일을 제자리에서 고쳐 쓰지
    않는다. 중간에 실패하면 새 임시 파일은 남지 않는다. 이름을 바꾸기 전에
    실패하면 이미 있던 두 파일은 그대로이고, 둘째(`mtx_api_pass`) 이름 바꾸기가
    실패하면 방금 옮긴 `mediamtx.yml` 을 지워 짝이 어긋난 설정을 남기지 않는다.
1.  `docker compose --ansi never -p pokeclip-media -f
    /etc/pokeclip-media/compose.yml up --force-recreate --remove-orphans
    --abort-on-container-exit --attach mediamtx --attach nginx` 로
    바뀐다(exec). 컨테이너는 늘 새로 만든다(`--force-recreate`) — 남은
    컨테이너가 지운 옛 렌더(옛 CDN 비밀 · 옛 API 비밀번호)를 붙든 채 다시
    쓰이지 않게 한다. 렌더에서 빠진 서비스(비운 이미지 변수)의 옛 컨테이너도
    지운다(`--remove-orphans`) — 남으면 옛 비밀을 든 채 같은 망 칸에 붙어 있다.

인자는 받지 않는다. 점검을 건너뛰는 플래그 · 변수는 없다(아래 「시험
이음매」).

**정지:** `systemctl stop` 은 두 단계로 멈춘다. 먼저 주 프로세스(compose up)에
TERM 을 보내 스스로 끝나기를 기다리고(상한 30초), 그다음 `stop-media.sh` 가
`docker compose … down` 과 렌더 파일 삭제를 한다. `ExecStopPost` 가 끝난 방식과
상관없이 한 번 더 compose 를 내리고 렌더를 지운다(정상 정지에서는 빈 down).
`down` 은 볼륨을 지우지 않는다.

*   **정지 뒤 유닛 상태는 `inactive` 가 정상이다.** 기동 중(선행 점검 · 비밀
    조회 · 렌더)에 멈춰도 같다 — 시작 스크립트가 TERM 에 compose up 과 같은
    130 으로 끝나고, 유닛은 130 을 성공으로 본다.
*   `failed` 면 정지 단계 하나가 실패한 것이다. `down` 실패(판정 줄
    `MEDIA STOP down exit <n>`)나 정지 중 주 프로세스가 0 · 130 이 아닌 코드로
    끝난 경우다. 저널을 본다.
*   `down` 이 실패하면(compose 파일이 깨진 경우 포함) 정지 스크립트가 프로젝트
    라벨로 남은 컨테이너를 찾아 지운다(`MEDIA STOP fallback removed <n>`).
    그 docker 호출은 하나에 10초 상한이고, 넘기면 `MEDIA STOP fallback FAIL` 이다.
    `down` · 폴백이 끝나지 않아 systemd 가 정지 명령에 TERM 을 보내도 렌더는
    지운다(그때 남은 컨테이너는 ExecStopPost 의 정지 스크립트가 다시 거둔다).
*   정지 뒤에는 프로젝트 컨테이너가 0 인지 건수로 확인한다. `containers 0`
    줄이 나와야 회수가 끝난 것이다. 그 줄 없이 오류만 나오면 조회가 실패한
    것이다(데몬 정지 · 권한) — 0 으로 읽지 않는다.

    ```shell
    ids=$(docker ps -a -q --filter label=com.docker.compose.project=pokeclip-media) \
      && printf 'containers %s\n' "$(printf '%s' "$ids" | grep -c .)"
    ```

**크래시:** 주 프로세스가 실패로 끝나면(compose 플러그인 · 유닛 cgroup
SIGKILL 등) systemd 252 는 `ExecStop` 을 건너뛰고 `ExecStopPost` 로 간다. 그래서
`ExecStopPost` 가 컨테이너를 내린다 — 렌더만 지우면 옛 비밀을 붙든 컨테이너가
남는다.

**재시도 한도가 없다.** 선행 점검이 실패하거나 CDN 비밀 조회(Secrets Manager
· NAT)가 몇 분 끊겨도 유닛은 `failed` 로 굳지 않고 20초마다 다시 시도한다.
그동안 저널에 같은 판정 줄이 되풀이된다. RDS 장애는 유닛이 아니라 사이드카만
다시 띄운다(위 「재기동 단위」).

## 판정 줄

스크립트는 고정 문자열 줄만 낸다. SSM 기록 · 저널에서 이 줄로 판정한다.

| 스크립트 | 줄 | 뜻 |
|---|---|---|
| `start-media.sh` | `MEDIA PREREQ OK\|FAIL compose-version` | Compose 판 일치 여부 |
| | `MEDIA PREREQ OK\|FAIL ecr-credential-helper` | 자격 도우미가 PATH 에 있는지 |
| | `MEDIA PREREQ OK\|FAIL docker-config <경로>` | 그 `config.json` 의 `credHelpers` 에 ECR 호스트 |
| | `MEDIA START BLOCKED: CDN secret unavailable` | CDN 비밀을 못 읽음 · 빈 값 · 허용 글자 밖 |
| | `MEDIA START BLOCKED: MTX API credential unavailable` | API 비밀번호 생성 실패 |
| | `MEDIA START FAIL render <단계>` | 렌더 실패 — `runtime-dir` · `template` · `template-token` · `owner` · `tmpfile` · `write` · `chown` · `target` · `rename` · `api-pass-tmpfile` · `api-pass-write` · `api-pass-chown` · `api-pass-target` · `api-pass-rename` |
| | `MEDIA START FAIL usage` | 인자를 줬음 |
| `stop-media.sh` | `MEDIA STOP FAIL usage\|runtime-dir` | 인자 오류 · `RUNTIME_DIR` 이 절대 경로가 아님 |
| | `MEDIA STOP down exit <n>` | `compose down` 이 0 아닌 코드 |
| | `MEDIA STOP fallback removed <n>` · `MEDIA STOP fallback FAIL` | down 실패 뒤 프로젝트 라벨로 지운 컨테이너 수 · 그 조회 · 삭제 실패 |
| | `MEDIA STOP interrupted` | down 중 TERM · INT — down 을 멈추고 렌더를 지운 뒤 143 · 130 |
| | `MEDIA STOP CLEANUP FAIL <디렉터리>` | 렌더 파일이 지운 뒤에도 남음 |
| `paths-count.sh` | `PATHS <n>` | 경로 항목 수(송출 중 아닌 것 포함) |
| | `PATHS FAIL incomplete` | 응답이 여러 페이지 — 원자 스냅샷 아님 |
| | `PATHS FAIL <단계>` | `usage` · `config` · `sidecar` · `credentials` · `request` · `timeout` · `auth`(401) · `http` · `response` |
| `paths-list.sh` | (표준 출력) 경로 이름 한 줄에 하나 | 송출 중인 ingestStreamId 목록 |
| | `PATHS LIST <n>`(표준 오류) | 그 목록의 개수 — force 대조에 함께 넘긴다 |
| | `PATHS LIST INCOMPLETE`(표준 오류) | 여러 페이지 — force 를 하지 않는다 |
| | `PATHS FAIL <단계>`(표준 오류) | paths-count 와 같은 단계 이름 |
| `loopback-health.sh` | `LOOPBACK OK` | 송출 → 프록시 경유 재생목록 200 |
| | `LOOPBACK FAIL <단계>` | `usage` · `config` · `cdn-secret` · `test-key` · `mediamtx` · `scratch` · `pull` · `sender` · `playlist` · `sender-cleanup` |
| `secret-count.sh` | `SECRET COUNT box\|ssm <n>` | 비밀 값이 든 줄 수 |
| | `SECRET COUNT FAIL <단계>` | `usage` · `config` · `cdn-secret` · `test-key` · `api-pass`(관리 API 비밀번호 파일 없음 · 못 읽음 · 꼴 밖 — 정지 뒤에는 정상) · `journal` · `mediamtx` · `nginx` · `docker-logs` · `ssm-command` · `ssm-list` · `ssm-status` · `ssm-output` · `ssm-truncated` · `grep` |
| `ops/force-diff.sh` | `FORCE REMAINING <n>` + 남은 ID | n 이 0 이면 복구 완료 |
| | `FORCE FAIL input A\|B\|ended` · `FORCE FAIL usage` | 입력 거부 · 인자 오류 |
| | `FORCE FAIL count A\|B` · `FORCE FAIL empty A` | 잘린 목록(ID 줄 수가 개수 인자와 다름 · 마지막 줄에 끝 줄바꿈 없음) · A 에 ID 가 없음 |

`FAIL` 줄은 「0 건」 이 아니다. 조회가 실패하면 실패로 끝낸다.

## 운영 확인 스크립트

모두 상자에서 SSM 으로 돌린다(실행 직전 kty 승인). 스크립트는
`/etc/pokeclip-media/media.env` 를 스스로 읽지 않으므로, 그 값이 필요한 것은
먼저 셸에 읽어 들인다. 아래는 그 꼴이고, 정본 명령문은 M9 의 런북이 정한다.

```shell
set -a; . /etc/pokeclip-media/media.env; set +a
/opt/pokeclip-media/bin/paths-count.sh
```

`media.env` 에는 비밀 값이 없다 — 판 · ARN · 호스트 · 포트 · 이미지 참조만 있다.

| 스크립트 | 쓰는 자리 | 읽는 `media.env` 키 |
|---|---|---|
| `paths-count.sh` | 교체 4(ingest 전환 직전 게이트) · 운영 MediaMTX 설정의 로컬 compose 실측 | 없음 |
| `paths-list.sh` | force 1 · 6, 리스 만료 중단 통지 | 없음 |
| `loopback-health.sh [--passphrase] <ingestStreamId>` | 교체 3 · O7f 9 | `CDN_SECRET_ARN` · `LOOPBACK_SENDER_IMAGE` · `MEDIA_LIVE_PORT` · (`--passphrase` 면) `TEST_STREAM_KEY_SECRET_ARN` |
| `secret-count.sh [--passphrase] box` | O7f 9 · O8a 3 | `CDN_SECRET_ARN` · (`--passphrase` 면) `TEST_STREAM_KEY_SECRET_ARN` |
| `secret-count.sh [--passphrase] ssm <command-id>` | kty 로컬 — 그 SSM 명령 기록 대조 | 같은 이름을 kty 셸에 넣는다 |

시간 상한은 명령마다 다르다. `MEDIA_OPS_TIMEOUT` 이 모든 외부 명령에 걸리는 것은
아니다.

| 상한 | 값 | 걸리는 곳 |
|---|---|---|
| `MEDIA_OPS_TIMEOUT` | 초, 1–600, 기본 30 | paths 두 스크립트의 컨테이너 찾기 · `docker exec` · secret-count 의 `journalctl` · `docker logs` · `aws` · 루프백의 비밀 읽기 · 컨테이너 찾기 · 송출기 정리(`docker rm` · `ps`) |
| 고정 300초 | `PULL_TIMEOUT` | 루프백 송출기 이미지 받기(`docker pull`) |
| 고정 5초 · 바깥 10초 | `CURL_MAX_TIME` · `CURL_BOUND` | 루프백 재생목록 요청 한 번(curl `max-time` · 그 바깥 상한) |
| `LOOPBACK_WAIT_SECONDS` | 초, 1–300, 기본 30 | 루프백이 200 을 기다리는 대기 루프의 상한. 진행 중인 요청 한 번(최대 10초)과 재시도 간격(1초)만큼 넘을 수 있다. 송출기는 이보다 15초 길게 송출한다 |
| 고정 5초 | `nc -w 5` | paths 조회의 컨테이너 안 연결 유휴 상한 |

`MEDIA_OPS_TIMEOUT` · `LOOPBACK_WAIT_SECONDS` 가 틀린 값이면 기본값으로 바꾸지
않고 `… FAIL config` 로 끝난다.

**paths 두 스크립트의 조회 방식:** 9997 은 MediaMTX 망 칸의 127.0.0.1 에만 열려
있고 MediaMTX 이미지에는 셸이 없다. 그래서 사이드카 컨테이너 안에서 고정 `sh
-c` 스크립트(`bin/lib/mtx_paths.sh` 의 `MTX_PATHS_EXEC_SCRIPT`)를 돌린다. 자격은
사이드카 자신의 `MTX_API_USER` · `MTX_API_PASS_FILE` 에서 읽고, 비밀번호는 셸 내장
`read` · `printf` → `base64` · BusyBox `nc` 의 표준 입력으로만 지나간다. 자격이
비면 요청하지 않고 `PATHS FAIL credentials` 다.

**force 대조(`ops/force-diff.sh`, kty 로컬):**

1.  force 1 에서 A 상자의 `paths-list.sh` 표준 출력을
    `force-<시각>-A.txt` 로 저장하고, 표준 오류의 `PATHS LIST <n>` 의 n 을
    함께 적어 둔다. **종료 0 일 때만 저장한다** — 실패하면 표준 출력이 비어
    있어, 저장하면 「송출 중 0」 처럼 읽힌다.
1.  **SSM 출력 한도(표준 출력 24,000자)에 닿은 출력은 저장하지 않는다** — 뒤가
    잘린 목록이다. 놓쳐도 다음 단계가 거부한다 — 줄 수가 개수와 다르거나,
    마지막 ID 가 가운데에서 잘려 끝 줄바꿈이 없으면 대조하지 않는다. 저장할 때
    출력을 손으로 고치지 않는다(끝 줄바꿈을 더하면 이 검사가 무력해진다).
1.  force 4 뒤 B 상자에서 같은 스크립트로 `force-<시각>-B.txt` 와 개수를
    받는다(같은 규칙).
1.  종료를 확인한 방송은 `force-<시각>-ended.txt` 에 탭 구분 3열(`ingestStreamId`
    · 확인 시각 · 확인 경로)로 덧붙인다.
1.  `force-diff.sh A B ended <A 개수> <B 개수>` → `FORCE REMAINING <n>`. 남은
    ID 가 연락 대상이고, 0 이면 복구 완료다. A · B 파일의 ID 줄 수가 개수와
    다르거나 마지막 줄에 끝 줄바꿈이 없으면(잘린 목록), A 가 비면 대조하지
    않는다(`FORCE FAIL count|empty`).

**루프백 헬스(`loopback-health.sh`):** 상자 안에서 시험 신호를 MediaMTX
(`srt://127.0.0.1:8890`)로 보내고, `127.0.0.1:<live 포트>/<ID>/index.m3u8` 이
200 인지 본다. CDN 비밀(Bearer)은 `curl --config -` 의 표준 입력으로만 간다.

*   **기본은 passphrase 를 싣지 않는다.** O7f 9 시점의 상자는 송출 인증(M15
    응답기) 전이라 익명 publish 이고, 그때 passphrase 를 실으면 MediaMTX 가
    끊는다. O7f 9 런북은 `--passphrase` 없이 부른다.
*   `--passphrase` 는 M15 반영 뒤에만 쓴다. 그 뒤에는 거꾸로 기본 갈래가 거부되는
    것이 정상이다.
*   `--passphrase` 일 때 passphrase 는 송출기 argv 에 실린다. 송출이 도는 수십
    초 동안 상자 프로세스 목록 · `docker inspect` 에 보인다(설계에서 받아들인 위험 —
    상자 셸에 들어온 사람은 이미 인스턴스 역할로 비밀을 읽을 수 있다).
*   송출기는 `linuxserver/ffmpeg` 다이제스트 고정 미러를 `docker run --rm
    --network container:<mediamtx>` 로 띄운다. 모든 cap 을 버리고, 읽기 전용
    루트 · 비root(65534)로 돈다.

**비밀 건수(`secret-count.sh`):** 대조 값은 CDN 비밀이고, 시험 키 passphrase
는 `--passphrase` 를 줄 때만 센다(기본은 세지 않는다). 값은 Secrets Manager 에서
직접 읽고, `grep -F -f -` 의 표준 입력으로만 넘긴다. `box` 모드 입력은 유닛
저널 · 태그 저널 셋 · `mediamtx` · `nginx` 의 `docker logs`(스택이 도는 동안
재기동으로 멈춘 컨테이너 포함)다. `ssm` 모드는 SSM API 가 주는 한도(표준 출력
24,000자 · 표준 오류 8,000자)에 닿으면 `ssm-truncated` 로 실패한다.

`box` 모드는 이번 기동의 관리 API 비밀번호(`/run/pokeclip-media/mtx_api_pass` 를
root 로 읽음)와 그 Basic 값(`pokeclip-sidecar:<비밀번호>` 의 base64)도 대조한다.

*   **`box` 는 스택이 떠 있을 때만 쓴다.** 정상 정지 뒤에는 정지 스크립트가
    비밀번호 파일을 지우므로 `SECRET COUNT FAIL api-pass` 로 끝난다(로그를
    모으기 전에 비밀번호 파일부터 읽는다). compose down 이 컨테이너도 지워
    어차피 셀 로그가 없다.
*   **비밀번호 파일은 시작 스크립트가 쓰는 꼴 하나만 받는다** — 파일 전체가
    16진 64자 한 줄(끝 줄바꿈 하나, 65바이트)이다. 파일이 없거나 못 읽거나, 그
    꼴이 아니면(줄바꿈 없음 · 둘째 줄 · 뒤에 남는 바이트 포함) 0 건이 아니라
    `SECRET COUNT FAIL api-pass`(종료 1)다. 정지 뒤의 이 실패는 정상이다.
    스택이 떠 있는데 이 실패가 나면 비밀번호 파일 이상이니 조사한다.
*   옛 기동의 비밀번호와 `ssm` 모드의 비밀번호 대조는 아래 「알려진 한계」.

## 운영 규칙

1.  **재기동 · 정지는 `systemctl restart|stop pokeclip-media`(compose 전체)만
    쓴다.** `docker compose restart <서비스>` 단독, 컨테이너 단독 `docker stop` ·
    `docker restart` 는 금지한다.
    *   서비스 단독 재시작은 시작 스크립트(비밀 조회 · 렌더 · 선행 점검)를
        건너뛴다. 망 칸을 함께 쓰는 서비스는 주인(mediamtx)과 함께 뜨고 져야
        한다.
    *   M2 실측(계약 시험 사례 14): `docker compose restart mediamtx` 단독 뒤 nginx
        가 다시 뜨고, 시작 스크립트의 `compose up` 은 「Aborting on container
        exit」 뒤 3분 넘게 끝나지 않았다. mediamtx · 사이드카는 멈추고 nginx 만
        남았다. prod 라면 유닛은 active 인데 서비스가 없는 상태가 이어진다.
    *   사이드카만 `docker stop` 하면 `on-failure` 정책은 다시 띄우지 않는다.
        다음 유닛 재기동까지 인덱싱이 멈춘다.
1.  **SSM 에서 `docker inspect` · `docker exec … env` ·
    `/run/pokeclip-media` 내용 출력을 금지한다.** `docker exec` 는
    `paths-count.sh` · `paths-list.sh` 가 사이드카 컨테이너에 쓰는 고정 `sh -c`
    한 꼴(`MTX_PATHS_EXEC_SCRIPT`)만 쓴다. 대조는 kty 로컬의 `force-diff.sh` 로
    한다.
1.  **SSM 명령 출력에는 판정 줄(`LOOPBACK …` · `PATHS …` · `SECRET COUNT …`)과
    force 때의 `paths-list.sh` 방송 목록(공개 식별자)만 남긴다.** 결과를 파일로
    저장하는 것은 종료 0 일 때만이다.

## 로그

*   **유닛 저널(`-u pokeclip-media`)에는 사이드카 줄이 없다.** 사이드카 ·
    응답기 · janitor 의 줄은 태그 저널(`-t pokeclip-media-sidecar` · `-t
    pokeclip-media-ingest-auth` · `-t pokeclip-media-janitor`)에 있다.
    컨테이너가 다시 만들어져도 앞 컨테이너의 줄이 남는 곳이 여기다.
*   어느 저널이든 SSM 으로는 건수만 내고, 원문은 상자 셸에서 직접 본다(위
    「시작 · 정지」).
*   유닛 저널에는 시작 · 정지 스크립트의 판정 줄과 `mediamtx` · `nginx` 의 출력이
    남는다. 이 둘의 엔진 쪽 로그는 json-file 10 MB × 3 개로 돈다.
*   로깅 옵션에 `env` · `labels` 를 넣지 않는다 — 컨테이너 환경이 저널 필드로
    남는다.
*   nginx 접근 로그에는 `Authorization` 과 쿼리 문자열을 싣지 않는다.

### 기동 확인 신호

O7e 기동 확인에서 사이드카 관리 API 자격이 붙었는지는 사이드카 태그 저널로
본다(상자 안에서 9997 을 따로 두드리지 않는다 — 규칙 ②).

사이드카 로그는 **JSON 한 줄**(Go `slog` JSON 핸들러)이다. 그래서 `auth=basic`
같은 문자열로 찾으면 정상 · 실패 모두 0건이 되어, 실패를 정상으로 읽는다. 판정은
메시지(`"msg"`)와 필드를 함께 본다.

| 신호 | JSON 조건(한 줄 안에 모두) | 판정 |
|---|---|---|
| 정상 기동 | `"msg":"mtxstate_started"` · `"auth":"basic"` · `"first_observation":true` | 이번 기동 이후 1건 이상 |
| 자격 거부 | `"msg":"mtxstate_poll_failed"` · `"reason":"auth_rejected"`(ERROR, 폴마다) | 이번 기동 이후 0건 |

이번 기동 이후의 두 건수로 판정 줄 하나와 종료 코드를 내는 꼴이다.

```shell
fail() { echo "SIDECAR AUTH FAIL $1"; exit 2; }
since="$(systemctl show -P ActiveEnterTimestamp pokeclip-media)" || fail since
[ -n "${since}" ] || fail since
log="$(journalctl -q -t pokeclip-media-sidecar --since "${since}" -o cat \
  --no-pager)" || fail journal
started="$(printf '%s\n' "${log}" | awk -v a='"msg":"mtxstate_started"' \
  -v b='"auth":"basic"' -v c='"first_observation":true' \
  'index($0, a) && index($0, b) && index($0, c) { n++ } END { print n + 0 }')" \
  || fail count
rejected="$(printf '%s\n' "${log}" | awk -v a='"msg":"mtxstate_poll_failed"' \
  -v b='"reason":"auth_rejected"' \
  'index($0, a) && index($0, b) { n++ } END { print n + 0 }')" || fail count
if [ "${started}" -ge 1 ] && [ "${rejected}" -eq 0 ]; then
  echo "SIDECAR AUTH OK started=${started} rejected=${rejected}"
  exit 0
fi
echo "SIDECAR AUTH NOT OK started=${started} rejected=${rejected}"
exit 1
```

| 갈래 | 출력 | 종료 코드(SSM 상태) |
|---|---|---|
| 정상 — started ≥ 1 · rejected = 0 | `SIDECAR AUTH OK started=<n> rejected=0` | 0(Success) |
| 기동 줄 없음 · 자격 거부 | `SIDECAR AUTH NOT OK started=<n> rejected=<n>` | 1(Failed) |
| 조회 실패(`systemctl` · `journalctl` · `awk`) · 기동 기록 없음 | `SIDECAR AUTH FAIL since\|journal\|count` | 2(Failed) |

*   1 과 2 는 SSM 에서 둘 다 Failed 다. 판정 줄로 「거부」 와 「조회 실패」 를
    가른다. 저널 원문은 내지 않는다.

*   유닛이 active 가 된 시각은 compose 전체 재시작 때만 바뀐다. 사이드카만 엔진이
    다시 띄운 경우는 같은 창 안에 든다.
*   **이 신호는 인증이 강제된다는 증거가 아니다.** 무인증 `api` 항목이 남아
    있으면 틀린 자격도 붙어 같은 신호가 난다(`media/README.md` 「관측 자격
    파일 규칙」). 이 상자는 그 항목을 지운 설정(`mediamtx.yml.tmpl`)이라 이
    신호를 자격 확인으로 쓴다.
*   무자격 · 「같은 이름 + 틀린 비밀번호」 가 모두 401 인지(음성 확인)는 M2
    로컬 실측이 맡았다.

## 보안 경계

*   **SG 가 유일한 경계다.** 공개 포트(live · playback · 제어면 · 헬스 TCP,
    8890/udp)는 `0.0.0.0` 에 게시한다(ALB · NLB 대상). docker 의 포트 게시는
    DNAT 규칙으로 호스트 방화벽을 우회하므로, 상자 방화벽은 경계가 아니다.
    인그레스 규칙은 M9 의 Media SG 가 정하고 단언한다.
*   **관리 API(9997):** 망 칸의 127.0.0.1 에만 묶인다. 무인증 `api` 항목은
    없다(기본 localhost 관리자 항목도 지웠다). 같은 망 칸의 컨테이너는 모두
    127.0.0.1 로 붙어 IP 로는 가를 수 없기 때문이다. 쓸 수 있는 사용자는
    비밀번호가 있는 사이드카 사용자(`pokeclip-sidecar`) 하나다. **운영자용 API
    사용자는 두지 않는다** — 운영 조회는 paths 두 스크립트가 사이드카 자격으로
    한다.
*   **재생 서버(9996) 무인증 항목:** `ips` 가 루프백이어도 같은 망 칸의
    컨테이너는 모두 127.0.0.1 로 붙어, 칸 안 누구나(사이드카 · 응답기 ·
    janitor) `/list` · `/get` 을 부를 수 있다(받아들인 위험). 실제 관문은
    nginx 의 경로 번호 대조와 CloudFront 서명 쿠키다.
*   **비밀 처리:**
    *   CDN 비밀은 Secrets Manager → 시작 스크립트 셸 변수 → tmpfs 의
        `mediamtx.yml`(10002 소유 0400)로만 간다.
    *   API 비밀번호는 기동마다 새로 만들고 저장하지 않는다. 같은 실행에서
        MediaMTX 설정과 사이드카 파일에 함께 쓰이므로 둘이 어긋나지 않는다.
    *   정지 때 두 파일을 지운다.
    *   core 파일을 남기지 않는다. 시작 스크립트와 compose CLI 에는 유닛의
        `LimitCORE=0` · 스크립트의 `ulimit -c 0` 이, 컨테이너에는 compose 의
        `ulimits: core: 0`(모든 서비스)이 건다 — 컨테이너는 dockerd 가 만들어
        유닛의 제한을 물려받지 않는다.
    *   옛 스택이 살아 있는 채 시작 스크립트가 다시 돌아도, `--force-recreate`
        로 컨테이너를 모두 새로 만들어 MediaMTX 와 사이드카가 같은 새 렌더를
        읽는다. 렌더에서 빠진 서비스의 옛 컨테이너는 `--remove-orphans` 가
        지운다.
*   **받아들인 위험 — 사이드카 자격 = 관리 API 전권:** MediaMTX 의 `api` 권한은
    조회 전용이 아니다. 이 자격으로 할 수 있는 것은 다음과 같다.
    *   설정 조회 — 응답에 `hlsCDNSecret` 이 평문으로 들어 있다(포크의 가림
        처리가 이 값을 가리지 않는다 — M2 실측).
    *   설정 변경 — 훅 명령을 바꿔 명령 실행까지 갈 수 있다.
    *   kick · 녹화 삭제.

    루프백 접근과 유효한 비밀번호가 둘 다 있어야 하고, 무자격 접근이나 정상
    로그로 새는 길은 확인되지 않았다. 같은 망 칸 누구나 무인증 전권이던 이전
    구성보다 좁다.
*   **침해가 의심되면** 서비스를 멈추고(`systemctl stop pokeclip-media` —
    SSM · 실행 직전 승인) kty 에게 알린다. 상세 절차(격리 · 증거 보존 · 역할
    세션 취소 · 비밀 교체 · 상자 교체)는 별도 보안 런북이 맡는다(아래
    「후속」).

## 되돌리기

상자 설정은 user_data 가 쓴 파일이라, 설정을 바꾸는 길은 M9 런북의 「설정
반영 교체」(`config_version` 을 올린 새 상자 B 로 교체)다. 되돌릴 때도 같은
길이다.

*   **이전 설정:** 이전 커밋의 템플릿으로 설정 반영 교체를 한다.
*   **관측만 끄기:** 사이드카 `MTX_API_URL` 을 비우면 관측이 꺼지고 비밀번호
    파일은 읽지 않는다. 사이드카 관리 API 자격 PR(아래 「출처」)은 이것을
    「즉시 롤백」 이라 부르지만, 이 상자에서 값을 바꾸는 길은 위의 설정 반영
    교체다 — 바로 되돌아가지 않는다.
*   **어느 쪽이든 반영은 compose 전체 재시작이다.** MediaMTX 단독 재시작은 하지
    않는다(규칙 ①).
*   관리 API 로 하는 수동 조작(kick · 설정 PATCH)은 되돌리기 수단이 아니다 —
    운영자 API 사용자가 없다.

## 이미지

compose 는 이미지를 모두 `<ECR 저장소 URL>@<다이제스트>` 로 가리킨다. 태그가
아니라 다이제스트[^digest]라서, 같은 참조는 늘 같은 내용이다.

| compose 변수 | prod ECR 저장소 | 출처 |
|---|---|---|
| `nginx_image` | `pokeclip-prod/media-nginx` | nginx 공식 이미지 미러[^mirror] — 빌드하지 않음. 핀[^pin] = `images.json` 의 `nginx` |
| `mediamtx_image` | `pokeclip-prod/media-mediamtx` | 1번이 `media/Dockerfile.mtxhook` 으로 빌드(훅 바이너리 · USER 10002). FROM 은 현행 포크 태그 — 정본은 볼트 런북 「mediamtx-커스텀이미지-반영」 |
| `sidecar_image` | `pokeclip-prod/media-sidecar` | 1번이 `media/Dockerfile` 로 빌드(USER 10001). I1-1(IAM 인증) 반영본 |
| `ingest_auth_image` | `pokeclip-prod/media-ingest-auth` | 1번이 빌드(`media/Dockerfile.ingest-auth`). 비면 서비스를 렌더하지 않음 |
| `janitor_image` | (저장소 없음) | 자리 — 비면 서비스를 렌더하지 않음 |
| (`media.env` `LOOPBACK_SENDER_IMAGE`) | M7 이 정함(후속) | 루프백 송출기 — `linuxserver/ffmpeg` 미러. 핀 = `images.json` 의 `loopback_sender` |

*   **포크 원본 MediaMTX 이미지를 그대로 미러링하지 않는다.** 훅 바이너리가 없어
    녹화 이벤트가 기록되지 않는다.
*   올리는 절차(O7c)는 [prod 산출물 올리기 런북](../terraform/runbooks/prod-artifacts.md)
    이다. 푸시한 다이제스트를 tfvars 에 넣는다 — `images.json` 의 핀과 다를 수
    있다(한 플랫폼만 올리면 매니페스트가 달라진다).
*   **arm64 주의:** kty 로컬(Apple Silicon)에서 받거나 빌드하면 기본이 arm64 다.
    상자가 amd64 면 기동이 실패한다. 받을 때 `--platform` 을 상자 플랫폼으로
    지정하고, 런북의 아키텍처 확인을 거친다.

### images.json 갱신

`images.json` 은 공식 이미지를 받아 미러링하는 둘(nginx · 루프백 송출기)의 핀
정본이다. 빌드하는 이미지(MediaMTX · 사이드카 · 응답기)는 여기에 없다.

1.  새 태그를 정하고, 그 태그의 **멀티 아키텍처 인덱스 다이제스트**를 읽는다.
    받지 않고 읽기만 한다.

    ```shell
    docker buildx imagetools inspect docker.io/library/nginx:<태그>
    ```

1.  출력의 `Digest:` 가 인덱스 다이제스트다. 그 아래 `Manifests:` 에
    `linux/amd64` · `linux/arm64` 가 **둘 다** 있는지 본다. 하나라도 없으면 쓰지
    않는다(상자 아키텍처가 아직 정해지지 않았다).
1.  `images.json` 의 `source`(태그까지 적은 참조)와 `digest` 를 함께 고친다.
1.  `images.json` 을 읽는 시험을 돌린다 — `proxy_contract.sh` · `loopback_test.sh`
    (사례 10 핀 꼴) · `unit_restart_test.sh`(CI).
1.  머지 뒤 O7c 런북대로 새 핀을 미러링하고, 푸시 다이제스트를 tfvars 에
    넣는다. 상자 반영은 설정 반영 교체다.

## 판 고정

prod 상자에 깔 도구의 판이다. M9 user_data 가 같은 값으로 설치하고, 시작
스크립트의 선행 점검이 Compose 판을 다시 본다.

| 도구 | 판 | 출처 · 확인 |
|---|---|---|
| docker compose 플러그인 | **5.5.0** | 공식 릴리스 `docker/compose` v5.5.0(2026-10-20 확인). `sha256` — `docker-compose-linux-x86_64`: `c57ab918abd5b05ca7e7d0f275875dd1330a695074f309dc9eab1b49efafcd4b` · `docker-compose-linux-aarch64`: `ff42489f5a9b879d5d117c5ffea6defc27390b3286da8ad52cbc9c6ab5df590e` |
| `amazon-ecr-credential-helper` | **0.12.0-3.amzn2023** | Amazon Linux 2023 저장소(release 2023.12.20260930 기준 최신 · 2026-10-20 `dnf` 조회) |
| docker | M9 이 정함 | M2 탐침 상자(AL2023 · systemd 252)는 25.0.16 으로 재기동 시험을 통과했다 |

*   Compose 의 `--abort-on-container-exit` · `--attach` · `--force-recreate`
    동작과 bind 의 `create_host_path: false`(원본이 없으면 디렉터리를 만들지
    않고 그 컨테이너 기동만 실패)는 이 판(5.5.0)과 docker 25.0.16 으로
    실측했다.
*   AL2023 저장소의 판은 AMI 날짜에 묶인다. M9 은 prod AMI 에서 같은 판이 있는지
    확인한다.
*   판을 바꾸면 `media.env` 의 `MEDIA_EXPECTED_COMPOSE_VERSION` 과 tfvars 의
    체크섬도 함께 바꾼다. 하나만 바꾸면 `MEDIA PREREQ FAIL compose-version` 으로
    기동이 막힌다.

## 시험

| 시험 | 보는 것 | 도는 곳 |
|---|---|---|
| `test/render_test.sh` | 세 템플릿 렌더 단언 · `docker compose config -q` · 시험 compose 와의 드리프트(bind 원본 자동 생성 여부 포함) | terraform · docker compose(컨테이너 안 띄움) |
| `test/start_test.sh` | 시작 · 정지 스크립트(가짜 docker · chown · aws) | 어디서나 |
| `test/proxy_contract.sh` | 앞단 프록시 계약 사례 1–17 · 1b(14 는 판정 아닌 기록) | terraform · docker(에코 상류 `test/echo` 빌드) |
| `test/unit_restart_test.sh` | 유닛 재기동 사례 1–19(15–17 = 크래시 경로 · 19 = 기동 중 정지) | 1–3 은 어디서나, 4–19 는 리눅스 · systemd · sudo. **13–17 은 호스트를 흔든다**(docker 엔진 재시작 · dockerd kill -9 · SIGKILL) — CI(`GITHUB_ACTIONS=true`) · 일회용 상자(`PCBENCH_ALLOW_HOST_DISRUPTION=1`)가 아니면 거부한다. 개발 머신에서 돌리지 않는다 |
| `test/loopback_test.sh` · `paths_count_test.sh` · `paths_list_test.sh` · `secret_count_test.sh` · `force_diff_test.sh` | 운영 확인 스크립트(가짜 명령) | 어디서나 |
| `test/harness_selftest.sh` · `query_guard_selftest.sh` | 시험 부품 자체 | 어디서나 |

*   시험 · 실측의 compose 프로젝트 이름은 `pcbench-edge-proxy`, 라벨은
    `com.pokeclip.bench=edge-proxy` 다.
*   제품 파일이 없으면 시험은 FAIL 이다. 건너뛰는 갈래는 없다.

### 시험 이음매

시험은 스크립트가 읽는 변수의 **값만** 바꾼다[^seam]. 선행 점검 세 항목과 CDN
비밀 조회는 모든 실행에서 똑같이 돈다. **우회 플래그는 두지 않는다** —
`SKIP` · `BYPASS` 같은 문자열이 스크립트에 0건인지 `start_test.sh` 가 본다.

| 변수 | prod(유닛 · `media.env`) | 시험에서 바꾸는 값 |
|---|---|---|
| `MEDIA_EXPECTED_COMPOSE_VERSION` | 고정 판(위 표) | 그 환경의 `docker compose version --short` |
| `DOCKER_CONFIG` | `/root/.docker` | 스크래치 디렉터리(가짜 `config.json`) |
| `PATH` | `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin` | `test/fakebin` 을 앞에(가짜 자격 도우미 · aws) |
| `MEDIA_COMPOSE_FILE` | `/etc/pokeclip-media/compose.yml` | `test/compose.contract.yml` · `test/compose.unit.yml` |
| `MEDIA_MTX_TEMPLATE` | `/etc/pokeclip-media/mediamtx.yml.tmpl` | `test/mediamtx.test.yml.tmpl` |
| `CDN_SECRET_ARN` · `ECR_REGISTRY_HOST` | ARN · 호스트(값 아님) | 가짜 문자열 |
| `RUNTIME_DIR` · `MEDIA_COMPOSE_PROJECT` | `/run/pokeclip-media` · `pokeclip-media` | 스크래치 · `pcbench-edge-proxy` |
| `MTX_UID`/`GID` · `SIDECAR_UID`/`GID` | 10002 · 10001 | 로컬 실물 compose 는 자기 uid/gid |

유닛 시험은 prod 유닛 파일을 바이트 그대로 설치하고, drop-in 에는
`Environment=` · `EnvironmentFile=` 만 둔다.

## 알려진 한계

고치지 않고 받아들인 것이다. 우리 상자에서 생기지 않는 입력이거나, 대조 수단이
없는 경우다.

*   **옛 기동의 API 비밀번호:** 비밀번호는 기동마다 새로 만들어지므로
    `secret-count.sh box` 는 이전 기동 로그에 남은 옛 비밀번호를 대조하지
    못한다. `ssm` 모드는 tmpfs 의 비밀번호 파일을 읽을 수 없어 비밀번호를
    대조하지 않는다. 판정 줄(`SECRET COUNT box 0`)에는 이 한계가 적히지
    않는다 — 0 은 「CDN 비밀 · 이번 기동의 비밀번호(`--passphrase` 면 시험 키
    passphrase 도) 0 건」 이다.
*   **비밀번호 파일의 크기 · 내용 사이:** `secret-count.sh` 는 크기를 잰 뒤
    내용을 다시 연다. 그 사이에 파일이 바뀔 수 있지만 그 자리는 root 만 쓰는
    tmpfs 이고 바꾸는 길은 시작 스크립트의 rename(재기동)뿐이다. 바뀌어도 새
    내용을 그대로 꼴 검사하므로 통과로 새지 않는다(실패 또는 새 비밀번호 대조).
*   **paths 응답 크기 상한의 시점:** 8 MiB 상한은 응답을 다 받은 뒤 잰다 — 그
    전까지는 파일에 쓴다. 응답원은 같은 망 칸의 MediaMTX 이고 조회에는 상한
    시간이 있어 받는 양이 끝없이 늘지 않는다.
*   **정지 뒤의 로그:** 정상 정지(compose down) 뒤에는 컨테이너와 그 로그가
    지워지고 비밀번호 파일도 정지 스크립트가 지운다. `secret-count.sh box` 는
    그때 셀 수 없어 실패한다(`FAIL api-pass`) — 정지 뒤에 남는 유닛 · 태그
    저널만 따로 볼 수단은 없다.
*   **SSM 에이전트의 상자 안 실행 기록:** SSM 에이전트가 상자에 남기는 명령 ·
    출력 기록 파일은 `secret-count.sh` 의 대조 밖이다.
*   **운영 확인 스크립트의 스크래치:** `$TMPDIR`(없으면 `/tmp`)에 0600 으로 두고
    끝나면 지운다. 그 자리가 tmpfs 인지는 AL2023 의 `/tmp` 마운트에 달렸다 —
    M9 이 상자에서 확인한다.
*   **paths 조회의 자격 정규화:** 사이드카는 자격 파일의 앞뒤 공백 · 끝 CRLF 를
    떼지만 paths 두 스크립트는 그러지 않는다. 자격 파일은 시작 스크립트가 16진
    64자 + LF 로만 쓰고 사용자 이름은 고정이라 갈리는 입력이 생기지 않는다.
*   **paths 조회의 `pageCount` 산술:** 2^63 급 값이면 셸 산술이 넘쳐 여러 페이지
    판정을 비켜 갈 수 있다. 그 값은 같은 망 칸 MediaMTX API 가 주는 것이라 그
    값을 위조할 수 있으면 응답 전체가 이미 신뢰 밖이고, 실제 경로 수로는 생기지
    않는다.

## 후속

이 디렉터리를 입력으로 쓰는 작업에 넘기는 것이다.

| 받는 쪽 | 할 일 |
|---|---|
| M9 | 템플릿 변수 키 집합에 `detached_log_driver` 를 넣고, 모듈이 문자 그대로 `"journald"` 를 넘기는지 단언한다. `daemon.json` 로그 옵션에 `env` · `labels` 계열 0 |
| M9 | 이미지 변수 꼴 `^[^@]+@sha256:[0-9a-f]{64}$`. 다이제스트가 비면 `…@` 로 조립하지 않고 빈 문자열을 넘긴다(응답기 · janitor) |
| M9 | Media SG 인그레스 단언(위 「SG 가 유일한 경계」). 인그레스 포트에 8888 · 9996 · 9997 0 |
| M9 | prod 프록시 포트 넷(nginx 템플릿 변수)을 모듈이 넘기고 모듈 시험에서 단언한다. user_data 렌더본은 렌더 파일 · nginx 설정 bind 의 긴 문법(`create_host_path: false`)을 그대로 쓴다 — 원본이 없으면 그 컨테이너만 기동에 실패한다. 이 PR 의 유닛 · 스크립트 · compose 템플릿을 함께 설치한다 |
| M9 | 설치 단언: `bin/lib/` 두 파일이 스크립트와 같은 상대 위치에 있고 내용이 저장소와 같다. `bin/` · `bin/lib/` 소유 `root:root`, 스크립트 0755 · lib 0644. `media.env`(유닛 `EnvironmentFile`)의 소유 · 모드(root 만 쓰기). 압축 user_data 16 KiB 게이트를 다시 잰다 |
| M9 | prod 엔진의 비특권 포트 하한(`net.ipv4.ip_unprivileged_port_start`)을 상자에서 잰다. nginx 는 cap CHOWN · SETGID · SETUID 만 받고 `NET_BIND_SERVICE` 가 없다 — 프록시 포트가 하한보다 낮으면 nginx 가 기동하지 못한다 |
| M9 · O7f · edge-secrets 런북 | CDN 비밀 생성 형식을 고정한다: 16진 또는 패딩 없는 base64url. 허용 글자는 `[A-Za-z0-9._+=-]` 이고 표준 base64 의 `/` 는 MediaMTX 가 거부해 기동이 막힌다. 입력 도구 · 런북 문구에 반영 |
| M9 | 유닛 하드닝 지시어(지금은 `LimitCORE=0` 뿐)를 상자에서 정한다 — docker CLI · `/run` 쓰기와 부딪히지 않는 지시어만 실측으로 고른다 |
| M9 | `media.env` 키에 `LOOPBACK_SENDER_IMAGE` · `MEDIA_LIVE_PORT` · `TEST_STREAM_KEY_SECRET_ARN` 을 더한다. ECR 읽기 Resource 에 송출기 저장소 하나 |
| M9 | `bin/lib/` 두 파일을 스크립트와 같은 상대 위치에 설치. nginx 렌더본을 `/etc/pokeclip-media/nginx.conf` 에. journald 보존 설정. 위 「판 고정」 값으로 설치 |
| M9 | 사이드카 `S3_BUCKET` 은 버킷 **이름**(`rewind_bucket_name`)이다. 런타임 출력은 `rewind_bucket_arn` 뿐이라 이름을 꺼내거나 출력을 더한다 |
| M9 | prod 인스턴스 아키텍처를 정한다(O7c 플랫폼 · 위 arm64 주의) |
| M9 런북 · O7e 5 | 사이드카 기동 판정은 태그 저널(`-t pokeclip-media-sidecar`)로. DB 장애 때는 compose 전체가 아니라 사이드카만 다시 뜬다(송출 유지) — 「5분 MainPID 무변화」 는 mediamtx · nginx 감시로만 읽는다. 판정 조건에 「관찰 창 5분 동안 사이드카 태그 저널의 종료 줄 0 건」 을 넣고, 먼저 `docker info` 로 로그 드라이버(journald)를 확인한다 |
| M7 · O7c | 루프백 송출기 ECR 저장소 · 미러 하나를 더한다 |
| I1-1 | RDS CA 번들 경로 env 는 지금 템플릿에 없다. **I1-1 PR 이 `compose.yml.tmpl` 사이드카 env 한 줄과 `render_test.sh` 단언을 더한다.** DB 연결 성공 고정 줄도 I1-1 이 정한다 |
| I1-6 | 제어면 서버가 생기면 `control_upstream` 을 채운다. control 헬스는 nginx 경로라, 사이드카가 다시 뜨는 동안에도 healthy 로 보이고 제어면은 502 를 낸다 |
| kty · 보안 | 보안 런북: 침해 대응 — 증거 보존 방식 · 저널 영속 여부(M9 의 journald 저장 방식 — 휘발 저널은 격리 뒤 꺼낼 길이 없다) · **정지 전 컨테이너 증거 보존 여부**(서비스를 멈추면 compose down 이 컨테이너 파일 시스템과 json-file 로그를 지운다 · 정지 승인을 기다리는 동안 서비스는 계속 돈다) · 격리 수단(IMDS 차단 필수 · 기존 연결 차단) · 역할 세션 취소 뒤 인라인 정책 정리 시점 · 비밀 교체와 상자 교체 순서 |
| 포크 mediamtx 트랙 | 다음 포크 태그에서 설정 조회 응답의 `hlsCDNSecret` 을 가리고(redact), 응답에 비밀이 새지 않는지 회귀 시험을 둔다(위 「받아들인 위험」). ADR 로 남길지는 kty |
| M15 | 응답기 자리를 채운다. passphrase 를 싣는 루프백 단계(`--passphrase`)를 런북에 더한다. 응답기가 kick 에 쓸 관리 API 자격 경로가 아직 없다(전용 사용자 · 파일 · 실행 계정). `authHTTPExclude: api` 를 두면 관리 API 인증이 풀린다. 응답기가 다시 뜨는 동안 송출 인증이 실패하는 것을 실측에 기록한다 |
| M15 | 응답기 계약 반영: 응답기 비밀은 파일로 받으므로 `*_FILE` env 이름(예: `STREAM_KEY_RESOLVE_TOKEN_FILE`)이 `render_test.sh` 의 비밀 계열 env 이름 금지(TOKEN 등)에 걸린다 — 값이 아닌 경로 env 는 허용하게 그 검사를 고친다. `authHTTPExclude` 에서 playback 이 빠지면 9996 재생이 막힌다. read 를 제외하려면 SRT 경로 잠금(`srtPublishPassphrase` · `srtReadPassphrase` · `overridePublisher: no`)을 prod 설정에 먼저 옮긴다. 포트 후보 127.0.0.1:8080 · 헬스 8081. dev-media 응답기와 다른 점(전용 제어망 · UID 10003 · 비밀 env)을 정리한다 |
| O7f · M15 · O8a · edge-secrets 런북 | 루프백 순서: O7f 9 의 기본 루프백은 passphrase 없이 돌고 시험 경로의 공개 토큰(`<ingestStreamId>` 인자)만 있으면 된다 — O7f 8 의 시험 키(passphrase) **입력**은 M15 · O8a 로 넘어간다. 기본 `secret-count.sh box` 는 CDN 비밀(과 이번 기동 API 비밀번호)만 대조한다. M15 전까지 상자는 NLB 송출 대상 밖이라는 전제다. M15 뒤에는 기본 갈래가 거부되는 것이 정상(음성 확인)이고, `--passphrase` 갈래는 인스턴스 역할에 시험 키 ARN 읽기 권한이 있어야 한다. edge-secrets 런북의 「루프백 직전 입력」 문구도 이 순서로 고친다 |

## 출처

*   계획 POK-299 의 M2 절과 착수 때 계획에서 바꾼 점(재기동 정책 `on-failure` ·
    로그 설계 · CA 번들 env 이월 · 0.0.0.0 게시와 SG 경계 · 루프백 순서 · API
    비밀번호 · 무인증 관리 항목 정리 · 단독 restart 관찰 · 두 단계 정지 · 크래시
    경로 정리), 결함 예측표, 로그 설계 절 — 저장소 밖 작업 기록.
*   설계 결정: 루프백 passphrase 의 argv 일시 노출 수용 · 무인증 관리 항목 삭제 ·
    paths 조회 꼴(사이드카 안 고정 `sh -c`) · 자격 필수 · 루프백 순서(M15 전에는
    passphrase 없이) · API 비밀번호 기동마다 생성 · 운영자 API 사용자 없음 · 침해
    대응 상세 절차는 보안 런북으로.
*   사이드카 관리 API 자격 PR(3K-PokeClip/PokeClip-mono#291) 본문 「인프라 쪽
    전달 사항」 — 비밀번호 파일 규칙 · 배포 신호 · 즉시 롤백.
*   실측 기록(저장소 밖, 2026-10-20): 로컬 compose 의 MediaMTX 단독 restart(사례
    14) · 설정 조회 응답의 CDN 비밀 평문 · 탐침 상자(정지 두 단계 · AL2023
    docker 25.0.16 · compose 5.5.0) · compose 판 조회.
*   보안 리뷰(0.0.0.0 게시 · 사이드카 자격 전권).
*   [Docker 문서 「Start containers automatically」](https://docs.docker.com/engine/containers/start-containers-automatically/)
    — 재시작 정책과 호스트 프로세스 관리자를 섞지 말라는 경고.

## See also

*   [prod 산출물 올리기 런북](../terraform/runbooks/prod-artifacts.md)(O7c)
*   [엣지 비밀 런북](../terraform/runbooks/edge-secrets.md)(CDN 비밀 입력 ·
    교체 · 기동 차단 표)
*   [dev-media](../dev-media/README.md) — dev 상시 상자(구성이 다르다)
*   [media/README.md](../../media/README.md) — 사이드카 설정 · 관측 자격

[^box]: **상자** — prod 미디어 서버를 돌리는 EC2 인스턴스 한 대. 교체할 때는 새
    상자(B)를 띄워 옮긴다.
[^sidecar]: **사이드카** — MediaMTX 옆에 붙어 녹화 조각을 인덱싱하고 S3 에 올리는
    1번 서비스(`media/`). 오토바이 옆 칸처럼 본체와 함께 뜨고 진다.
[^netns]: **망 칸** — 리눅스 네트워크 네임스페이스. 컨테이너 여럿이 루프백
    (127.0.0.1)과 포트를 함께 쓰는 한 칸이다. `network_mode: service:mediamtx` 로
    MediaMTX 의 칸에 들어간다.
[^journal]: **저널** — systemd 의 로그 저장소(journald). `journalctl -u <유닛>` 은
    유닛 출력, `-t <태그>` 는 그 태그로 들어온 줄을 읽는다.
[^slot]: **자리** — 템플릿에 서비스 정의만 있고 이미지 변수가 비어 있어 렌더하지
    않는 서비스. 값을 넣으면 생긴다.
[^attach]: **붙는다(attach)** — `compose up --attach <서비스>` 가 그 서비스의
    출력을 받고, `--abort-on-container-exit` 로 그 서비스가 끝나면 compose 를
    끝낸다. 붙지 않은 서비스가 끝나도 compose 는 계속 돈다.
[^digest]: **다이제스트** — 이미지 내용의 해시(`sha256:…`). 태그와 달리 내용이
    바뀌면 값도 바뀐다.
[^mirror]: **미러** — 공식 이미지를 받아 내용을 바꾸지 않고 우리 ECR 에 다시 올린
    사본. 상자는 인터넷 레지스트리가 아니라 ECR 에서만 받는다.
[^pin]: **핀** — 쓸 이미지를 태그가 아니라 다이제스트로 못 박아 둔 값.
[^seam]: **이음매** — 시험이 제품 코드를 고치지 않고 값을 갈아 끼우는 자리. 여기서는
    환경변수 값뿐이다.
