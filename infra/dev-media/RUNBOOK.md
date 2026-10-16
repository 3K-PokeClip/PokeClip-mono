# RUNBOOK — dev-media 계정별 방송 서버

위에서 아래로 순서대로 친다. 설정값은 이 문서에 옮겨 적지 않는다 — 값이 궁금하면 해당
파일을 본다(`mediamtx.demo.yml.tmpl` · `compose.yml` · `nginx.conf`). 배경과 구성도는
`README.md` 다. 블록 안의 고정값(UID 10002 · 10003 · 64 KiB · 토큰 꼴)은 그 파일들과
응답기(`media/cmd/ingest-auth`)가 정한 값을 옮긴 것이다 — 그쪽이 바뀌면 함께 고친다.

**서버에서 실행하는 명령은 전부 root 다.** `.env` 가 `root:root 0600` 이라서, `sudo` 를
빼면 render.sh 는 `.env` 읽기 검사에서 중단되고, compose 는 변수 보간에 실패한다.

**docker 그룹 = 비밀 열람권이다.** 응답기의 비밀 둘(resolve 출입증 · Control API 비번)은
컨테이너 환경 변수라 `docker inspect` 로 보인다. 상자에서 docker 그룹에 사람을 더하지
않는다.

**이 저장소는 public 이다 — 실제 AWS 자원 ID · 공인 IP · 사설 IP 를 이 문서에 적지
않는다**(git 히스토리에 영구히 남는다). 아래 셸 변수를 세션 시작 시 채워서 쓴다. 값은 AWS
콘솔이나 팀 비공개 채널에서 확인한다.

```bash
export VPC_ID=<vpc-id>                 # 팀 dev VPC
export SUBNET_ID=<subnet-id>           # 퍼블릭 서브넷
export AMI_ID=<al2023-ami-id>          # 없으면 1-2 의 SSM 파라미터로 최신 AL2023 조회
export HOSTED_ZONE_ID=<hosted-zone-id> # pokeclip.com 존
export INSTANCE_PROFILE=pokeclip-dev-profile
```

팀 상시 dev EC2 인스턴스와 그 보안그룹은 **이 문서 어디에서도 건드리지 않는다** — 위
변수에도 담지 않는다.

| 절 | 무엇 |
|---|---|
| 0 | 로컬 리허설 — 같은 블록을 로컬에서 돌리는 법 · 줄 문법 대조 |
| 1 | AWS 자원 생성(상자를 처음 만들 때) |
| 2 | 블록 규약 · 공통 머리 · 보존 본문 |
| 3 | 값 주입 |
| 4 | 반영 |
| 5 | 반영 실패 복구 |
| 6 | 실측 |
| 7 | 운영 순서 |
| 8 | 긴급 절단 |
| 9 | 해제 |
| 10 | 틀린 줄 걷기 |
| 11 | 롤백 |
| 12 | 철거 |

---

## 0. 로컬 리허설

상자와 **같은 블록**을 로컬에서 먼저 돌린다. 블록은 리눅스 · bash 4.4 이상 · GNU 도구를
전제하므로(2절) 맥의 셸(bash 3.2)에서 돌리지 않는다 — 리눅스 컨테이너 안에서 root 로
돌린다. 상자 경로는 환경 변수로만 바꾼다. 블록의 바이트는 그대로 둔다.

| 변수 | 상자 기본값 | 로컬에서 |
|---|---|---|
| `DEMO_HOME` | `/opt/pokeclip-demo` | 임시 절대 경로(`.env` · 렌더 산출물 · 백업 · 거부 목록이 사는 곳) |
| `DEMO_REPO` | `${DEMO_HOME}/repo` | 체크아웃 경로 |
| `COMPOSE_PROJECT_NAME` | `dev-media` | 실측 전용 이름(예: `p7-dev-media`) |
| `DEMO_ARCH` | `amd64` | 로컬 아치(R6 의 이미지 아치 단언) |

```bash
# 로컬 실행 꼴 — 머리 + (보존 본문) + 본문을 이은 파일을 표준 입력으로 준다
env DEMO_HOME=/abs/local DEMO_REPO=/abs/repo COMPOSE_PROJECT_NAME=p7-dev-media \
  bash -s -- <인자…> < block.sh
```

- 실험 컨테이너 · 망에는 라벨(`com.pokeclip.bench=<이름>`)과 전용 프로젝트 이름을 붙이고,
  끝나면 잔여 0 을 확인한다.
- 팀 공용 스택이 떠 있으면 `pokeclip-media` 가 8890/udp · 8888/tcp 를 점유해 `up -d` 가
  `port is already allocated` 로 실패한다. 리허설 동안만 내리고 끝나면 되돌린다.
- 로컬 Docker Desktop · OrbStack 은 파일 소유권을 재매핑하므로 설정 파일 모드 함정이
  재현되지 않는다(리눅스 전용). 그래서 상자에서 한 번 더 본다(R4 의 소유 단언).

**줄 문법 대조.** 해제 · 틀린 줄 걷기가 쓰는 셸 계수(머리의 `deny_scan`)는 응답기(Go)와
같은 유효 토큰 집합 · 틀린 줄 수를 내야 한다. 응답기 시험과 같은 공유 입력 벡터(40줄 —
NBSP · 전각 공백 · NUL · CRLF · 끝 줄바꿈 없는 마지막 줄을 담는다)로 대조한다. 셸 계수를
고치면 다시 돌린다.

```bash
# ── 블록: 줄 문법 대조 — 머리 + 이 본문. 인자: <공유 벡터 디렉터리> ──
# 디렉터리는 media/internal/ingestauth/testdata 다. 집합(정렬 목록)과 틀린 줄 수가 응답기
# 시험의 기대 파일과 바이트까지 같아야 한다.
main() {
  (( $# == 1 )) || check_failed '사용법: <공유 벡터 디렉터리>'
  local -r dir="$1"
  deny_scan "${dir}/denylist_vector.txt" "${WORK}/vector"
  assert_same "${dir}/denylist_vector_tokens.txt" "${WORK}/vector.tokens" \
    '유효 토큰 집합이 기대 파일과 다르다'
  assert_same "${dir}/denylist_vector_bad_lines.txt" "${WORK}/vector.bad" \
    '틀린 줄 수가 기대 파일과 다르다'
  echo '줄 문법 대조 통과 — 셸 계수와 응답기 기대 파일이 같다'
}
main "$@"
```

---

## 1. AWS 자원 생성

### 1-1. 보안그룹

인바운드는 정확히 2건이다 — `udp 8890`(SRT 송출) · `tcp 8888`(재생). 소스는 둘 다 팀 공인 IP/32.

```bash
MYIP=$(curl -s https://checkip.amazonaws.com)          # 현재 공인 IP 를 그때그때 산출 (상수로 적지 않는다)
SG=$(aws ec2 create-security-group --group-name pokeclip-media-demo-sg \
      --description "temp mentor demo (expires 2026-08-24)" \
      --vpc-id "$VPC_ID" --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol udp --port 8890 --cidr "$MYIP/32"
aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol tcp --port 8888 --cidr "$MYIP/32"

# 검증: 규칙 2건 · 0.0.0.0/0 은 0건
aws ec2 describe-security-groups --group-ids "$SG" \
  --query 'SecurityGroups[0].IpPermissions[].{proto:IpProtocol,port:FromPort,src:IpRanges[].CidrIp}'
```

### 1-2. EC2 기동

user-data는 **도커 설치와 디렉토리 생성까지만** 한다. 컨테이너 기동은 사람이 한다(시크릿이 필요하므로).

```bash
cat > /tmp/userdata.sh <<'EOF'
#!/bin/bash
set -eux
dnf install -y docker
# AL2023 의 docker 패키지에는 compose 가 없다 — v2 플러그인을 직접 배치한다
install -d /usr/local/lib/docker/cli-plugins
curl -fsSL -o /usr/local/lib/docker/cli-plugins/docker-compose \
  https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
systemctl enable --now docker
# 소유권 모델: 상위 디렉토리·렌더물은 root 소유(그룹 10002 읽기), 세그먼트 디렉토리만 10002 쓰기.
# 컨테이너(10002)는 설정을 읽기만 하면 되므로 탈옥해도 .env·설정을 조작할 수 없다.
install -d -m 750 -o root -g 10002 /opt/pokeclip-demo
install -d -m 750 -o 10002 -g 10002 /opt/pokeclip-demo/dvr
EOF

aws ec2 run-instances --image-id "$AMI_ID" --instance-type t3.small \
  --subnet-id "$SUBNET_ID" --security-group-ids "$SG" \
  --iam-instance-profile Name="$INSTANCE_PROFILE" \
  --metadata-options 'HttpTokens=required,HttpEndpoint=enabled' \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp3,DeleteOnTermination=true}' \
  --associate-public-ip-address --user-data file:///tmp/userdata.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=pokeclip-media-demo},{Key=expires,Value=2026-08-24}]'
```

- `$AMI_ID` 가 비었거나 만료됐으면 최신 AL2023으로 채운다:
  `export AMI_ID=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 --query Parameter.Value --output text)`
- **EIP는 할당하지 않는다.** 대신 **인스턴스를 stop 하지 않는다** — stop 하면 퍼블릭 IP가 바뀐다.
- 검증(부팅 후): `sudo docker compose version` 이 v2.x 를 출력한다.

### 1-3. DNS A 레코드

```bash
IP=$(aws ec2 describe-instances --instance-ids <instance-id> \
      --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
aws route53 change-resource-record-sets --hosted-zone-id "$HOSTED_ZONE_ID" \
  --change-batch "{\"Changes\":[{\"Action\":\"UPSERT\",\"ResourceRecordSet\":{
    \"Name\":\"media-dev.pokeclip.com\",\"Type\":\"A\",\"TTL\":60,
    \"ResourceRecords\":[{\"Value\":\"$IP\"}]}}]}"

dig +short media-dev.pokeclip.com     # 위 IP 가 나와야 한다 (네임서버는 존 조회로 확인)
```

### 1-4. 접속 확인

```bash
aws ssm describe-instance-information --query "InstanceInformationList[?InstanceId=='<instance-id>'].PingStatus"  # Online
nc -z -w3 media-dev.pokeclip.com 22 && echo "22 열림 — 즉시 조사" || echo "22 닫힘(정상)"
```

---

## 2. 블록 규약 · 공통 머리 · 보존 본문

### 2-1. 블록을 보내는 법

상자 명령은 **블록**으로 보낸다. 블록은 2-2 의 **머리** + (보존을 하는 블록만 2-3 의
**보존 본문**) + 그 블록의 **본문**을 차례로 이은 bash 스크립트 하나다.

- **SSM RunShellScript 명령 하나로 보낸다.** 세션이 끊겨도 명령이 상자에서 끝까지 돈다.
  대화형 셸에 붙여 넣지 않는다 — `set -e` · `exit` 가 세션을 닫는다. 예외는 3절의 값
  주입 하나다(대화형 세션).
- **꼴:** `sudo bash -s -- <인자…> <<'STEP'` · 스크립트 · `STEP`. 인자는 블록마다 머리
  주석에 적었다. 토큰 · SHA · 파일 이름만 인자로 싣는다 — 비밀 · 주소는 싣지 않는다.
- **실행 직전 kty 승인.** 상자를 바꾸는 블록(R0–R6 · 반영 취소 · 덧붙이기 · cut · 해제
  적용 · 되돌림 적용 · 틀린 줄 걷기 · 응답기 살리기 · 다시 시작 · MediaMTX 최후 재시작)은
  블록마다 승인을 받는다. 읽기만 하는 블록(R1 값 단언 · 반영 취소 ⓒ · 걷기 확인)과 준비
  블록(해제 준비 · 되돌림 준비 — 살아 있는 파일을 바꾸지 않는다)도 박스 명령이라 승인
  묶음에 넣는다.
- **종료 코드:** 0 통과 · 1 일치 없음(기록 · 기대와 다르다 — 이 문서의 그 자리 처방을
  본다) · 2 검사 실패(목록 · 조회 · 도구가 돌지 못했다 · 인자가 틀렸다). 출력의 마지막
  줄이 이유다.
- 블록은 비밀 값을 출력하지 않는다. 출력은 이름 · 개수 · 상태 · 시각뿐이다.

### 2-2. 공통 머리

모든 블록의 맨 앞에 그대로 둔다. 롤백 블록(11절)도 같은 머리를 쓴다.

```bash
# ── 블록: 머리 — 모든 블록의 맨 앞에 그대로 둔다 ──
# 블록 = 이 머리 + (보존 본문) + 그 블록의 본문이다. 이어 붙인 bash 하나를 SSM
# RunShellScript 명령 하나로 보낸다(예: sudo bash -s -- <인자> <<'STEP' … STEP). 대화형
# 셸에 붙여 넣지 않는다 — exit 는 그 스크립트만 끝낸다. 스크립트가 표준 입력으로 들어가므로
# 본문의 어떤 명령도 표준 입력을 읽지 않는다(읽는 것은 < 나 파이프로 준다).
# 전제: 리눅스 · bash 4.4 이상 · GNU coreutils(stat -c · mv -T · sort -z · comm -z ·
# chown/chmod --reference) · GNU tar(--compare) · sudo · docker compose v2.
# 종료 코드: 0 통과 · 1 일치 없음(기록 · 기대와 다르다) · 2 검사 실패(목록 · 조회 · 도구가
# 돌지 못했다 · 인자가 틀렸다). 일치 없음은 명령 치환 · 파이프 밖에서만 낸다 — 안에서
# 내면 바깥의 ERR 덫이 검사 실패로 바꿔 찍는다.
# 자리는 상자 값이 기본이다. 로컬 리허설은 환경 변수(DEMO_HOME · DEMO_REPO ·
# COMPOSE_PROJECT_NAME)로만 바꾼다 — 블록의 바이트는 그대로다(0절).
set -euo pipefail
set -o errtrace   # 아래 ERR 덫이 함수 · 명령 치환에도 걸린다
# 덫은 shopt 보다 먼저 둔다 — 그 줄이 실패해도 종료 2 다
trap 'echo "검사 실패(종료 $?): ${BASH_COMMAND}" >&2; exit 2' ERR
# 명령 치환 안에서도 set -e 가 선다(bash 4.4 이상 — 낮으면 이 줄에서 종료 2)
shopt -s inherit_errexit
# 바이트 단위로 센다 — 토큰 꼴 · 공백 집합(줄 문법)과 정렬이 로캘에 따라 갈리지 않게
export LC_ALL=C
readonly DEMO_DIR="${DEMO_HOME:-/opt/pokeclip-demo}"  # 백업 · .env · 렌더 산출물
readonly REPO="${DEMO_REPO:-${DEMO_DIR}/repo}"         # 상자 체크아웃
readonly COMPOSE_YML='infra/dev-media/compose.yml'
readonly PROJECT="${COMPOSE_PROJECT_NAME:-dev-media}"  # compose 프로젝트 이름
readonly DENY_DIR="${DEMO_DIR}/ingest-auth/deny"       # 응답기의 /etc/ingest-auth
readonly DENY_FILE="${DENY_DIR}/deny-tokens"           # 거부 목록(살아 있는 파일)
readonly STATE_DIR="${DEMO_DIR}/ingest-auth/state"     # 허용 연결 기록
readonly RESPONDER_ID=10003                            # 응답기의 UID · GID
readonly DENY_MAX_BYTES=65536                          # 응답기의 거부 목록 상한
readonly TOKEN_RE='^[0-9A-HJKMNP-TV-Z]{26}$'           # 정규식 경로와 같은 식
OWNER="$(stat -c '%U' -- "${REPO}")"   # git 은 체크아웃 소유자로 부른다
readonly OWNER
WORK="$(mktemp -d)"   # 이 스크립트의 임시 목록(이름 · 상태 · 토큰 — 비밀 없음)
readonly WORK
trap 'rm -rf -- "${WORK}"' EXIT

mismatch() {
  echo "일치 없음: $*" >&2
  exit 1
}

check_failed() {
  echo "검사 실패: $*" >&2
  exit 2
}

# owner_git 은 체크아웃 소유자 신원으로 git 을 부른다.
owner_git() {
  sudo -u "${OWNER}" git -C "${REPO}" "$@"
}

# assert_same 은 두 파일이 바이트까지 같은지 본다. 다르면 일치 없음(셋째 인자가 있으면 그
# 문구로), cmp 가 돌지 못하면 검사 실패다.
assert_same() {
  local rc=0
  cmp -s -- "$1" "$2" || rc=$?
  case "${rc}" in
    0) ;;
    1) mismatch "${3:-$1 · $2}" ;;
    *) check_failed "cmp ${rc}: $1 · $2" ;;
  esac
}

# assert_bundle 은 추적 밖 묶음의 파일이 지금 트리와 내용 · 크기 · 모드 · 소유 · 수정
# 시각까지 같은지 본다(GNU tar --compare — 종료 0 같음 · 1 다름 · 2 오류). 다르면 일치
# 없음, tar 가 돌지 못하면 검사 실패다. 묶음에 든 파일만 본다 — 이름 집합은 -z
# 목록(assert_same)이 본다.
assert_bundle() {
  local rc=0
  tar -C "${REPO}" --compare -zf "$1" >&2 || rc=$?
  case "${rc}" in
    0) ;;
    1) mismatch "묶음과 트리의 내용이 다르다: $1" ;;
    *) check_failed "tar --compare ${rc}: $1" ;;
  esac
}

# keep_backup 은 백업이 없을 때만 지금 상태로 만든다(덮어쓰지 않는다). .part 를 거쳐
# 이름을 바꾸므로 끊겨도 반쪽 백업이 남지 않는다. 있든 만들었든 백업이 지금 상태와
# 바이트까지 같은지 단언한다.
keep_backup() {
  local -r now="$1" backup="$2"
  if [[ ! -e "${backup}" ]]; then
    cp -p -- "${now}" "${backup}.part"
    mv -T -- "${backup}.part" "${backup}"
  fi
  assert_same "${now}" "${backup}"
}

# list_docker_names 는 이 compose 프로젝트의 컨테이너 · 망 이름을 낸다(복귀 확인 · 잔여
# 0 의 기준).
list_docker_names() {
  docker ps -a --filter "label=com.docker.compose.project=${PROJECT}" \
    --format 'container {{.Names}}' | sort
  docker network ls --filter "label=com.docker.compose.project=${PROJECT}" \
    --format 'network {{.Name}}' | sort
}

# list_running_services 는 이 compose 프로젝트에서 도는 서비스 이름을 정렬해 낸다.
list_running_services() {
  docker ps --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter status=running \
    --format '{{.Label "com.docker.compose.service"}}' | sort
}

# list_untracked 는 지금의 추적 밖 파일 이름을 -z 로(정렬해) 낸다. 지금 무시 규칙이
# 무시하는 파일은 빼되, 반영 전 목록(pre-T.untracked.z)에 있던 이름은 무시돼도 넣는다 —
# 판마다 무시 규칙이 달라 롤백 B 의 정리가 지우는 파일(반영 전 추적 밖 · T 무시 —
# hls.min.js)이 T 보존에서 빠지지 않게 한다. 반영 전 목록이 아직 없으면(반영 직전 첫
# 보존) 지금 규칙의 목록 그대로다. 출력은 파일로 받는다.
list_untracked() {
  owner_git ls-files -z --others --exclude-standard | sort -z > "${WORK}/cur.z"
  owner_git ls-files -z --others | sort -z > "${WORK}/all.z"   # 무시 파일 포함
  if [[ -e "${DEMO_DIR}/pre-T.untracked.z" ]]; then
    comm -z -12 -- "${WORK}/all.z" "${DEMO_DIR}/pre-T.untracked.z" \
      > "${WORK}/pre.z"
  else
    : > "${WORK}/pre.z"
  fi
  sort -z -u -- "${WORK}/cur.z" "${WORK}/pre.z"
}

# untracked_outside_bundles 는 지금의 추적 밖 이름(list_untracked) 가운데 두 묶음(T ·
# 반영 전)의 목록 어디에도 없는 것을 -z 로 낸다. 생산자가 실패하면 그 자리에서 검사
# 실패다(프로세스 치환을 쓰지 않는다).
untracked_outside_bundles() {
  list_untracked > "${WORK}/now.z"
  sort -z -u -- "${DEMO_DIR}/T.untracked.z" "${DEMO_DIR}/pre-T.untracked.z" \
    > "${WORK}/keep.z"
  comm -z -23 -- "${WORK}/now.z" "${WORK}/keep.z"
}

# paired_compose 는 docker compose 를 트리의 HEAD 와 짝 맞는 env 로 부른다. 롤백의
# docker compose 는 모두 이것으로 부르고, 출력은 파일로 받는다. 앞에서 PRE_SHA · T_SHA 를
# readonly 로 둔다.
paired_compose() {
  local head_sha env_file
  head_sha="$(owner_git rev-parse HEAD)"
  if [[ "${head_sha}" == "${T_SHA}" ]]; then
    env_file="${DEMO_DIR}/.env.T.bak"
  elif [[ "${head_sha}" == "${PRE_SHA}" ]]; then
    env_file="${DEMO_DIR}/.env.pre-T.bak"
  else
    mismatch "HEAD 가 T 도 반영 전도 아니다 — compose 와 짝 맞는 env 가 없다"
  fi
  docker compose -p "${PROJECT}" --env-file "${env_file}" \
    -f "${REPO}/${COMPOSE_YML}" "$@"
}

# live_compose 는 docker compose 를 지금의 .env 로 부른다 — 반영 · 운영 블록이 쓴다(롤백은
# paired_compose). 출력은 파일로 받는다.
live_compose() {
  docker compose -p "${PROJECT}" --env-file "${DEMO_DIR}/.env" \
    -f "${REPO}/${COMPOSE_YML}" "$@"
}

# container_id 는 이 compose 프로젝트에서 서비스 $1 의 컨테이너 id 하나를 변수 $2 에
# 넣는다. 없거나 둘 이상이면 일치 없음이다.
container_id() {
  local -a ids
  docker ps -a -q --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter "label=com.docker.compose.service=$1" > "${WORK}/id.$1"
  mapfile -t ids < "${WORK}/id.$1"
  (( ${#ids[@]} == 1 )) || mismatch "$1 컨테이너가 하나가 아니다(${#ids[@]}개)"
  printf -v "$2" '%s' "${ids[0]}"
}

# restore_head_shape 는 HEAD 형태를 기록대로 맞춘다. 기록이 브랜치이고 그 브랜치가 아직
# 그 SHA 를 가리키면 그 브랜치로 옮긴다. 옮겨졌으면 분리 HEAD 로 두고 알린다. 기록이
# 분리면 그대로 둔다.
restore_head_shape() {
  local -r record="$1" sha="$2"
  local want branch_sha
  want="$(cat -- "${record}")"
  if [[ "${want}" != refs/heads/* ]]; then
    return 0
  fi
  branch_sha="$(owner_git for-each-ref --format='%(objectname)' "${want}")"
  if [[ "${branch_sha}" == "${sha}" ]]; then
    owner_git switch -q "${want#refs/heads/}"
  else
    echo "알림: ${want} 가 ${sha} 를 가리키지 않아 분리 HEAD 로 둔다" >&2
  fi
}

# report_head_shape 는 지금 HEAD 형태를 기록과 견줘 다르면 알린다. 멈추지 않는다 — 형태는
# 롤백 B · D 가 맞추고, 브랜치가 옮겨졌으면 분리로 둔다.
report_head_shape() {
  local rc=0
  owner_git rev-parse --symbolic-full-name HEAD > "${WORK}/head.now"
  cmp -s -- "${WORK}/head.now" "$1" || rc=$?
  case "${rc}" in
    0) ;;
    1)
      echo "알림: HEAD 형태가 기록($(cat -- "$1"))과 다르다 —" \
        "지금 $(cat -- "${WORK}/head.now")" >&2
      ;;
    *) check_failed "cmp ${rc}: HEAD 형태" ;;
  esac
}

# wait_ingest_auth_healthy 는 ingest-auth 가 120초 안에 healthy 가 되는지 되풀이해 본다
# (운영 순서 — 7절). 넘기면 일치 없음이다. 원인이 거부 목록의 틀린 줄이면(응답기 로그
# ingestauth_denylist_error) 손으로 고치지 않고 틀린 줄 걷기(10절 — 재시작 없음)를 돈 뒤
# 그 블록을 다시 돌린다.
wait_ingest_auth_healthy() {
  local id='' status
  local -r deadline=$(( SECONDS + 120 ))
  container_id ingest-auth id
  while true; do
    status="$(docker inspect -f '{{.State.Health.Status}}' "${id}")"
    if [[ "${status}" == 'healthy' ]]; then
      return 0
    fi
    (( SECONDS < deadline )) \
      || mismatch "120초 안에 healthy 가 아니다(마지막 ${status})"
    sleep 3
  done
}

# restart_ingest_auth_only 는 ingest-auth 컨테이너 하나만 다시 시작한다 — compose 의
# restart 대신 컨테이너 하나를 겨눈다. MediaMTX 의 시작 시각이 앞뒤로 같은지 보고, 재시작
# 창(명령 → healthy)의 두 시각을 낸다. healthy 시한을 넘기면 일치 없음이다.
restart_ingest_auth_only() {
  local auth_id='' mtx_id='' before after
  container_id ingest-auth auth_id
  container_id mediamtx mtx_id
  before="$(docker inspect -f '{{.State.StartedAt}}' "${mtx_id}")"
  echo "재시작 명령: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  docker restart -- "${auth_id}" > /dev/null
  wait_ingest_auth_healthy
  echo "healthy: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  after="$(docker inspect -f '{{.State.StartedAt}}' "${mtx_id}")"
  [[ "${after}" == "${before}" ]] \
    || mismatch 'MediaMTX 가 다시 시작됐다 — 「응답기만」이 깨졌다(9-6)'
}

# count_lines_with 는 파일 $1 에서 글 $2 가 든 줄의 수를 낸다(명령 치환으로 받는다).
count_lines_with() {
  local line
  local -i n=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" == *"$2"* ]]; then
      n+=1
    fi
  done < "$1"
  printf '%d\n' "${n}"
}

# assert_back_to_pre_t 는 지금 상태가 반영 전 기록(pre-T.*)과 같은지 본다(복귀 확인 —
# 읽기만). 반영 취소 ⓒ · 롤백 단계 C · T 백업 처분의 첫머리가 이 함수 하나를 부른다.
# 앞에서 PRE_SHA 를 readonly 로 둔다.
assert_back_to_pre_t() {
  local head_sha
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" == "${PRE_SHA}" ]] || mismatch "HEAD 가 반영 전 SHA 가 아니다"
  owner_git status --porcelain > "${WORK}/status"
  assert_same "${WORK}/status" "${DEMO_DIR}/pre-T.status"
  # 트리가 기록과 같다 — compose · 이미지 핀도 같다
  owner_git diff --binary HEAD > "${WORK}/worktree.patch"
  assert_same "${WORK}/worktree.patch" "${DEMO_DIR}/pre-T.worktree.patch"
  # 추적 밖 이름(-z)과 그 내용(hls.min.js 등)이 반영 전과 같다
  list_untracked > "${WORK}/untracked.z"
  assert_same "${WORK}/untracked.z" "${DEMO_DIR}/pre-T.untracked.z"
  assert_bundle "${DEMO_DIR}/pre-T.untracked.tgz"
  assert_same "${DEMO_DIR}/.env.pre-T.bak" "${DEMO_DIR}/.env"
  assert_same "${DEMO_DIR}/mediamtx.demo.yml.pre-T.bak" \
    "${DEMO_DIR}/mediamtx.demo.yml"
  # ingest-auth · mtx-control 이 없다 — 반영 전과 같다
  list_docker_names > "${WORK}/docker"
  assert_same "${WORK}/docker" "${DEMO_DIR}/pre-T.docker"
  list_running_services > "${WORK}/running"
  printf '%s\n' edge mediamtx > "${WORK}/running.want"
  assert_same "${WORK}/running" "${WORK}/running.want"
  report_head_shape "${DEMO_DIR}/pre-T.head"
}

# t_backup_names 는 ${DEMO_DIR} 의 T 백업 이름(T.* · *.T.bak · *.T.bak.part)을 한 줄에
# 하나씩 낸다. 반영 전 보존의 첫머리 · 롤백 E-2 · T 백업 처분 · 반영 전 비밀 파기가
# 잔여를 본다.
t_backup_names() {
  local name
  ls -1A -- "${DEMO_DIR}" > "${WORK}/names"   # 목록을 먼저 받는다 — 못 받으면 검사 실패
  while IFS= read -r name; do
    case "${name}" in
      T.* | *.T.bak | *.T.bak.part) printf '%s\n' "${name}" ;;
    esac
  done < "${WORK}/names"
}

# dispose_t_backups 는 T 백업 아홉과 그 .part 를 있는 것만 지운다 — 비밀 둘(resolve 토큰 ·
# API 비번 · 잠금값)은 shred -u(이름 제거 + 최선 노력 — 11-7), 나머지 일곱은 rm. 끝에 T
# 백업 이름 0 을 단언한다. 롤백 E-2 와 T 백업 처분 블록이 이 함수 하나를 부른다(첫머리
# 단언은 각 블록에 있다).
dispose_t_backups() {
  local name path
  for name in .env.T.bak mediamtx.demo.yml.T.bak; do
    for path in "${DEMO_DIR}/${name}" "${DEMO_DIR}/${name}.part"; do
      if [[ -e "${path}" ]]; then
        shred -u -- "${path}"
      fi
    done
  done
  for name in T.sha T.head T.status T.worktree.patch T.untracked.z \
    T.untracked.tgz T.docker; do
    rm -f -- "${DEMO_DIR}/${name}" "${DEMO_DIR}/${name}.part"
  done
  t_backup_names > "${WORK}/t-left"
  [[ ! -s "${WORK}/t-left" ]] \
    || mismatch "T 백업 잔여: $(tr '\n' ' ' < "${WORK}/t-left")"
}

# deny_scan 은 거부 목록 파일 $1 을 줄 문법(응답기 DenyList 와 같은 규칙)으로 읽는다.
# 줄마다 '#' 뒤를 버리고 앞뒤 공백(C 로캘 [[:space:]] 의 여섯 — 스페이스 · 탭 · LF · VT ·
# FF · CR)을 걷은 값이 토큰이다. 빈 값은 건너뛰고, 토큰 꼴이 아니거나 NUL 이 든 줄은
# 틀린 줄이다. 끝 줄바꿈이 없는 마지막 줄도 한 줄이다. 파일 셋을 쓴다.
#   $2.tokens  유효 토큰 집합(바이트 순 · 한 줄에 하나 · 같은 토큰은 하나)
#   $2.bad     틀린 줄 수(십진수 한 줄)
#   $2.kept    $3 이 있으면 값이 $3 인 줄을, 없으면 틀린 줄을 뺀 나머지 줄(원래 차례 ·
#              바이트, 줄마다 줄바꿈). NUL 이 든 줄은 늘 뺀다.
deny_scan() {
  local -r file="$1" out="$2" drop="${3:-}"
  local line value flag
  local -i n=0 bad=0
  local -A nul_lines=()
  local -a tokens=()
  # bash 의 read 는 NUL 을 버린다 — NUL 이 든 줄의 번호를 먼저 따로 받는다
  tr -cd '\000\n' < "${file}" | tr '\000' 'N' > "${WORK}/nul-flags"
  while IFS= read -r flag || [[ -n "${flag}" ]]; do
    n+=1
    if [[ -n "${flag}" ]]; then
      nul_lines[${n}]=1
    fi
  done < "${WORK}/nul-flags"
  n=0
  : > "${out}.kept"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    n+=1
    if [[ -n "${nul_lines[${n}]:-}" ]]; then
      continue   # 틀린 줄 — 아래에서 nul_lines 로 센다
    fi
    value="${line%%#*}"
    value="${value#"${value%%[![:space:]]*}"}"   # 앞 공백
    value="${value%"${value##*[![:space:]]}"}"   # 뒤 공백
    if [[ -z "${value}" ]]; then
      :
    elif [[ "${value}" =~ ${TOKEN_RE} ]]; then
      tokens+=("${value}")
      if [[ "${value}" == "${drop}" ]]; then
        continue
      fi
    else
      bad+=1
      if [[ -z "${drop}" ]]; then
        continue
      fi
    fi
    printf '%s\n' "${line}" >> "${out}.kept"
  done < "${file}"
  bad+=${#nul_lines[@]}
  if (( ${#tokens[@]} > 0 )); then
    printf '%s\n' "${tokens[@]}" | sort -u > "${out}.tokens"
  else
    : > "${out}.tokens"
  fi
  printf '%d\n' "${bad}" > "${out}.bad"
}

# assert_responder_may 는 응답기(UID · GID 10003 — 보조 그룹 없음)가 $1 에 권한 $2(8진
# 비트 — 4 읽기 · 1 지나가기 · 7 모두)를 갖는지 모드 비트로 본다. 소유자가 10003 이면
# 소유자 칸, 그룹이 10003 이면 그룹 칸, 아니면 기타 칸이다. 모자라면 일치 없음이다.
assert_responder_may() {
  local -r path="$1" want="$2"
  local info uid gid mode
  local -i offset=0
  info="$(stat -c '%u %g %a' -- "${path}")"
  read -r uid gid mode <<< "${info}"
  if (( uid == RESPONDER_ID )); then
    offset=6
  elif (( gid == RESPONDER_ID )); then
    offset=3
  fi
  (( ((8#${mode} >> offset) & want) == want )) \
    || mismatch "응답기가 ${path} 에 권한 ${want} 이 없다(${uid}:${gid} ${mode})"
}

# assert_ready_to_start_ingest_auth 는 응답기를 (다시) 띄우기 전에 그 입력을 본다(7-4 —
# kty 106차). 거부 목록 파일이 없으면 응답기는 빈 목록으로 떠 거부했던 토큰이 신호 없이
# 다시 붙으므로 멈춘다. 거부 목록은 일반 파일 · 64 KiB 이하 · 응답기가 읽음 · 틀린 줄 0,
# 상태 디렉터리는 10003 소유 · 응답기가 쓸 수 있음을 본다.
assert_ready_to_start_ingest_auth() {
  local size bad owner
  [[ -d "${DENY_DIR}" && ! -L "${DENY_DIR}" ]] \
    || mismatch "거부 목록 디렉터리가 없다: ${DENY_DIR}"
  [[ -f "${DENY_FILE}" && ! -L "${DENY_FILE}" ]] \
    || mismatch "거부 목록 파일이 없거나 일반 파일이 아니다(7-4): ${DENY_FILE}"
  size="$(stat -c '%s' -- "${DENY_FILE}")"
  (( size <= DENY_MAX_BYTES )) \
    || mismatch "거부 목록이 64 KiB 를 넘는다(${size} 바이트)"
  assert_responder_may "${DENY_DIR}" 1
  assert_responder_may "${DENY_FILE}" 4
  deny_scan "${DENY_FILE}" "${WORK}/ready"
  bad="$(< "${WORK}/ready.bad")"
  [[ "${bad}" == 0 ]] \
    || mismatch "거부 목록에 틀린 줄이 ${bad}개 — 틀린 줄 걷기(10절)가 먼저다"
  [[ -d "${STATE_DIR}" && ! -L "${STATE_DIR}" ]] \
    || mismatch "상태 디렉터리가 없다: ${STATE_DIR}"
  owner="$(stat -c '%u' -- "${STATE_DIR}")"
  [[ "${owner}" == "${RESPONDER_ID}" ]] \
    || mismatch "상태 디렉터리가 ${RESPONDER_ID} 소유가 아니다(${owner})"
  assert_responder_may "${STATE_DIR}" 7
}

# assert_unblock_files 는 해제의 집합 단언이다 — 해제 준비 3 · 5 와 해제 적용 7 이 이 함수
# 하나를 부른다. 인자는 사본 · 새 파일 · 대상 토큰 · 단계(plain — 주석 전, commented —
# 절단 사유 주석 한 줄을 붙인 최종 새 파일)다. 대상이 사본의 집합에 있다 · 새 파일의 집합 =
# 사본의 집합 − {대상} · 두 파일에 틀린 줄 0 · 대상의 줄 밖의 줄은 차례 · 바이트까지
# 같다(commented 면 그 뒤에 '#' 로 시작하는 한 줄만 더 있다) · 새 파일을 응답기가 읽는다.
# 어긋나면 일치 없음이고 아무것도 바꾸지 않는다.
assert_unblock_files() {
  local -r copy="$1" new="$2" target="$3" stage="$4"
  local t bad size lines last first
  local -i found=0
  deny_scan "${copy}" "${WORK}/copy" "${target}"
  deny_scan "${new}" "${WORK}/new"
  bad="$(< "${WORK}/copy.bad")"
  [[ "${bad}" == 0 ]] || mismatch "사본에 틀린 줄이 ${bad}개 — 틀린 줄 걷기(10절) 뒤 준비부터"
  bad="$(< "${WORK}/new.bad")"
  [[ "${bad}" == 0 ]] || mismatch "새 파일에 틀린 줄이 ${bad}개"
  : > "${WORK}/want.tokens"
  while IFS= read -r t; do
    if [[ "${t}" == "${target}" ]]; then
      found=1
    else
      printf '%s\n' "${t}" >> "${WORK}/want.tokens"
    fi
  done < "${WORK}/copy.tokens"
  (( found )) || mismatch '대상이 사본의 집합에 없다'
  assert_same "${WORK}/want.tokens" "${WORK}/new.tokens" \
    '새 파일의 집합이 사본의 집합 − {대상} 이 아니다'
  case "${stage}" in
    plain)
      assert_same "${WORK}/copy.kept" "${new}" '대상의 줄 밖의 줄이 사본과 다르다'
      ;;
    commented)
      size="$(stat -c '%s' -- "${WORK}/copy.kept")"
      head -c "${size}" -- "${new}" > "${WORK}/new.head"
      assert_same "${WORK}/copy.kept" "${WORK}/new.head" \
        '대상의 줄 밖의 줄이 사본과 다르다'
      tail -c "+$(( size + 1 ))" -- "${new}" > "${WORK}/new.tail"
      lines="$(wc -l < "${WORK}/new.tail")"
      last="$(tail -c 1 -- "${WORK}/new.tail")"
      [[ "${lines}" == 1 && -z "${last}" ]] \
        || mismatch '사본 뒤에 붙은 것이 주석 한 줄이 아니다'
      IFS= read -r first < "${WORK}/new.tail"
      [[ "${first}" == '#'* ]] || mismatch "사본 뒤에 붙은 줄이 '#' 로 시작하지 않는다"
      ;;
    *) check_failed "단계는 plain · commented 가운데 하나다: ${stage}" ;;
  esac
  assert_responder_may "${new}" 4
}

# assert_walk_files 는 틀린 줄 걷기의 단언이다. 사본 $1 과 새 파일 $2 의 유효 토큰 집합이
# 같다 · 새 파일에 틀린 줄 0 · 틀린 줄 밖의 줄은 차례 · 바이트까지 같다 · 새 파일을
# 응답기가 읽는다. 어긋나면 일치 없음이다.
assert_walk_files() {
  local -r copy="$1" new="$2"
  local bad
  deny_scan "${copy}" "${WORK}/walk-copy"
  deny_scan "${new}" "${WORK}/walk-new"
  bad="$(< "${WORK}/walk-new.bad")"
  [[ "${bad}" == 0 ]] || mismatch "새 파일에 틀린 줄이 ${bad}개"
  assert_same "${WORK}/walk-copy.tokens" "${WORK}/walk-new.tokens" \
    '유효 토큰 집합이 사본과 다르다'
  assert_same "${WORK}/walk-copy.kept" "${new}" '틀린 줄 밖의 줄이 사본과 다르다'
  assert_responder_may "${new}" 4
}

# assert_revert_file 은 사본 되돌림의 단언이다. 되돌릴 파일 $3 의 유효 토큰 집합 = 사본
# $1 의 집합 ∪ 덧붙은 줄 $2 의 집합 · 틀린 줄 0 · 사본의 바이트가 그대로 앞에 있다 ·
# 응답기가 읽는다. 어긋나면 일치 없음이다.
assert_revert_file() {
  local -r copy="$1" added="$2" revert="$3"
  local bad size
  deny_scan "${copy}" "${WORK}/rv-copy"
  deny_scan "${added}" "${WORK}/rv-added"
  deny_scan "${revert}" "${WORK}/rv"
  bad="$(< "${WORK}/rv.bad")"
  [[ "${bad}" == 0 ]] || mismatch "되돌릴 파일에 틀린 줄이 ${bad}개"
  sort -u -- "${WORK}/rv-copy.tokens" "${WORK}/rv-added.tokens" \
    > "${WORK}/rv.want"
  assert_same "${WORK}/rv.want" "${WORK}/rv.tokens" \
    '되돌릴 파일의 집합이 사본 ∪ 덧붙은 토큰이 아니다'
  size="$(stat -c '%s' -- "${copy}")"
  head -c "${size}" -- "${revert}" > "${WORK}/rv.head"
  assert_same "${copy}" "${WORK}/rv.head" '되돌릴 파일이 사본으로 시작하지 않는다'
  assert_responder_may "${revert}" 4
}

# swap_deny_file 은 $1 을 거부 목록(살아 있는 파일)으로 끼운다 — 같은 디렉터리에 cp -p 로
# 옮긴 뒤 이름 바꾸기 한 번이다. $1 은 기록으로 남는다.
swap_deny_file() {
  cp -p -- "$1" "${DENY_FILE}.swap"
  mv -T -- "${DENY_FILE}.swap" "${DENY_FILE}"
}
```

### 2-3. 보존 본문

반영 전 보존(R0 — `pre-T`)과 롤백 단계 A(`T`)가 같은 함수를 쓴다. 머리 뒤 · 본문 앞에
둔다. 상태 하나에 백업 아홉이다 — SHA · HEAD 형태 · status · 손 수정 패치 · 컨테이너 ·
망 이름 · `.env` · 렌더 산출물 · 추적 밖 이름 목록(`-z`) · 추적 밖 묶음.

- 덮어쓰지 않는다. 백업이 이미 있으면 만들지 않고 지금 상태와 같은지 단언한다. 그래서
  끊긴 보존을 다시 돌리면 이어지고, 그사이 상태가 바뀌었으면 「일치 없음」으로 멈춘다.
- 추적 밖은 이름(`-z` 목록끼리 바이트)과 내용(`tar --compare` — 내용 · 크기 · 모드 ·
  소유 · 수정 시각)을 둘 다 본다. 반쪽 백업은 `.part` 이름이라 남지 않는다.
- `.env` · 렌더 산출물 백업은 비밀(옛 passphrase · 잠금값)을 담는다. `cp -p` 로 원래
  소유 · 모드(`.env` root:root 0600 · 렌더물 root:10002 0640)를 지킨다.

```bash
# ── 블록: 보존 본문 — 머리 뒤 · 본문 앞에 둔다. preserve_state <pre-T|T> ──
# 백업은 덮어쓰지 않고, 만든 직후(또는 있으면 지금) 단언한다.
preserve_state() {
  local -r state="$1"
  local part
  owner_git rev-parse HEAD > "${WORK}/sha"
  # 브랜치면 refs/heads/…, 분리면 HEAD
  owner_git rev-parse --symbolic-full-name HEAD > "${WORK}/head"
  owner_git status --porcelain > "${WORK}/status"
  owner_git diff --binary HEAD > "${WORK}/worktree.patch"   # 손 수정
  list_docker_names > "${WORK}/docker"
  for part in sha head status worktree.patch docker; do
    keep_backup "${WORK}/${part}" "${DEMO_DIR}/${state}.${part}"
  done
  # 비밀 — .env 는 root:root 0600, 렌더 산출물은 root:10002 0640 그대로
  keep_backup "${DEMO_DIR}/.env" "${DEMO_DIR}/.env.${state}.bak"
  keep_backup "${DEMO_DIR}/mediamtx.demo.yml" \
    "${DEMO_DIR}/mediamtx.demo.yml.${state}.bak"
  # 추적 밖 — 이름 목록(-z)과 묶음 한 짝. 묶음은 남긴 목록에서만 만든다
  list_untracked > "${WORK}/untracked.z"
  if [[ ! -e "${DEMO_DIR}/${state}.untracked.tgz" ]]; then
    keep_backup "${WORK}/untracked.z" "${DEMO_DIR}/${state}.untracked.z"
    tar -C "${REPO}" --null -T "${DEMO_DIR}/${state}.untracked.z" \
      -czf "${DEMO_DIR}/${state}.untracked.tgz.part"
    mv -T -- "${DEMO_DIR}/${state}.untracked.tgz.part" \
      "${DEMO_DIR}/${state}.untracked.tgz"
  fi
  # 이름 — -z 목록끼리 바이트로(목록이 없으면 검사 실패)
  assert_same "${WORK}/untracked.z" "${DEMO_DIR}/${state}.untracked.z"
  # 내용 — 묶음의 파일이 지금 트리와 같다
  assert_bundle "${DEMO_DIR}/${state}.untracked.tgz"
}
```

---

## 3. 값 주입

`.env` 는 `root:root 0600` 이고 값 네 줄이다(`.env.example`). 컨테이너는 `.env` 를 읽지
않는다 — `--env-file` 은 상자의 docker CLI 가 root 로 읽는다.

| 줄 | 어디서 오나 |
|---|---|
| `DEMO_HOME` | `/opt/pokeclip-demo` 그대로 |
| `STREAM_KEY_RESOLVE_TOKEN` | auth 의 resolve 전용 출입증. dev auth 쪽과 같은 값이 반영 전에 이미 들어가 있다(사전 확인 단계 — 그때 resolve 200 을 확인한다) |
| `STREAM_KEY_RESOLVE_URL` | `http://<dev EC2 사설 주소>:8082` — 사설 주소여야 한다(공인 주소면 auth 출발지 잠금에 걸려 403) |
| `MTX_API_PASS` | 응답기가 MediaMTX Control API 를 부를 때의 자격. 상자에서 새로 만든다 — 사람이 보지 않는다 |

**R1 — 값 투입은 SSM 대화형 세션 안에서만 한다.** `send-command` 의 인자로 값을 넘기지
않는다 — 명령 이력이 SSM 에 남는다. 세션 로깅이 꺼져 있는지 먼저 본다(켜져 있으면 세션
입력이 S3 · CloudWatch 에 남는다 → 중단).

```bash
aws ssm get-document --name SSM-SessionManagerRunShell \
  --query Content --output text
#   s3BucketName / cloudWatchLogGroupName 이 빈 값이어야 한다
aws ssm start-session --target <instance-id>
```

세션 **안에서**(반영 전 보존 R0 이 끝난 뒤):

```bash
sudo bash          # root 대화형 셸 — 아래는 이 셸 안에서
unset HISTFILE
umask 077
DEMO_DIR=/opt/pokeclip-demo
read -r URL        # http://<dev EC2 사설 주소>:8082 를 친다(비밀 아님 · 저장소에 적지 않음)
if [[ -s "${DEMO_DIR}/.env.pre-T.bak" ]]; then
  {
    grep -E '^(DEMO_HOME|STREAM_KEY_RESOLVE_TOKEN)=' "${DEMO_DIR}/.env"
    printf 'STREAM_KEY_RESOLVE_URL=%s\n' "${URL}"
    printf 'MTX_API_PASS=%s\n' "$(openssl rand -hex 32)"
  } > "${DEMO_DIR}/.env.next"
  chown root:root "${DEMO_DIR}/.env.next"
  chmod 0600 "${DEMO_DIR}/.env.next"
  mv -T "${DEMO_DIR}/.env.next" "${DEMO_DIR}/.env"
else
  echo '멈춤: 반영 전 보존(R0)이 먼저다'
fi
unset URL
exit
```

- 옛 두 줄(`DEMO_PATH` · `SRT_PASSPHRASE`)은 옮기지 않는다 — 반영 전 값은 R0 의
  `.env.pre-T.bak` 에 있다. resolve 출입증 줄은 이미 들어간 줄을 그대로 옮긴다.
- `MTX_API_PASS` 는 `printf` 내장 명령이 받아 파일에만 쓴다 — 화면 · 명령 인자에 나오지
  않는다.
- `.env.next` 를 다 쓴 뒤 이름 바꾸기로 끼우므로 세션이 끊겨도 반쪽 `.env` 가 생기지
  않는다(남은 `.env.next` 는 지우고 다시 친다).

이어서 꼴을 단언한다(값은 내지 않는다).

```bash
# ── 블록: R1 값 단언 — 머리 + 이 본문. 인자 없음. 읽기만 ──
# .env 가 root:root 0600 이고 네 줄이 하나씩 꼴대로 있는지 본다. 값은 내지 않는다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local info line key
  local -A seen=()
  info="$(stat -c '%u:%g %a' -- "${DEMO_DIR}/.env")"
  [[ "${info}" == '0:0 600' ]] \
    || mismatch ".env 가 root:root 0600 이 아니다(${info})"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ -z "${line}" || "${line}" == '#'* ]]; then
      continue
    fi
    key="${line%%=*}"
    case "${key}" in
      DEMO_HOME)
        [[ "${line}" == "DEMO_HOME=${DEMO_DIR}" ]] \
          || mismatch "DEMO_HOME 이 ${DEMO_DIR} 가 아니다"
        ;;
      STREAM_KEY_RESOLVE_URL)
        [[ "${line}" =~ ^STREAM_KEY_RESOLVE_URL=http://[0-9.]+:8082$ ]] \
          || mismatch 'STREAM_KEY_RESOLVE_URL 이 http://<IPv4>:8082 꼴이 아니다'
        ;;
      STREAM_KEY_RESOLVE_TOKEN | MTX_API_PASS)
        [[ "${line}" =~ ^[A-Z_]+=[0-9a-f]{64}$ ]] \
          || mismatch "${key} 가 64자 소문자 hex 가 아니다"
        ;;
      *) mismatch '모르는 줄이 있다 — 옛 DEMO_PATH · SRT_PASSPHRASE 를 빼야 한다' ;;
    esac
    seen["${key}"]=$(( ${seen["${key}"]:-0} + 1 ))
  done < "${DEMO_DIR}/.env"
  for key in DEMO_HOME STREAM_KEY_RESOLVE_URL STREAM_KEY_RESOLVE_TOKEN \
    MTX_API_PASS; do
    [[ "${seen["${key}"]:-0}" == 1 ]] \
      || mismatch "${key} 줄이 하나가 아니다(${seen["${key}"]:-0}개)"
  done
  echo '.env 꼴 단언 통과(값은 내지 않았다)'
}
main "$@"
```

값 자체를 새로 만들어야 하면 `.env.example` 의 생성 명령을 따른다. 출입증은 dev auth 쪽과
같은 값이어야 하므로 상자에서 새로 만들지 않는다.

---

## 4. 반영

옛 공용 passphrase 판 → 계정별 방송 판(T)을 올린다. 단계마다 블록 하나 · 실행 직전 kty
승인이다. 중간에 멈추면 5절(반영 실패 복구)의 그 행으로 간다.

### 4-1. 전제

- **사전 확인(읽기)이 끝났다** — 체크아웃 상태 · 이미지 · 도구 판(2-2 머리의 전제) ·
  디스크 · 시계 · 보안 그룹(SRT 인바운드가 팀 IP/32 만) · dev auth 의 resolve 200.
- **hls.js 가 compose.yml 옆에 있다.** 재생 페이지는 hls.js 를 같은 오리진
  `/hls.min.js` 에서 받는다(MediaMTX 동봉본 의존은 실측에서 실패해 폐기). compose 가
  `./hls.min.js` 를 마운트하므로 없으면 내려받는다.

  ```bash
  sudo curl -fsSL -o /opt/pokeclip-demo/repo/infra/dev-media/hls.min.js \
    https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js
  sudo chown 10002:10002 /opt/pokeclip-demo/repo/infra/dev-media/hls.min.js
  ```

- 지금 서비스 사용자가 없어 시각 제약은 없다. 반영 중 송출 끊김 길이는 기록만 한다.

### 4-2. 반영 차례

| 단계 | 블록 | 하는 일 | 멈추면 |
|---|---|---|---|
| R0 | R0 반영 전 보존 | T 백업이 없음을 단언 → 반영 전 백업 아홉(`pre-T`) | F0 |
| R1 | 3절 값 투입 → R1 값 단언 | T `.env` | F1 |
| R2 | R2 T 체크아웃 | 손 수정을 버리고(패치에 있다) T SHA 로 분리 체크아웃 | F2 · F2′ |
| R3 | R3 응답기 이미지 빌드 | `build ingest-auth` · 이미지 User 단언 | F3 |
| R4 | R4 렌더 | `${DEMO_HOME}` 소유 단언 → render.sh → `chown root:10002` · `chmod 0640` → 소유 · 모드 단언 | F4 |
| R5 | R5 응답기 기동 | 디렉터리 준비 · 거부 목록 확인 → `up -d ingest-auth` → healthy(120초) | F5 |
| R6 | R6 전체 기동 | `up -d`(MediaMTX · edge 재생성) → 판 · 아치 · 좌표 · 권한 오류 · `MTX_` 단언 | F6 |

순서의 까닭.

- **`.env` 를 먼저, 체크아웃을 그다음에.** 옛 compose 는 `${DEMO_HOME}` 하나만 보간해 T
  `.env` 로도 풀리고, T compose 는 T `.env` 가 있어야 풀린다(`:?`).
- **빌드를 렌더 · `up` 앞에.** 빌드가 실패하면 렌더 산출물과 컨테이너를 바꾸기 전에
  멈춘다.
- **응답기 healthy 뒤에 MediaMTX.** 운영 순서(7절)다.
- 반영 중 옛 MediaMTX 는 옛 설정으로 돈다 — 렌더 산출물은 단일 파일 마운트라 이름
  바꾸기로 바뀐 새 파일을 옛 컨테이너가 보지 않는다(추론 — 로컬 실측에서 확인).

```bash
# ── 블록: R0 반영 전 보존 — 머리 + 보존 본문 + 이 본문. 인자 없음 ──
# T 백업이 하나라도 남아 있으면 아무것도 만들지 않고 멈춘다(종료 1) — 접은 뒤 다시 반영할
# 때는 T 백업 처분(11절)이 먼저다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  t_backup_names > "${WORK}/t-left"
  [[ ! -s "${WORK}/t-left" ]] \
    || mismatch "T 백업이 남아 있다 — T 백업 처분이 먼저다:" \
      "$(tr '\n' ' ' < "${WORK}/t-left")"
  preserve_state 'pre-T'
  echo '반영 전 보존 끝 — 백업 아홉 단언 통과'
}
main "$@"
```

```bash
# ── 블록: R2 T 체크아웃 — 머리 + 이 본문. 인자: <T SHA>(40자) ──
# 반영 전 백업이 다 있고 손 수정이 보존한 패치와 같을 때만, 손 수정을 버리고 T SHA 로 분리
# 체크아웃한다. 추적 밖 파일과 겹치면 git 이 멈춘다(F2).
main() {
  (( $# == 1 )) || check_failed '사용법: <T SHA>'
  local -r t_sha="$1"
  local name pre_sha head_sha tracked
  [[ "${t_sha}" =~ ^[0-9a-f]{40}$ ]] || check_failed 'T SHA 는 40자 소문자 hex 다'
  for name in .env.pre-T.bak mediamtx.demo.yml.pre-T.bak pre-T.sha pre-T.head \
    pre-T.docker pre-T.untracked.tgz; do
    [[ -s "${DEMO_DIR}/${name}" ]] || mismatch "반영 전 백업이 없다: ${name} — R0 부터"
  done
  for name in pre-T.status pre-T.worktree.patch pre-T.untracked.z; do
    [[ -e "${DEMO_DIR}/${name}" ]] || mismatch "반영 전 백업이 없다: ${name} — R0 부터"
  done
  pre_sha="$(< "${DEMO_DIR}/pre-T.sha")"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" == "${pre_sha}" ]] \
    || mismatch 'HEAD 가 반영 전 SHA 가 아니다 — 이미 체크아웃했거나 기록과 다르다'
  owner_git diff --binary HEAD > "${WORK}/worktree.patch"
  assert_same "${WORK}/worktree.patch" "${DEMO_DIR}/pre-T.worktree.patch" \
    '손 수정이 보존한 패치와 다르다 — 버리지 않고 멈춘다'
  if ! owner_git cat-file -e "${t_sha}^{commit}" 2> /dev/null; then
    owner_git fetch -q --depth 1 origin "${t_sha}"   # 얕은 클론
  fi
  owner_git reset -q --hard HEAD
  owner_git checkout -q --detach "${t_sha}"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" == "${t_sha}" ]] || mismatch 'HEAD 가 T SHA 가 아니다'
  tracked="$(owner_git status --porcelain --untracked-files=no)"
  [[ -z "${tracked}" ]] || mismatch '추적 파일이 깨끗하지 않다'
  echo "T 체크아웃 끝 — HEAD ${t_sha}"
}
main "$@"
```

```bash
# ── 블록: R3 응답기 이미지 빌드 — 머리 + 이 본문. 인자 없음 ──
# 응답기 이미지는 상자에서 빌드한다. 컨테이너 · 렌더 산출물은 건드리지 않는다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local pre_sha head_sha user
  pre_sha="$(< "${DEMO_DIR}/pre-T.sha")"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" != "${pre_sha}" ]] || mismatch 'HEAD 가 반영 전이다 — R2 가 먼저다'
  live_compose build ingest-auth
  # compose v2 는 build 만 있는 서비스의 이미지를 <프로젝트>-<서비스> 로 이름 짓는다
  user="$(docker image inspect -f '{{.Config.User}}' "${PROJECT}-ingest-auth")"
  [[ "${user}" == '10003:10003' ]] \
    || mismatch "응답기 이미지의 User 가 10003:10003 이 아니다(${user})"
  echo '응답기 이미지 빌드 끝'
}
main "$@"
```

```bash
# ── 블록: R4 렌더 — 머리 + 이 본문. 인자 없음 ──
# render.sh 는 임시 파일 뒤 원자 교체라 실패하면 산출물을 바꾸지 않는다. 산출물은 실행 계정
# 소유 0640 으로 생기므로 그룹 10002(MediaMTX)를 여기서 준다 — 빠뜨리면 R6 이 F6 이 된다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local -r out="${DEMO_DIR}/mediamtx.demo.yml"
  local info uid mode
  # 렌더 자리에 남이 쓸 수 없다 — root 소유 · 그룹 · 기타 쓰기 없음
  info="$(stat -c '%u %a' -- "${DEMO_DIR}")"
  read -r uid mode <<< "${info}"
  [[ "${uid}" == 0 ]] || mismatch "${DEMO_DIR} 가 root 소유가 아니다(${uid})"
  (( (8#${mode} & 8#022) == 0 )) \
    || mismatch "${DEMO_DIR} 에 root 밖의 쓰기 권한이 있다(${mode})"
  env DEMO_HOME="${DEMO_DIR}" "${REPO}/infra/dev-media/render.sh"
  chown root:10002 -- "${out}"
  chmod 0640 -- "${out}"
  info="$(stat -c '%u:%g %a' -- "${out}")"
  [[ "${info}" == '0:10002 640' ]] \
    || mismatch "렌더 산출물이 root:10002 0640 이 아니다(${info})"
  echo '렌더 끝 — root:10002 0640'
}
main "$@"
```

```bash
# ── 블록: R5 응답기 기동 — 머리 + 이 본문. 인자 없음 ──
# 첫 반영이면 디렉터리와 빈 거부 목록을 만든다. 그다음 응답기의 입력을 확인하고 응답기만
# 띄워 healthy 를 기다린다. MediaMTX 는 아직 옛 컨테이너다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  if [[ ! -e "${DEMO_DIR}/ingest-auth" ]]; then
    # 첫 반영 — 빈 거부 목록은 「거부할 토큰 없음」이다
    install -d -m 0750 -o root -g root -- "${DEMO_DIR}/ingest-auth"
    install -d -m 0750 -o root -g "${RESPONDER_ID}" -- "${DENY_DIR}"
    install -m 0640 -o root -g "${RESPONDER_ID}" -- /dev/null "${DENY_FILE}"
    install -d -m 0700 -o "${RESPONDER_ID}" -g "${RESPONDER_ID}" \
      -- "${STATE_DIR}"
  fi
  assert_ready_to_start_ingest_auth
  live_compose up -d ingest-auth
  wait_ingest_auth_healthy
  echo 'ingest-auth healthy — 다음은 R6'
}
main "$@"
```

```bash
# ── 블록: R6 전체 기동 — 머리 + 이 본문. 인자 없음 ──
# 응답기 healthy 를 다시 본 뒤 MediaMTX · edge 를 새로 만든다. 이어서 서버가 쓰는 판을
# 단언한다 — 기동 첫 줄의 판 · 이미지 아치 · compose 의 이미지 좌표 · 기동 로그의 권한
# 오류 0 · 컨테이너 환경의 MTX_ 0(MTX_ 변수는 설정 파일보다 우선해 렌더 단언을 비켜 간다).
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local mtx_id='' image tag arch line want_arch="${DEMO_ARCH:-amd64}"
  local -i i found=0 version=0 denied=0 unsaved=0 mtx_env=0
  wait_ingest_auth_healthy   # 운영 순서 — 응답기 healthy 뒤에만 MediaMTX
  live_compose up -d
  container_id mediamtx mtx_id
  image="$(docker inspect -f '{{.Config.Image}}' "${mtx_id}")"
  live_compose config --images > "${WORK}/images"
  while IFS= read -r line; do
    if [[ "${line}" == "${image}" ]]; then
      found=1
    fi
  done < "${WORK}/images"
  (( found )) || mismatch "도는 이미지가 compose 의 좌표가 아니다: ${image}"
  tag="${image#*:}"
  tag="${tag%%@*}"
  arch="$(docker image inspect -f '{{.Architecture}}' "${image}")"
  [[ "${arch}" == "${want_arch}" ]] \
    || mismatch "이미지 아치가 ${want_arch} 가 아니다(${arch})"
  for (( i = 0; i < 10; i++ )); do
    docker logs "${mtx_id}" > "${WORK}/mtx.log" 2>&1
    version="$(count_lines_with "${WORK}/mtx.log" "MediaMTX ${tag}")"
    (( version == 0 )) || break
    sleep 3
  done
  (( version > 0 )) || mismatch "기동 첫 줄 'MediaMTX ${tag}' 가 없다 — 기동 실패(F6)"
  denied="$(count_lines_with "${WORK}/mtx.log" 'permission denied')"
  unsaved="$(count_lines_with "${WORK}/mtx.log" 'failed to save')"
  (( denied == 0 && unsaved == 0 )) \
    || mismatch "기동 로그의 권한 오류: permission denied ${denied} ·" \
      "failed to save ${unsaved}"
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "${mtx_id}" \
    > "${WORK}/mtx.env"
  while IFS= read -r line; do
    if [[ "${line}" == MTX_* ]]; then
      mtx_env+=1
    fi
  done < "${WORK}/mtx.env"
  (( mtx_env == 0 )) || mismatch "mediamtx 컨테이너에 MTX_ 환경 변수가 ${mtx_env}개"
  echo "전체 기동 끝 — ${tag} · ${arch} · 권한 오류 0 · MTX_ 0"
}
main "$@"
```

- R6 뒤 녹화 쓰기의 권한 오류는 송출이 있어야 드러난다 — 실측 1 의 첫 송출 뒤
  `permission denied` 0 을 한 번 더 본다(6절).
- 판 · 아치 · 좌표 · 권한 오류 가운데 하나라도 어긋나면 실측으로 가지 않고 멈추고 kty —
  T 가 도는 상태라 접는다면 F7 과 같은 길이다.

---

## 5. 반영 실패 복구

박스를 반쯤 바뀐 상태로 두지 않는 것이 목적이다. 롤백 단계 0 은 HEAD 가 반영 전이면
멈추므로, 그 자리는 **반영 취소** 블록이 받는다. HEAD 가 T 라도 컨테이너를 아직 바꾸지
않았으면(F3 · F4) 반영 취소에 HEAD 되돌림(ⓓ)을 더해 받아 T 백업을 만들지 않는다.

### 5-1. 멈춘 자리별 상태 · 복구 진입점

| # | 멈춘 자리 | 그때 상태(HEAD · 트리 · `.env` · 렌더 산출물 · 컨테이너) | 복구 진입점 |
|---|---|---|---|
| F0 | R0 보존 | 반영 전 그대로 · 만든 백업만 | 다시 돌리면 이어진다. 첫머리 단언(T 백업 남음)에서 멈췄으면 접은 뒤 처분을 빠뜨렸다 — T 백업 처분(11절) 뒤 다시. 보존 단언이 어긋나면(종료 1) 반영 전 상태가 이미 바뀌었다 — 반영을 멈추고 kty |
| F1 | R1 값 투입(세션 도중 끊김 · 꼴 단언 실패) | HEAD · 트리 · 렌더 · 컨테이너 = 반영 전(옛 판이 그대로 돈다 — 옛 compose 는 `${DEMO_HOME}` 하나만 보간하고 옛 MediaMTX 는 `.env` 를 다시 읽지 않는다). `.env` = 반영 전 또는 T | 반영 취소 ⓐ → ⓒ. 또는 값을 고쳐 다시 넣고 R2 로 |
| F2 | R2 체크아웃 — git 이 거부(추적 밖 파일과 겹침) | HEAD = 반영 전 · 손 수정은 버려짐(패치에 있다) · `.env` T · 렌더 · 컨테이너 반영 전 | 반영 취소 ⓑ → ⓐ → ⓒ. 겹친 파일은 kty 가 정한 뒤(옮김) R2 를 다시 |
| F2′ | R2 체크아웃 도중 끊김(프로세스가 죽음) | 트리 일부만 T · `.git/index.lock` 이 남을 수 있다 · HEAD 는 대개 반영 전(추론) | kty — 남은 lock · 트리 상태를 보고 정한다(lock 지움은 승인). HEAD 가 T 면 F3 행 |
| F3 | R3 빌드 실패 | HEAD · 트리 = T · `.env` T · 렌더 · 컨테이너 = 반영 전(옛 판이 돈다) | 원인(디스크 · 모듈 · GHCR)을 고쳐 R3 부터. 접으려면 반영 취소 ⓓ → ⓑ → ⓐ → ⓒ(컨테이너를 건드리지 않고 T 백업을 만들지 않는다). 다시 T 로 갈 때는 곧바로 R0 부터 |
| F4 | R4 렌더 가드 실패(종료 1) · 소유 단언 실패 | F3 과 같다 — 렌더 산출물은 그대로(render.sh 가 임시 파일을 지운다) · 새 응답기 이미지는 남는다. 소유 단언에서 멈췄으면 새 산출물이 얹혔다 — `chown` · `chmod` 를 고쳐 R4 의 단언부터 | F3 과 같다 |
| F5 | R5 healthy 시한 초과 | HEAD · 트리 · `.env` · 렌더 = T · `ingest-auth` 만 떠 있고 unhealthy · MediaMTX 는 옛 컨테이너가 옛 설정으로 돈다(추론) | 응답기 로그로 원인을 고쳐 R5 부터. 원인이 거부 목록의 틀린 줄이면 손으로 고치지 않고 틀린 줄 걷기(10절 — 재시작 없음) 뒤 R5 부터. 접으려면 롤백 0 → A → B → C(11절). 다시 T 로 갈 때는 T 백업 처분 → R0 부터 — D 를 쓰지 않는다. 접는 중 B · C 에서 막혀도 D 로 돌아가지 않는다 — kty 판단으로 원인을 고쳐 B · C 를 끝낸다 |
| F6 | R6 `up -d` 실패 · MediaMTX 기동 실패 | T 가 일부 돈다 · 송출이 끊겼을 수 있다 | 롤백 0 → A → B → C. 다시 T 로 — F5 와 같다 |
| F7 | 실측 1 · 6 · 7 실패(그 자리에서 고칠 수 없음) | T 가 돈다 | 롤백 0 → A → B → C. 다시 T 로 — F5 와 같다 |

### 5-2. 반영 취소 블록

F1–F4 — HEAD 가 반영 전이거나, T 라도 컨테이너를 아직 바꾸지 않았을 때다. 단계마다 블록
하나(SSM 명령 하나)다. 추적 밖 파일 · 백업을 지우는 명령이 없고 컨테이너를 건드리지
않는다(옛 판이 계속 돈다). 차례는 ⓓ(F3 · F4 만) → ⓑ(F2 · F3 · F4) → ⓐ → ⓒ 다.

```bash
# ── 블록: 반영 취소 ⓓ — 머리 + 이 본문. 인자 없음. HEAD 되돌림(F3 · F4) ──
# 첫머리에서 넷을 단언한다 — HEAD ≠ 반영 전 · 추적 파일 깨끗 · 렌더 산출물 = 반영 전 ·
# 컨테이너 · 망 이름 = 반영 전. 어긋나면 종료 1 이다(컨테이너를 바꾼 뒤면 롤백으로 간다).
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local pre_sha head_sha tracked
  pre_sha="$(< "${DEMO_DIR}/pre-T.sha")"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" != "${pre_sha}" ]] \
    || mismatch 'HEAD 가 이미 반영 전이다 — ⓓ 를 지났다(5-3)'
  tracked="$(owner_git status --porcelain --untracked-files=no)"
  [[ -z "${tracked}" ]] || mismatch '추적 파일이 깨끗하지 않다'
  assert_same "${DEMO_DIR}/mediamtx.demo.yml.pre-T.bak" \
    "${DEMO_DIR}/mediamtx.demo.yml" \
    '렌더 산출물이 반영 전과 다르다 — 컨테이너를 바꾼 뒤면 롤백(11절)'
  list_docker_names > "${WORK}/docker"
  assert_same "${WORK}/docker" "${DEMO_DIR}/pre-T.docker" \
    '컨테이너 · 망 이름이 반영 전과 다르다 — 롤백(11절)'
  owner_git checkout -q --detach "${pre_sha}"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" == "${pre_sha}" ]] || mismatch 'HEAD 가 반영 전 SHA 가 아니다'
  tracked="$(owner_git status --porcelain --untracked-files=no)"
  [[ -z "${tracked}" ]] || mismatch '추적 파일이 깨끗하지 않다'
  # HEAD 형태 되살림 — 되풀이해도 같다(5-3 의 이어 가기는 이 줄부터)
  restore_head_shape "${DEMO_DIR}/pre-T.head" "${pre_sha}"
  echo 'ⓓ 끝 — 다음은 ⓑ'
}
main "$@"
```

```bash
# ── 블록: 반영 취소 ⓑ — 머리 + 이 본문. 인자 없음. 손 수정 패치 다시 대기(F2 · F3 · F4) ──
# 이미 댄 패치에서 다시 돌리면 apply --check 가 종료 2 다 — 그때는 ⓐ 부터(5-3).
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local pre_sha head_sha
  pre_sha="$(< "${DEMO_DIR}/pre-T.sha")"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" == "${pre_sha}" ]] \
    || mismatch 'HEAD 가 반영 전 SHA 가 아니다 — HEAD 가 T 면 ⓓ 가 먼저다'
  if [[ -s "${DEMO_DIR}/pre-T.worktree.patch" ]]; then
    owner_git apply --check --binary < "${DEMO_DIR}/pre-T.worktree.patch"
    owner_git apply --binary < "${DEMO_DIR}/pre-T.worktree.patch"
  fi
  owner_git diff --binary HEAD > "${WORK}/worktree.patch"
  assert_same "${WORK}/worktree.patch" "${DEMO_DIR}/pre-T.worktree.patch"
  echo 'ⓑ 끝 — 다음은 ⓐ'
}
main "$@"
```

```bash
# ── 블록: 반영 취소 ⓐ — 머리 + 이 본문. 인자 없음. .env 되돌림 ──
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  cp -p -- "${DEMO_DIR}/.env.pre-T.bak" "${DEMO_DIR}/.env"
  assert_same "${DEMO_DIR}/.env.pre-T.bak" "${DEMO_DIR}/.env"
  echo 'ⓐ 끝 — 다음은 ⓒ'
}
main "$@"
```

```bash
# ── 블록: 반영 취소 ⓒ — 머리 + 이 본문. 인자 없음. 확인(읽기만) ──
# 복귀 확인 함수 하나(롤백 단계 C 와 같다)로 반영 전 기록과 견준다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  PRE_SHA="$(< "${DEMO_DIR}/pre-T.sha")"
  readonly PRE_SHA
  assert_back_to_pre_t
  echo 'ⓒ 끝 — 반영 전 기록과 같다'
}
main "$@"
```

### 5-3. 반영 취소 — 멈춘 자리별 상태

어느 단계를 지났는지는 상태로 가린다 — HEAD(T · 반영 전) · `diff --binary HEAD`(빔 · 손
수정 패치와 같음 · 그 밖) · `.env`(T · 반영 전). 다시 돌릴 때는 아래 행이 가리키는
단계부터 돈다. F1 은 손 수정이 그대로라 ⓐ 부터, F2 는 ⓑ 부터다.

| 멈춘 자리 | 상태 | 다시 돌릴 때 |
|---|---|---|
| ⓓ 첫머리 단언(종료 1) | 아무것도 바꾸지 않았다 | 렌더 산출물 · 컨테이너 · 망 이름이 반영 전과 다르면 컨테이너를 바꾼 뒤다 — 롤백 0 → A → B → C(F5 행). HEAD 가 이미 반영 전 SHA 면 ⓓ 를 지났다 — 다음 행 |
| ⓓ 를 지났다 — `checkout` 뒤(HEAD = 반영 전 SHA · 추적 파일 깨끗) | 손 수정 없음 · HEAD 형태는 아직 분리일 수 있다 · `.env` T | ⓓ 를 처음부터 다시 돌리지 않는다 — 첫머리 「HEAD ≠ 반영 전」이 종료 1 로 막는다. 롤백 0 → A → B → C 도 아니다 — 단계 0 이 같은 이유로 멈춘다. HEAD 형태가 기록과 다르면 `restore_head_shape` 한 줄(되풀이해도 같다)만 돈 뒤 ⓑ 부터 |
| ⓑ 를 지났다 — `apply` 뒤(`diff --binary HEAD` = `pre-T.worktree.patch`) | 손 수정이 되살아났다 · `.env` T | ⓑ 를 다시 돌리지 않는다 — 이미 댄 패치에서 `apply --check` 가 종료 2 다. ⓐ 부터 |
| ⓐ 를 지났다(`.env` = `.env.pre-T.bak`) | 반영 전 그대로 | ⓒ 만 |
| ⓒ 어긋남(종료 1) | 반영 전 기록과 다르다 | 차분을 보고 kty. 컨테이너는 내려가지 않았다(옛 판이 돈다) |
| 명령 도중 끊김(반쪽 패치 · `.git/index.lock`) | `diff --binary HEAD` 가 빔도 패치도 아니다 | kty — 롤백 「멈춘 자리별 상태」의 마지막 행과 같은 부류(lock 지움은 승인) |

### 5-4. 다시 T 로 갈 때

접은 뒤 원인을 고쳐 다시 T 로 갈 때는 **R0 부터 새로 반영한다.**

- 반영 취소로 접었으면(F1–F4) T 백업이 없다 — 곧바로 R0 다. R0 는 남은 반영 전 백업이
  지금 상태와 같은지 단언하고 이어 쓴다.
- 롤백 0 → A → B → C 로 접었으면(F5–F7) R0 앞에 T 백업 처분 블록(11절)을 돈다. R0 첫머리
  단언이 처분을 빠뜨린 다시 반영을 반영 앞에서 막는다.
- **롤백 단계 D 는 리허설(실측 8)에서만 쓴다(kty 81차).** 실제 롤백 · 최초 반영 실패 ·
  실측 실패로 접는 중에 B · C 에서 막혀도 D 로 돌아가지 않는다 — kty 판단으로 막힌
  원인을 고쳐 B · C 를 끝내고, T 백업 처분 → R0 부터 다시 반영한다. D 는 단계 A 가 남긴
  T 백업을 되살리는데, 최초 반영 실패에서 그 「T」는 반쪽 상태이거나 고치기 전 판이다.

---

## 6. 실측

반영 뒤 실측의 차례다. 차례는 실측 번호와 다르다 — 해제(3)를 4 · 5 · 8 앞에 두고, 키를
폐기하는 2 를 키를 쓰는 실측의 맨 뒤에 둔다. 박스 명령은 단계마다 실행 직전 승인이다.
시각은 `date` 출력만 쓰고, 토큰 · 연결 id · 주소는 가려서 기록한다.

| 차례 | 실측 | 무엇 |
|---|---|---|
| 1 | 1 · 6 · 7 | 플러그인 송출 · 틀린 passphrase · 무암호 · 비토큰 이름 거부 · SRT 읽기(`m=request`) 거부 · 첫 연결 소요 · 수락 → `is publishing` 간격 / edge 에서 `10.250.0.3:8080` · `10.250.0.2:9997` · `mediamtx:9997` 연결 실패 / 누설 grep(값은 상자 안에서만 쓰고 개수만 낸다) / 첫 송출 뒤 MediaMTX 로그 `permission denied` 0 |
| 2 | 3 | 긴급 절단(8절) → 해제(9절). 착수 때 해제 확인용 가짜 토큰 줄을 덧붙인다(9-1) |
| 3 | 4 | auth 정지(빠른 실패) · 일시 정지(느린 실패) — 새 연결 거부 · 진행 방송 유지 · 회복 |
| 4 | 5 · 5′ | 응답기 healthy 확인 뒤 MediaMTX 재기동 — 동시 재접속 묶음 · 둘을 함께 재시작 |
| 5 | 8 | 롤백 리허설 0 → A → B → C → D → E-1 → E-2(11절) |
| 6 | 10(선택) | 응답기 부재 중 줄과 비는 시간 |
| 7 | 2 | 키 폐기 — rotate(kty 가 직접 실행) → 재페어링 |
| 8 | 9 | 1–5 동안의 응답기 `resolve_ms` 를 이행 전 측정과 견줌 |

**재생 · DVR 확인**(실측 1 의 일부 — 옛 판에서 이어온 확인, `<host>` 는 상자 주소,
`<토큰>` 은 송출 계정의 공개 토큰).

- 매니페스트는 2단계다. `index.m3u8` 은 멀티버리언트 플레이리스트라 LL-HLS 마커가 거기
  없다.

  ```bash
  curl -s "http://<host>:8888/<토큰>/index.m3u8"        # ① 200 + 변형 URI 한 줄
  curl -s "http://<host>:8888/<토큰>/<위에서 얻은 URI>"  # ② 마커
  ```

  ②의 합격 조건 — `EXT-X-PART` · `EXT-X-PART-INF` · `EXT-X-PRELOAD-HINT` ·
  `CAN-BLOCK-RELOAD` 존재 + `EXTINF` 가 4.0 근처. `PART-HOLD-BACK` 은 값을 기록만 한다.
- GOP 는 설정값을 믿지 않고 HLS 디먹서로 잰다 — `key_frame=1` 행의 `pts_time` 간격이
  2.0초여야 한다.

  ```bash
  # 몇 초 뒤 Ctrl-C
  ffprobe -select_streams v -show_frames \
    -show_entries frame=pts_time,key_frame -of csv \
    "http://<host>:8888/<토큰>/index.m3u8"
  ```

- DVR 되감기 — `http://<host>:8888/player.html?p=<토큰>` 에서 마우스 드래그와 키보드
  ←/→ 로 20분 전으로 간다. 그 시점 재생이 30초 이상 유지되고 라이브로 튕기지 않아야
  한다. 인증 팝업은 뜨지 않는다.
- 부하 — `sudo docker stats` 로 CPU · RSS. 나쁘면 임의로 값을 바꾸지 않고 kty 가 정한다.

---

## 7. 운영 순서

### 7-1. 규칙

- **MediaMTX 를 (다시) 띄우기 전에 응답기가 healthy 인지 본다.** 응답기가 없으면
  MediaMTX 의 인증 요청 하나가 약 3–10초를 붙잡아 줄이 쌓인다.
- **응답기 고장은 응답기를 먼저 살려 푼다**(7-2 「응답기 살리기」). MediaMTX 재시작으로
  풀지 않는다 — 진행 중 송출까지 끊겨 한꺼번에 다시 붙으며 같은 줄을 스스로 만든다.
- **MediaMTX 재시작은 최후 수단이고 한 번만 쓴다(되풀이 금지).** 진행 중 송출이 모두
  끊긴다(플러그인 재시도가 5초 뒤 다시 붙인다). 쓰는 때는 8-3 의 갈래가 가리킬 때뿐이다.
- **응답기를 다시 띄우기 전에 거부 목록 파일을 확인한다**(7-4). 응답기를 띄우거나 다시
  시작하는 블록은 모두 `assert_ready_to_start_ingest_auth` 를 먼저 부른다.
- `compose.yml` 의 `depends_on` 은 `up` 의 시작 순서(응답기 먼저)만 맞춘다. 상자
  재부팅 때는 Docker 가 컨테이너를 따로 올려 순서가 보장되지 않는다 — 재부팅 뒤에는
  응답기 healthy 와 거부 목록 파일을 먼저 본다.
- **auth 재배포 운영 합의.** dev auth 가 다시 만들어지는 동안(참고 — `docker restart` 의
  정지 → health 200 약 22초) 새 연결은 모두 거부되고, 진행 중 송출은 무사하다. 사용자가
  생기면 방송 시간 밖 머지로 맞춘다(강제 수단은 아니다). 지금은 사용자가 없어 알리지
  않는다.

### 7-2. 응답기 · MediaMTX 블록

```bash
# ── 블록: 응답기 살리기 — 머리 + 이 본문. 인자 없음 ──
# 응답기가 죽었거나 없을 때(api_auth · 부재). 입력을 확인한 뒤 up -d ingest-auth → healthy.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  assert_ready_to_start_ingest_auth
  live_compose up -d ingest-auth
  wait_ingest_auth_healthy
  echo 'ingest-auth healthy'
}
main "$@"
```

```bash
# ── 블록: 응답기 다시 시작 — 머리 + 이 본문. 인자 없음 ──
# 응답기가 떠 있는데 다시 시작해야 할 때(기록 세대가 지워짐 등 — 8-3). 응답기만 다시
# 시작한다. 그동안 모든 계정의 새 연결 · 재접속이 거부된다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  assert_ready_to_start_ingest_auth
  restart_ingest_auth_only
}
main "$@"
```

```bash
# ── 블록: MediaMTX 최후 재시작 — 머리 + 이 본문. 인자 없음. 한 번만 ──
# 응답기가 healthy 일 때만 MediaMTX 를 다시 시작한다. healthy 가 아니고 원인이 거부 목록의
# 틀린 줄이면 틀린 줄 걷기(10절) 뒤 다시 돌린다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local mtx_id=''
  wait_ingest_auth_healthy
  container_id mediamtx mtx_id
  echo "MediaMTX 재시작: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  docker restart -- "${mtx_id}" > /dev/null
  echo '플러그인 재시도가 다시 붙인다 — 되풀이하지 않는다'
}
main "$@"
```

### 7-3. 로그 읽기

응답기 · MediaMTX 로그는 이렇게 본다(값은 나오지 않는다 — 토큰 · 연결 id 는 공개
식별자).

```bash
sudo docker compose -p dev-media --env-file /opt/pokeclip-demo/.env \
  -f /opt/pokeclip-demo/repo/infra/dev-media/compose.yml \
  logs --since 10m ingest-auth mediamtx
```

| 로그 | 뜻 |
|---|---|
| `ingestauth_publish_allowed` | 인증 통과(passphrase 판정 전) |
| `ingestauth_publish_denied` `reason=denylisted` | 거부 목록에 있는 토큰 |
| `ingestauth_publish_denied` `reason=recheck_wait` `wait=gap` | 차례 거절 — 기억된 토큰의 재확인 칸(4초에 하나)이 닫혀 있다 |
| `ingestauth_publish_denied` `reason=recheck_wait` `wait=yield` | 차례 거절 — 더 오래 기다린 기억 토큰에 양보했다. 한 물결(약 8초) 늦게 붙는다 |
| `ingestauth_publish_denied` `reason=budget_exhausted` | 처리 시간 예산 거절 — auth 가 느릴 때 앞자리가 시간을 다 썼다 |
| `ingestauth_failed_cleared` `scope=all` | 재확인이 쓸 수 있는 값으로 끝나 실패 토큰 기억을 모두 지웠다(auth 회복의 신호) |
| `ingestauth_failed_cleared` `scope=token` | 재확인이 거절(valid:false · 형식 밖)로 끝나 그 토큰만 지웠다 |
| `ingestauth_auth_degraded` · `ingestauth_auth_recovered` | auth 판정 불가 게이지의 켜짐 · 꺼짐 |
| `ingestauth_denylist_error` `reason=unreadable` · `invalid_lines` | 거부 목록을 못 읽음(새 연결 전부 거부) · 틀린 줄(`lines` — 10절) |
| `ingestauth_denylist_changed` | 다시 읽은 파일의 집합이 바뀌었다. 합집합이라 `removed` 는 해제가 아니다 — 판정은 응답기를 다시 시작할 때까지 그대로다 |
| `ingestauth_connlog_error` | 허용 연결 기록 쓰기 실패(새 연결 거부 — 디스크 부족은 여기로만 드러난다) |
| `ingestauth_api_denied` `ip=…` | Control API 요청의 출발지 · 자격이 맞지 않음(8-3 의 api_auth) |
| MediaMTX `connection not found` ERR | cut 이 kick 한 연결이 이미 없어 404 가 났다 — 예상된 것이다. 가득 찬 cut 하나면 약 17,000줄이 난다 |

- **차례 거절은 auth 장애 뒤 회복이 늦는 모양이다.** 끊긴 뒤 300초 안(5초 재시도 간격
  구간)이면 회복은 송출 하나 약 9초, 묶음 약 10–18초다. 최악 약 22초는 차례를 먼저 가진
  거절 토큰 · 계속 실패하는 토큰이 없고 콜드 첫 재확인이 시한(1.125초) 안일 때의 값이다.
  거절 토큰은 하나마다 약 8초를, 계속 실패하는 토큰 p 개는 최악 18 + 8p + 8⌈(p − 1)/2⌉
  초(p = 2 면 약 42초)를, 시한을 넘긴 콜드 첫 재확인은 한 물결(약 8초)을 더한다.
- **긴 간격 구간에서는 `wait=gap` 이 길게 이어질 수 있다.** 끊긴 뒤 300초가 지나면
  재시도 간격이 30 · 60초라 차례 돌림이 서지 않는다. 계속 실패하는 토큰이 먼저 오는
  물결이 이어지면 정상 토큰은 그 기억이 풀릴 때(마지막 판정 불가 뒤 300초 + 한 주기)까지
  거절될 수 있다. 상한은 그 기억 수명이다.
- **셸이 없다.** 응답기 이미지는 셸이 없는 `scratch` 라 `docker exec … sh` 가 안 된다.
  상태는 `docker exec <id> /ingest-auth healthcheck`(실패 까닭이 stderr 에 나온다)와
  `docker inspect -f '{{json .State.Health}}' <id>` 로, 파일은 상자의
  `${DEMO_HOME}/ingest-auth/` 에서 본다. 망 안에서 볼 것이 있으면 별도 컨테이너로 본다
  (`docker run --rm --network container:<id> busybox:1 …`).

**MediaMTX 판을 올릴 때 — 두 404 꼴 대조.** cut 은 Control API 의 404 두 꼴을 가른다 —
연결 없음(Control API 의 JSON 에러 꼴 — 「이미 없음」으로 센다)과 경로 없음(gin 기본
404 — `api_incompatible`). 판을 올리면 새 판에서 두 꼴이 그대로인지 응답기의 망에서 본다.
비번은 `.env` 에서 컨테이너 안으로만 들어가고 curl 은 표준 입력의 설정으로 받는다.

```bash
AUTH_ID="$(sudo docker ps -q \
  --filter label=com.docker.compose.project=dev-media \
  --filter label=com.docker.compose.service=ingest-auth)"
sudo docker run --rm --env-file /opt/pokeclip-demo/.env \
  --network "container:${AUTH_ID}" curlimages/curl:8.10.1 sh -c '
  api=http://10.250.0.2:9997/v3
  printf "user = \"ingest-auth:%s\"\n" "${MTX_API_PASS}" > /tmp/cfg
  for p in srtconns/get/00000000-0000-0000-0000-000000000000 no-such-route; do
    curl -sS -K /tmp/cfg -w " — HTTP %{http_code}\n" "${api}/${p}"
  done'
```

첫 줄이 JSON 에러 본문 + 404, 둘째 줄이 `404 page not found` + 404 면 그대로다. 다르면
판 올림을 멈추고 cut 의 404 판정(`media/internal/mtxctl`)을 먼저 맞춘다.

### 7-4. 거부 목록 · 상태 디렉터리 규약

- **거부 목록**은 `${DEMO_HOME}/ingest-auth/deny/deny-tokens` 다(응답기 안에서
  `/etc/ingest-auth/deny-tokens` — 디렉터리 마운트라 이름 바꾸기로 끼운 파일이 닿는다).
  한 줄에 토큰 하나, `#` 뒤는 주석, 빈 줄 허용. 모드는 응답기(UID 10003)가 읽게 둔다 —
  첫 반영이 `root:10003 0640` 으로 만든다. 못 읽으면 새 연결이 모두 거부된다.
- **일반 파일 · 64 KiB 이하.** 넘거나 일반 파일이 아니면(링크 · FIFO 등) 응답기는 읽기
  실패로 다룬다 — 새 연결이 모두 거부되고, **cut 도 아무것도 끊지 않는다**
  (`denylist_unreadable`).
- **손으로 편집하지 않는다.** 덧붙이기는 8-1 블록(꼴 검사), 빼기는 해제(9절), 틀린 줄은
  틀린 줄 걷기(10절)로만 한다. 손 편집이 다른 줄을 잃어도 합집합이 재시작 때까지 가려
  드러나지 않고, 그 뒤 재시작에서 조용히 풀린다.
- **파일이 없으면 빈 목록이다 — 남는 틈(kty 106차 (가)).** 응답기가 떠 있는 동안은 읽은
  토큰을 합집합으로 기억해 파일을 지워도 차단이 유지된다. 그러나 다시 뜨면 파일부터 다시
  쌓는다. 그래서 파일만 지워진 채 자동 재시작(컨테이너 재시작 정책 · 상자 재부팅)되면
  거부했던 토큰이 신호 없이 다시 붙는다 — 흔적은 응답기 로그의 `publish_allowed` 한
  줄뿐이다. 디렉터리째 없으면 compose 의 긴 꼴 bind(`create_host_path: false`)로 응답기
  기동이 실패해 드러난다. 사람이 응답기를 띄우는 블록은 파일을 먼저 확인한다(7-1).
  파일이 없으면 띄우지 않는다. 응답기가 떠 있으면 다시 시작하지도 않는다 — 합집합이 아직
  차단을 쥐고 있다. 남은 사본(`deny-tokens.*`) 하나를 그대로 되살리지 않는다 — 긴급 절단의
  덧붙이기(8-1)는 사본을 남기지 않아, 마지막 사본 뒤에 막은 토큰(유출 키 포함)이 어느
  사본에도 없다. 그 사본으로 띄우면 그 토큰이 신호 없이 다시 붙는다. 되살릴 내용(가장
  최근 사본 + 그 뒤 SSM 명령 기록에 남은 덧붙이기 토큰 − 그 뒤 해제한 토큰)은 kty 가
  정하고, 정해진 뒤에 띄운다.
- 해제 · 걷기 · 되돌림의 사본 · 새 파일은 거부 목록 옆에 남는다
  (`deny-tokens.copy.*` · `.unblock.*` · `.gathered.*` · `.revert.*` ·
  `.walk-copy.*` · `.walk.*`). 토큰은 공개 식별자라 비밀이 아니다. 기록으로 둔다.
- **상태 디렉터리**(`${DEMO_HOME}/ingest-auth/state` — 허용 연결 기록)는 `10003:10003
  0700` 이다. 첫 반영(R5)이 만든다 — 없으면 docker 가 root 소유로 만들어 응답기가
  unhealthy 가 된다.
- **기록 세대 파일은 손으로 지우거나 옮기지 않는다.** 세대 파일은 0600 이고 심볼릭
  링크면 안 된다 — 어기면 기동이 실패하고, 운영 중 지우거나 바꿔 끼우면 새 publish 가
  401(`connlog_unwritable`)이다. 직전 세대(`.1`) 자리의 링크 · FIFO · 큰 파일은 기동을
  막지 않고 cut 이 그대로 따라 읽는다 — 세대 자리에 링크 · FIFO · 큰 파일을 두지 않는다.
  지워졌으면 응답기를 다시 시작한다(8-3 `connlog_unreadable`).

---

## 8. 긴급 절단

어떤 토큰의 송출을 지금 끊고 다시 붙지 못하게 한다. **차례는 늘 덧붙이기 → cut 이다** —
바꾸지 않는다. 긴급 절단은 해제 준비 · 승인 대기를 기다리지 않는다(9절).

### 8-1. 덧붙이기

값이 토큰 꼴(26자 · `0-9A-HJKMNP-TV-Z`)인지 보는 검사와 덧붙이기가 한 블록이다. 꼴이
아니면 덧붙이지 않고 종료 2 다. 틀린 줄은 판정에서 건너뛰지만 healthz 를 실패시켜 healthy
를 기다리는 블록을 모두 멈추게 한다 — 들이지 않는 것이 먼저다. 설명이 필요하면 둘째
인자로 준다 — 블록이 `# ` 를 붙인 주석 줄을 토큰 줄 앞에 쓴다. 효과는 곧바로 난다(응답기가
publish 인증마다 파일을 다시 읽는다).

```bash
# ── 블록: 덧붙이기 — 머리 + 이 본문. 인자: <토큰> ['<설명 한 줄>'] ──
# 꼴이 아니면 덧붙이지 않고 종료 2 다. 파일을 지웠다가 다시 만드는 편집은 쓰지 않는다.
main() {
  (( $# == 1 || $# == 2 )) || check_failed "사용법: <토큰> ['<설명>']"
  local -r token="$1" note="${2:-}"
  # 값은 메시지에 싣지 않는다 — 토큰 자리에 비밀을 잘못 붙였을 수 있다
  [[ "${token}" =~ ${TOKEN_RE} ]] \
    || check_failed '토큰 꼴(26자 · 0-9A-HJKMNP-TV-Z)이 아니다 — 덧붙이지 않았다'
  [[ "${note}" != *$'\n'* ]] || check_failed '설명은 한 줄이다 — 덧붙이지 않았다'
  [[ -f "${DENY_FILE}" && ! -L "${DENY_FILE}" ]] \
    || mismatch "거부 목록 파일이 없다(7-4): ${DENY_FILE}"
  if [[ -s "${DENY_FILE}" && -n "$(tail -c 1 -- "${DENY_FILE}")" ]]; then
    printf '\n' >> "${DENY_FILE}"   # 끝 줄바꿈 없는 마지막 줄에 붙지 않게
  fi
  if [[ -n "${note}" ]]; then
    printf '# %s\n%s\n' "${note}" "${token}" >> "${DENY_FILE}"
  else
    printf '%s\n' "${token}" >> "${DENY_FILE}"
  fi
  echo '덧붙임 — 이어서 cut(8-2)'
}
main "$@"
```

### 8-2. cut

덧붙이기를 마친 뒤 돌린다. 응답기 안의 `cut` 이 거부 목록에 토큰이 있는지 확인하고, 허용
연결 기록 · 연결 목록에서 그 토큰의 연결을 모아 끊은 뒤 사라졌는지 확인한다. 출력은 한
줄(`ingestauth_cut_completed` · `ingestauth_cut_incomplete`)이고 종료 코드는 cut 의 것이다
(머리의 규약이 아니다).

```bash
sudo docker compose -p dev-media --env-file /opt/pokeclip-demo/.env \
  -f /opt/pokeclip-demo/repo/infra/dev-media/compose.yml \
  exec -T ingest-auth /ingest-auth cut '<토큰>'
```

| 종료 | 뜻 |
|---|---|
| 0 | 끝까지 끊었다 — 남은 연결 0 · 두 판독 모두 기록 밖 30초 안 연결 0 |
| 1 | 미완 — 출력의 `reason` 으로 8-3 의 갈래를 탄다 |
| 2 | 시작하지 않았다 — 거부 목록에 그 토큰이 없다(`not_denied`) · 사용법 · 설정. 끊은 것 없음 |

출력 필드의 뜻.

- `from_log` · `from_list` · `from_unmapped` — 끊을 연결을 모은 세 갈래(허용 연결 기록 ·
  연결 목록에서 경로가 그 토큰 · 기록 밖이면서 30초가 넘은 판독 중 연결). `from_unmapped`
  는 토큰과 무관하게 잡는 갈래다 — 기록이 담기 전에 생긴 연결이다.
- `unmapped_young` — 기록 밖이면서 30초가 안 된 판독 중 연결 수. 0 이 아니면 미완이다.
- `kicked` · `already_gone` — 끊은 수 · 이미 없던 수(404). `covered_s` — 기록이 담은 기간.
- **cut 뒤 그 토큰의 `is publishing` 한 줄** — kick 시점에 등록 직전이던 연결이 한 번
  등록될 수 있다(순간 등록). 곧바로 `closed` 가 따르고 송출은 이어지지 않는다. 「cut 뒤
  등록」으로 오독하지 않는다. 해제 뒤의 `is publishing` 은 정상 재송출이다.
- MediaMTX 로그의 `connection not found` ERR 은 예상된 것이다(7-3).

### 8-3. cut 종료 사유별 대응

종료 1 이면 출력의 `reason` 으로 가른다. 갈래 밖으로 나가지 않는다. 「MediaMTX 최후
재시작」은 7-2 블록이고 한 번만 쓴다 — 유출 키의 진행 중 송출을 바로 끊는 수단은 이것뿐이다.

| reason | 대응 |
|---|---|
| `verify_timeout` · 남은 연결 | cut 을 한 번 더 돌린다 |
| `connlog_window_short` | 기록 밖이면서 30초가 안 된 판독 중 연결이 있다. 30초 뒤 cut 을 다시 돌린다. 기록이 처음 열린 뒤 30초 안(상태 디렉터리를 새로 만든 직후)에만 생긴다 |
| `denylist_unreadable` | 거부 목록을 못 읽었다 — **아무것도 끊지 않았다.** 파일의 권한 · 마운트 · 꼴(일반 파일 · 64 KiB — 7-4)을 고친 뒤 다시 돌린다. 그동안 새 연결은 모두 거부된다. 틀린 줄은 읽기 실패가 아니다 — 판정에서 건너뛰고 healthz 만 실패한다(10절). 해제 적용 뒤라면 살아 있는 파일의 모드가 틀린 것이다 — 사본 되돌림(9-6) 뒤 다시 돌린다 |
| `connlog_unreadable` | 기록 디렉터리를 못 읽었거나 줄이 하나도 없다 — 목록 갈래(등록된 연결)는 이미 끊었다. 권한 · 마운트를 고친 뒤 다시 돌린다. 줄이 하나도 없으면(시작 표시도 없음) 상태 디렉터리가 지워진 것이다 — 「응답기 다시 시작」(7-2)으로 새 세대와 시작 표시를 만든 뒤 다시 돌린다. 그동안 새 연결은 기록 쓰기 실패로 거부된다 |
| `api_auth` | Control API 가 401 이다 — MediaMTX 가 api 인증을 응답기에 묻다 실패했다. 응답기 로그 `ingestauth_api_denied` 와 MediaMTX 로그를 본다(7-3). ① `api_denied` 0 ∧ MediaMTX 로그에 `cannot assign requested address` → MediaMTX 쪽 임시 포트 고갈이다(큰 cut 직후 · 토큰 꼴 송출 시도가 몰릴 때). 60초 뒤 cut 을 한 번 다시 돌린다 → 또 나면 MediaMTX 최후 재시작(되풀이 금지) → cut 으로 확인. 커널 값은 조정하지 않는다(kty 106차). ② `api_denied` 가 0 이 아니면 그 `ip` 를 compose 의 `INGEST_AUTH_LISTEN` 주소와 견준다 — 다르면 출발지 설정이 틀렸다(kty). ③ 둘 다 아니면 응답기 부재 · 고장이다 — 「응답기 살리기」(7-2) 뒤 cut 을 다시 돌린다. MediaMTX 재시작으로 풀지 않는다 |
| `api_unreachable` | Control API 와의 왕복이 실패했다 — 무응답 · 전송 실패 · 401 · 404 밖의 HTTP 상태(500 등). 먼저 응답기가 healthy 인지 본다 — 아니면 응답기를 살린 뒤 **cut 부터 다시** 돌린다. 그다음 출력의 `kicked` · `already_gone` 과 stderr 줄(`ingest-auth: cut api_unreachable: mtxctl: <list·get·kick> — <까닭>`)의 까닭으로 가른다. ① `timeout`(또는 stderr 가 `context deadline exceeded` — 끊은 뒤 확인 단계에서 시한에 걸림)이고 `kicked + already_gone` 이 0 보다 크다 — cut 자신의 시한(2분)에 걸린 큰 cut 이다. Control API 인증은 돌고 있었다 — cut 을 한 번 더 돌린다. ② `timeout` 이고 0 이거나 `unreachable`(연결 거부 등) — Control API 가 답하지 않는다. MediaMTX 고장이다 — 응답기 healthy 를 확인한 뒤 MediaMTX 최후 재시작(되풀이 금지) → cut 으로 확인. ③ `HTTP <상태>`(500 등) — Control API 는 답했지만 처리하지 못했다. cut 을 한 번 다시 돌린다 → 같으면 `kicked` 를 보고 응답기 healthy 를 확인한 뒤 MediaMTX 최후 재시작 1회(되풀이 금지) → cut 으로 확인하고 kty 에 보고한다. 어느 갈래든 재시작 뒤 그 토큰의 재접속은 거부 목록이 막는다. ①–③ 밖의 꼴이면 멈추고 kty |
| `api_incompatible` | cut 이 아는 꼴이 아닌 Control API 응답이다(경로 없음 404 · 해석 실패 · 여러 페이지). **끊었는지 모른다 — 출력의 `kicked` 를 본다**(kick 한 뒤에도 날 수 있다). 응답기 healthy 를 확인한 뒤 MediaMTX 최후 재시작 1회(되풀이 금지) → MediaMTX 판 · 경로 호환을 고친 뒤(7-3 두 404 꼴) cut 으로 확인한다 |
| `internal_error` | cut 의 내부 오류(분류 밖 결과)다. `api_incompatible` 과 같은 처방(`kicked` 확인 → 응답기 healthy → MediaMTX 최후 재시작 1회)에 kty 보고를 더한다 |
| 종료 2 `not_denied` | 거부 목록에 그 토큰이 없다 — 아무것도 끊지 않았다. 덧붙이기(8-1) 뒤 다시 돌린다. 덧붙였는데 이것이면 해제 · 되돌림 적용의 교체 구간에 줄이 사라진 것이다 — 다시 덧붙인다 |

- 플러그인 재시도 뒤에는 MediaMTX 재시작이 「전 방송 종료」가 아니다 — 전 방송이 끊기고
  5초 뒤 일제히 다시 붙는다. 상자의 HLS 창(DVR)은 송출이 끊기면 처음부터 다시 쌓여
  시청자에게 되감기 공백이 생긴다(추론).
- 해제 전까지 끊긴 토큰의 플러그인은 65분 동안 5–60초 간격으로 거부 목록에 부딪힌다.
  무해하다 — resolve 호출이 0 이다.

---

## 9. 해제

원인이 풀렸고 키가 새지 않은 토큰을 다시 허용한다. **키가 샜거나 판단할 수 없으면 차단을
유지한다**(9-7). 차례 — 해제 준비(1–5) → 판정 · 승인(6) → 해제 적용(7–8) → 동작 확인(9).

### 9-1. 셈의 단위와 시험 줄

- **셈은 유효 토큰 집합이다.** 줄마다 `#` 뒤를 버리고 앞뒤 공백(C 로캘 `[[:space:]]` 의
  여섯 — 스페이스 · 탭 · LF · VT · FF · CR)을 걷은 값이 토큰이다. 빈 줄은 건너뛰고 같은
  토큰이 여러 줄이면 하나로 센다. 토큰 꼴이 아닌 값 · NUL 이 든 줄은 틀린 줄이다. 줄
  수로 세지 않는다 — 중복 · 주석 · 틀린 줄이 있으면 줄 수와 집합 크기가 다르다. 머리의
  `deny_scan` 이 이 규칙이고, 응답기와 같은 결과를 내는지 0절의 줄 문법 대조가 본다.
- **해제 확인용 시험 줄.** 「남은 토큰 거부」를 보려면 거부 목록에 대상 말고 줄이 하나
  있어야 한다. 실측 3 착수 때 덧붙이기(8-1)로 토큰 꼴의 무작위 값을 덧붙인다 — 어떤
  계정의 키도 아니다. 설명 인자에 「해제 확인용 시험 줄 — 어떤 계정의 키도 아님 ·
  `date` 시각」을 준다. 덧붙이기 전 시도는 `reason=not_found`, 뒤는 `reason=denylisted`
  다. 남겨 둔다 — 다음 해제의 확인에 쓰고, 거부 목록에 있는 동안 resolve 0 이라 무해하다.

### 9-2. 해제 준비 (1–5)

1. **지우기 전 사본** — `deny-tokens.copy.<시각>`(`cp -p` · 덮어쓰지 않는 이름).
1. **새 파일** — 사본에서 대상 토큰의 줄을 모두 뺀 `deny-tokens.unblock.<시각>`. 모드 ·
   소유는 원본과 같게 지정한다(응답기가 읽어야 한다). 살아 있는 파일은 바꾸지 않는다.
1. **집합 단언(주석 전)** — 대상이 사본의 집합에 있다 · 새 파일의 집합 = 사본의 집합 −
   {대상} · 두 파일에 틀린 줄 0 · 대상의 줄 밖의 줄은 차례 · 바이트까지 같다 · 응답기가
   새 파일을 읽는다. 사본에 틀린 줄이 있으면 여기서 늘 종료 1 이다 — 틀린 줄 걷기(10절)를
   먼저 돈 뒤 준비부터 다시 한다.
1. **절단 사유 주석** — 새 파일 끝에 `#` 한 줄(대상 토큰 · 사유와 비유출 판정 근거 ·
   시각). 응답기는 주석을 읽지 않는다.
1. **최종 새 파일에 다시 단언** — 3 과 같은 함수로, 사본 뒤에 `#` 한 줄만 더해졌는지 본다.
   주석이 `#` 없이 붙어 틀린 줄이 되거나 앞 줄과 붙었으면 종료 1 이다.

```bash
# ── 블록: 해제 준비 — 머리 + 이 본문. 인자: <대상 토큰> '<사유 · 비유출 판정 근거>' ──
# 살아 있는 파일(deny-tokens)은 바꾸지 않는다. 사본과 새 파일을 같은 디렉터리에 만들고 그
# 이름을 낸다 — 해제 적용 · 되돌림 준비의 인자다.
main() {
  (( $# == 2 )) || check_failed "사용법: <대상 토큰> '<사유>'"
  local -r target="$1" reason="$2"
  local stamp copy new
  [[ "${target}" =~ ${TOKEN_RE} ]] || check_failed '대상이 토큰 꼴이 아니다'
  [[ -n "${reason}" && "${reason}" != *$'\n'* ]] \
    || check_failed '사유는 비지 않은 한 줄이다'
  stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  copy="${DENY_FILE}.copy.${stamp}"
  new="${DENY_FILE}.unblock.${stamp}"
  [[ ! -e "${copy}" && ! -e "${new}" ]] || mismatch '같은 이름이 있다 — 1초 뒤 다시'
  # 1 지우기 전 사본
  cp -p -- "${DENY_FILE}" "${copy}"
  # 2 새 파일 — 대상의 줄을 모두 뺀다. 모드 · 소유는 원본(사본)과 같게 지정한다
  deny_scan "${copy}" "${WORK}/prep" "${target}"
  cp -- "${WORK}/prep.kept" "${new}"
  chown --reference="${copy}" -- "${new}"
  chmod --reference="${copy}" -- "${new}"
  printf '사본: %s\n새 파일: %s\n' "${copy}" "${new}"
  # 3 집합 단언(주석 전)
  assert_unblock_files "${copy}" "${new}" "${target}" plain
  # 4 절단 사유 주석 한 줄
  printf '# 해제 %s · %s · %s\n' "${target}" "${reason}" "${stamp}" >> "${new}"
  # 5 최종 새 파일에 다시 단언
  assert_unblock_files "${copy}" "${new}" "${target}" commented
  echo '해제 준비 끝 — 살아 있는 파일은 그대로다. 다음은 판정 · 승인(9-3)'
}
main "$@"
```

### 9-3. 판정 · 승인 (6)

- 비유출 판정 · 원인 해소 확인 · kty 승인. **판단할 수 없거나 유출이면 새 파일만 지우고
  멈춘다** — 살아 있는 파일이 그대로라 차단이 유지된다(`sudo rm -- <새 파일>`).
- **긴급 절단이 늘 먼저다.** 승인을 기다리는 동안에도 긴급 절단(덧붙이기 → cut)은
  미루지 않는다. 끼면 적용의 재확인이 종료 1 로 멈춘다 — 그때는 준비부터 다시 한다(새
  사본이 그 줄을 담는다).

### 9-4. 해제 적용 (7–8)

실행 직전 kty 승인. **이 블록이 도는 동안에도 긴급 절단(덧붙이기 → cut)은 미루지
않는다.** 블록은 SSM 명령 하나라 끝날 때까지(재시작 · healthy 대기 — 최대 120초) 출력이
보이지 않고, 운영자는 어느 단계인지 알 수 없다. 덧붙인 줄을 잃는 것은 재확인부터 이름
바꾸기까지(7 — 수 초)의 순간뿐이다 — 그 사이에 덧붙인 줄은 옛 파일과 함께 사라진다.
cut 이 그 순간이 지난 뒤에 돌면 첫 확인이 종료 2(`not_denied` — 8-3)로 드러낸다. 그러나
덧붙이기와 cut 이 둘 다 그 순간 안에 끝나면 cut 은 옛 파일을 보고 종료 0 이라 드러나지
않는다. 그래서 **이 블록이 도는 동안 긴급 절단을 했으면 블록이 끝난 뒤 그 토큰으로 cut 을
한 번 더 돌린다** — 블록이 끝나면 새 파일만 보이므로, 줄이 사라졌으면 반드시 종료 2 다.
종료 2 면 다시 덧붙이고 cut 을 다시 돌린다. 그 전까지(재시작부터 다시 돈 cut 까지 — 수
초에서 2분) 그 토큰이 다시 붙을 수 있다 — 한계로 둔다(kty 107차). 이름 바꾸기 뒤에
덧붙인 줄은 새 파일에 남고, healthy 에 실패해 사본 되돌림(9-6)으로 가면 되돌림이 그 줄을
옮겨 붙인다.

7. 살아 있는 파일이 사본과 바이트까지 같은지 다시 본다(그사이 덧붙은 줄이 없다). 최종 새
   파일에 5 의 단언을 한 번 더 건다. 새 파일을 이름 바꾸기로 끼우고 `ingest-auth` 하나만
   다시 시작한다 — MediaMTX 는 건드리지 않는다.
8. 120초 안에 healthy 인지 본다. 넘기면 9-6 이다.

```bash
# ── 블록: 해제 적용 — 머리 + 이 본문. 인자: <대상 토큰> <사본> <새 파일> ──
# 사본 · 새 파일은 해제 준비가 낸 이름이다. 실행 직전 kty 승인.
main() {
  (( $# == 3 )) || check_failed '사용법: <대상 토큰> <사본> <새 파일>'
  local -r target="$1" copy="$2" new="$3"
  [[ "${target}" =~ ${TOKEN_RE} ]] || check_failed '대상이 토큰 꼴이 아니다'
  [[ "${copy}" == "${DENY_FILE}".copy.* \
    && "${new}" == "${DENY_FILE}".unblock.* ]] \
    || check_failed '사본 · 새 파일은 해제 준비가 낸 이름이다'
  # 7 재확인 — 그사이 덧붙은 줄(긴급 절단)이 있으면 멈춘다
  assert_same "${DENY_FILE}" "${copy}" \
    '살아 있는 파일이 사본과 다르다 — 그사이 덧붙은 줄이 있다. 준비부터 다시'
  assert_unblock_files "${copy}" "${new}" "${target}" commented
  swap_deny_file "${new}"
  # 8 응답기만 다시 시작 → healthy(120초)
  restart_ingest_auth_only
  echo '해제 적용 끝 — 다음은 동작 확인(9-5)'
}
main "$@"
```

### 9-5. 동작 확인 (9)

넷을 본다(로그는 7-3). 판정은 동작으로 한다 — `ingestauth_denylist_changed` 는 판정에 쓰지
않는다(기동 첫 읽기에 나는지 정하지 않았고, 빈 집합이면 바뀐 것이 없어 나지 않는다).

- **대상 허용** — 대상 토큰의 다음 송출이 `publish_allowed` · `is publishing` 이다. 플러그인
  재시도가 붙이거나, 재시도를 그만뒀으면 스트리머가 「다시 연결」을 누른다. 스트리머가
  송출하지 않으면 시도 도구(남겨 둔 ffmpeg 이미지의 SRT 송출 한 번 — 라벨
  `com.pokeclip.bench=<이름>` · `--rm`)로 대상 토큰을 한 번 시도해 응답기
  `publish_allowed`(인증 통과 — passphrase 판정 전)로 갈음한다. 시도 도구는 passphrase 를
  몰라 그 연결은 passphrase 판정에서 끊긴다 — 정상이다.
- **남은 토큰 거부** — 새 파일의 집합에 남은 토큰마다 시도 한 번이 `reason=denylisted` 다.
  남은 집합이 비면(마지막 토큰을 풀었으면) 시도할 것이 없다.
- **무접촉** — 다른 진행 송출이 재시작 창 내내 이어진다(재생 · MediaMTX 로그에 그 경로의
  `closed` 없음). MediaMTX 시작 시각은 적용 블록이 앞뒤로 견줬다.
- **재시작 창** — 적용 블록이 낸 「재시작 명령」 · 「healthy」 두 시각의 차다. 그동안은
  응답기 부재라 모든 계정의 새 연결 · 재접속이 거부된다. 실측 3 의 값: (실측 뒤 적는다).
  지금은 서비스 사용자가 없어 따로 알리지 않는다.
- `ingestauth_denylist_changed` 가 나면 그 `denied` 를 참고로 적는다(판정에는 쓰지
  않는다).

### 9-6. 적용 뒤 실패와 사본 되돌림

healthy 시한 초과 · 대상이 허용되지 않음 · 남은 토큰이 허용됨 · 다른 진행 송출이 끊김
가운데 하나면 멈춘다. 그때 살아 있는 파일은 최종 새 파일이고 응답기는 다시 시작했다(또는
unhealthy).

- 그동안의 긴급 절단도 덧붙이기 → cut 차례 그대로다. 덧붙이기는 곧바로 효과가 있다(응답기가
  뜰 때 읽는다 — 재시작 창에는 새 연결이 응답기 부재로 모두 거부된다). **cut 은 먼저
  시도한다** — healthz 만 실패하는 unhealthy 에서는 api 인증이 돈다. 종료 1 이면 8-3 으로
  가른다 — `api_auth` 면 응답기를 살린 뒤, `denylist_unreadable`(살아 있는 파일의 모드가
  틀림)이면 사본 되돌림 뒤 다시 돌린다.

| 실패 | 처방 |
|---|---|
| 남은 토큰이 허용됨 · healthy 시한 초과 | 사본 되돌림(아래 — 준비 → 적용 · 응답기만 재시작 · healthy) → 사본의 토큰과 그사이 덧붙은 토큰이 모두 `reason=denylisted` 인지 시도. 허용된 토큰의 연결이 이미 붙었으면 cut 한다(먼저 시도). 되돌려도 unhealthy 면 파일 밖 원인이다 — 운영 순서(7절)대로 응답기를 살리고 kty |
| 대상이 허용되지 않음 | 차단이 유지된 쪽이라 급하지 않다. 스트리머가 없어 가려지지 않으면 시도 도구 확인(9-5)으로 가른다. 원인(새 파일에 대상이 남음 · 응답기가 다시 시작하지 않음)을 본 뒤 준비부터 다시 한다 |
| 다른 진행 송출이 끊김 · MediaMTX 가 다시 시작됨 | 「응답기만」이 깨졌다 — 재시작 명령이 MediaMTX 까지 건드렸다. 플러그인 재시도가 다시 붙인다(전 방송 수 초 끊김). 기록하고 명령 꼴을 고친다(kty) |

**사본 되돌림** — 해제처럼 준비와 적용으로 나눈다. 모음과 이름 바꾸기 사이에 긴급 쓰기가
끼면 그 줄이 교체와 함께 사라지고, 재시작이 합집합을 파일에서 새로 쌓아 그 토큰이 다시
허용되기 때문이다. 덧붙은 줄을 옮기지 않고 사본만 끼우면 그 절단이 조용히 풀린다.

1. **되돌림 준비 — 모음.** 살아 있는 파일을 `deny-tokens.gathered.<시각>` 으로 남긴다(모은
   시점의 사본). 그것이 최종 새 파일로 시작하는지 본다 — 아니면 멈추고 kty.
1. **되돌릴 파일.** 사본을 `cp -p` 로 `deny-tokens.revert.<시각>` 에 만들고, 모은 사본에서
   최종 새 파일 뒤에 덧붙은 줄(재시작 창의 긴급 절단)을 그 끝에 옮겨 붙인다.
1. **단언.** 되돌릴 파일의 집합 = 사본의 집합 ∪ 덧붙은 토큰 · 틀린 줄 0 · 사본의 줄은
   차례 · 바이트까지 같다 · 응답기가 읽는다.
1. **되돌림 적용(실행 직전 승인).** 살아 있는 파일이 모은 사본과 바이트까지 같은지 다시
   본다 — 다르면 종료 1 이고 아무것도 바꾸지 않는다(그사이 덧붙은 줄이 있다 — 1 부터 다시,
   새 모음이 그 줄을 담는다). 같으면 끼우고 `ingest-auth` 하나만 다시 시작 → healthy.

```bash
# ── 블록: 되돌림 준비 — 머리 + 이 본문. 인자: <사본> <새 파일>(해제 준비가 낸 이름) ──
# 살아 있는 파일은 바꾸지 않는다. 모은 사본과 되돌릴 파일의 이름을 낸다 — 되돌림 적용의 인자다.
main() {
  (( $# == 2 )) || check_failed '사용법: <사본> <새 파일>'
  local -r copy="$1" new="$2"
  local stamp gather revert size
  stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  gather="${DENY_FILE}.gathered.${stamp}"
  revert="${DENY_FILE}.revert.${stamp}"
  [[ ! -e "${gather}" && ! -e "${revert}" ]] || mismatch '같은 이름이 있다 — 1초 뒤 다시'
  # 1 모음 — 살아 있는 파일을 모은 시점의 사본으로 남긴다
  cp -p -- "${DENY_FILE}" "${gather}"
  printf '모은 사본: %s\n' "${gather}"
  size="$(stat -c '%s' -- "${new}")"
  head -c "${size}" -- "${gather}" > "${WORK}/gather.head"
  assert_same "${new}" "${WORK}/gather.head" \
    '모은 사본이 최종 새 파일로 시작하지 않는다 — 멈추고 kty'
  tail -c "+$(( size + 1 ))" -- "${gather}" > "${WORK}/added"
  # 2 되돌릴 파일 — 사본 + 덧붙은 줄. 모드 · 소유는 사본과 같다
  cp -p -- "${copy}" "${revert}"
  if [[ -s "${copy}" && -n "$(tail -c 1 -- "${copy}")" ]]; then
    printf '\n' >> "${revert}"   # 사본의 끝 줄바꿈이 없으면 덧붙은 첫 줄이 붙지 않게
  fi
  cat -- "${WORK}/added" >> "${revert}"
  printf '되돌릴 파일: %s\n' "${revert}"
  # 3 단언
  assert_revert_file "${copy}" "${WORK}/added" "${revert}"
  echo '되돌림 준비 끝 — 살아 있는 파일은 그대로다. 다음은 승인 · 되돌림 적용'
}
main "$@"
```

```bash
# ── 블록: 되돌림 적용 — 머리 + 이 본문. 인자: <모은 사본> <되돌릴 파일> ──
# 두 이름은 되돌림 준비가 낸 것이다. 실행 직전 kty 승인.
main() {
  (( $# == 2 )) || check_failed '사용법: <모은 사본> <되돌릴 파일>'
  local -r gather="$1" revert="$2"
  local bad
  [[ "${gather}" == "${DENY_FILE}".gathered.* \
    && "${revert}" == "${DENY_FILE}".revert.* ]] \
    || check_failed '모은 사본 · 되돌릴 파일은 되돌림 준비가 낸 이름이다'
  # 4 재확인 — 그사이 덧붙은 줄이 있으면 멈춘다
  assert_same "${DENY_FILE}" "${gather}" \
    '살아 있는 파일이 모은 사본과 다르다 — 그사이 덧붙은 줄이 있다. 준비부터 다시'
  deny_scan "${revert}" "${WORK}/apply"
  bad="$(< "${WORK}/apply.bad")"
  [[ "${bad}" == 0 ]] || mismatch "되돌릴 파일에 틀린 줄이 ${bad}개"
  assert_responder_may "${revert}" 4
  swap_deny_file "${revert}"
  restart_ingest_auth_only
  echo '되돌림 적용 끝 — 사본의 토큰 · 덧붙은 토큰이 모두 거부되는지 시도한다'
}
main "$@"
```

- 되돌림 적용 블록이 도는 동안에도 긴급 절단은 미루지 않는다(9-4 와 같다). 덧붙인 줄을
  잃는 것은 재확인부터 이름 바꾸기까지(수 초)의 순간뿐이다. 블록이 도는 동안 긴급 절단을
  했으면 블록이 끝난 뒤 그 토큰으로 cut 을 한 번 더 돌리고, 종료 2 면 다시 덧붙이고 cut
  을 다시 돌린다(9-4 — kty 107차).
- 편집 뒤 응답기가 내는 `denylist_changed removed` 는 해제가 아니다 — 판정은 다시 시작할
  때까지 그대로다.

### 9-7. 유출 키 — 한계

유출 키는 해제하지 않는다. 판단할 수 없을 때도 차단을 유지한다. 새 키를 주는 화면 ·
운영자용 수단이 없다 — 사용자 JWT 를 받는 `rotate` API 만 있다(실측 2 가 그 길로
재페어링한다). 웹 「재발급」은 같은 키를 주므로 회복 수단이 아니다. 그 수단은 따로 정한다.

---

## 10. 틀린 줄 걷기

살아 있는 거부 목록에 토큰 꼴이 아닌 줄(틀린 줄)이 있으면 healthz 가 계속
실패한다(응답기 로그 `ingestauth_denylist_error` `reason=invalid_lines` · `lines`).
그러면 healthy 를 기다리는 블록(해제 적용 · 롤백 D · E-1 · R5 · 실측 5 의 응답기 확인 ·
MediaMTX 최후 재시작)이 모두 멈추고, 해제 준비의 집합 단언도 늘 멈춘다. 그 줄만
걷는다 — 손으로 편집하지 않는다.

1. **사본** — `deny-tokens.walk-copy.<시각>`(`cp -p` · 덮어쓰지 않는 이름).
1. **새 파일** — 사본에서 틀린 줄만 뺀 `deny-tokens.walk.<시각>`. 토큰 줄 · 주석 줄 · 빈
   줄은 그대로 둔다. 모드 · 소유는 원본과 같게 지정한다.
1. **단언** — 두 파일의 유효 토큰 집합이 같다 · 새 파일에 틀린 줄 0 · 틀린 줄 밖의 줄은
   차례 · 바이트까지 같다 · 응답기가 새 파일을 읽는다.
1. **재확인 → 끼우기** — 살아 있는 파일이 사본과 바이트까지 같은지 다시 본다(다르면 종료
   1 — 1 부터 다시, 새 사본이 그 줄을 담는다). 같으면 이름 바꾸기로 끼운다.
1. **재시작하지 않는다.** 유효 집합이 같아 합집합이 그대로다. healthz 는 다음 확인(30초
   주기)에서 파일을 지금 다시 읽는다 — 「걷기 확인」 블록으로 healthy 로 돌아왔는지와
   응답기 시작 시각이 그대로인지 본다. healthy 까지 걸린 시간을 적는다.

- 1–4 는 한 블록(수 초)이고 실행 직전 승인이다. 그동안만 거부 목록을 쓰지 않는다. 긴급
  절단은 미루지 않는다 — 블록 앞에서 끼면 4 의 재확인이 종료 1 로 멈춘다.
- 걷은 줄은 사본에 남는다. 그 줄이 막으려던 토큰이 있었으면(예: 소문자로 옮긴 값) 꼴을
  고쳐 덧붙이기(8-1)와 cut 으로 다시 막는다.
- **멈춤 안내가 이 절을 가리킨다** — 해제 준비의 집합 단언이 사본의 틀린 줄로 멈출 때,
  그리고 healthy 를 기다리는 블록이 시한을 넘기고 원인이 틀린 줄일 때다. 이 절을 돈 뒤 그
  블록으로 돌아간다.

```bash
# ── 블록: 틀린 줄 걷기 — 머리 + 이 본문. 인자 없음. 응답기를 다시 시작하지 않는다 ──
# 실행 직전 kty 승인. 끝에 응답기 시작 시각을 낸다 — 걷기 확인의 인자다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local stamp copy new bad auth_id='' started
  stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  copy="${DENY_FILE}.walk-copy.${stamp}"
  new="${DENY_FILE}.walk.${stamp}"
  [[ ! -e "${copy}" && ! -e "${new}" ]] || mismatch '같은 이름이 있다 — 1초 뒤 다시'
  # 1 사본
  cp -p -- "${DENY_FILE}" "${copy}"
  # 2 새 파일 — 틀린 줄만 뺀다. 모드 · 소유는 원본(사본)과 같게 지정한다
  deny_scan "${copy}" "${WORK}/walk"
  bad="$(< "${WORK}/walk.bad")"
  [[ "${bad}" != 0 ]] || mismatch '틀린 줄이 없다 — 걷을 것이 없다'
  cp -- "${WORK}/walk.kept" "${new}"
  chown --reference="${copy}" -- "${new}"
  chmod --reference="${copy}" -- "${new}"
  printf '사본: %s\n새 파일: %s\n' "${copy}" "${new}"
  # 3 단언
  assert_walk_files "${copy}" "${new}"
  # 4 재확인 → 끼우기
  assert_same "${DENY_FILE}" "${copy}" \
    '살아 있는 파일이 사본과 다르다 — 그사이 덧붙은 줄이 있다. 다시 돌린다'
  container_id ingest-auth auth_id
  started="$(docker inspect -f '{{.State.StartedAt}}' "${auth_id}")"
  swap_deny_file "${new}"
  printf '걷은 줄: %s개\n응답기 시작 시각: %s\n' "${bad}" "${started}"
}
main "$@"
```

```bash
# ── 블록: 걷기 확인 — 머리 + 이 본문. 인자: <응답기 시작 시각>(걷기가 낸 값). 읽기만 ──
main() {
  (( $# == 1 )) || check_failed '사용법: <응답기 시작 시각>'
  local auth_id='' started bad
  wait_ingest_auth_healthy
  container_id ingest-auth auth_id
  started="$(docker inspect -f '{{.State.StartedAt}}' "${auth_id}")"
  [[ "${started}" == "$1" ]] \
    || mismatch '응답기가 다시 시작됐다 — 걷기는 재시작 없이 끝나야 한다'
  deny_scan "${DENY_FILE}" "${WORK}/now"
  bad="$(< "${WORK}/now.bad")"
  [[ "${bad}" == 0 ]] || mismatch "거부 목록에 틀린 줄이 ${bad}개"
  echo "healthy · 시작 시각 그대로 · 틀린 줄 0 — $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
}
main "$@"
```

---

## 11. 롤백

T 구성 → 옛 판(반영 전 기록) → (리허설이면) T 로 다시. 블록은 2-2 의 머리 + (A 만 2-3 의
보존 본문) + 그 블록의 본문이다. 백업은 반영 전 보존(R0 — `pre-T`)과 단계 A(`T`)가
만든 아홉씩이다(2-3).

### 11-1. 언제 · 차례 · 승인

**언제.**

- 최초 반영이 컨테이너를 바꾼 뒤 멈췄을 때(5-1 F5 · F6)와 반영 직후 실측 1 · 6 · 7 이
  실패하고 그 자리에서 고칠 수 없을 때(F7) — 0 → A → B → C. 컨테이너를 바꾸기 전(F1–F4)은
  롤백이 아니라 반영 취소(5-2)다.
- 운영 중 T 결함 — 먼저 운영 순서(7절)로 푼다. 그래도 풀리지 않으면 kty 가 정해 0 → A →
  B → C.
- 리허설(6절 실측 8 — 반영 뒤 한 번) — 0 → A → B → C → D → E-1 → E-2.

| 단계 | 블록 | 하는 일 | 지움 |
|---|---|---|---|
| 0 | 롤백 0 | 사전 확인(읽기만) | — |
| A | 롤백 A | ① T 상태 보존 — T 백업 아홉 | — |
| B | 롤백 B | ② 내리고 반영 전 SHA 로 · ③ 손 수정 패치 · ④ 추적 밖 정리 · ⑤ 되살리고 올림 · `.env` 는 마지막 | 있음 |
| C | 롤백 C | ⑥ 복귀 확인(읽기만 — 11-4) | — |
| D | 롤백 D | **리허설에서만** — T 로 다시 올림 | 있음 |
| E-1 | 롤백 E-1 | 리허설 뒤 T 상태 단언(읽기만) | — |
| E-2 | 롤백 E-2 | 리허설 뒤 T 백업 아홉 지움 · 잔여 0 | 있음 |
| — | T 백업 처분 | 접힌 뒤 T 백업 아홉 지움 · 잔여 0(11-6) | 있음 |
| — | 반영 전 비밀 파기 | 롤백 창 닫기 — 반영 전 비밀 둘 지움(11-7) | 있음 |

**D 는 리허설(실측 8)에서만 쓴다(kty 81차).** 실제 롤백 · 최초 반영 실패 · 실측 실패로
0 → A → B → C 를 돌다 B · C 에서 막혀도 D 로 돌아가지 않는다. kty 판단으로 막힌 원인을
고쳐 B · C 를 끝내고, 다시 T 로 갈 때는 T 백업 처분 → R0 부터 새로 반영한다(11-6).

**승인.** 블록마다 실행 직전 kty 승인이다(2-1).

- 지우는 블록(B · D · E-2 · T 백업 처분 · 반영 전 비밀 파기)은 승인 문장에 11-5 의 해당
  행을 붙인다.
- A 와 B 는 그 사이에 아무것도 손대지 않도록 한 자리에서 잇달아 승인받는다. B ④ 의
  지우기 전 대조는 이름만 본다 — 내용은 A(보존)와 C · E-1 이 본다.
- D 의 승인 문장에는 리허설(실측 8)임을 적는다. 리허설이 아니면 D 를 승인하지 않는다.

**멈춤.** 어느 블록이든 종료 1(일치 없음) · 2(검사 실패)면 그 자리에서 멈추고 11-5 의
그 행을 따른다. 지우는 쪽으로 밀어붙이지 않는다.

### 11-2. 왜 이 차례인가

- 렌더 산출물은 템플릿을 되돌려도 바뀌지 않고, T 의 render.sh 로는 옛 설정이 나오지
  않는다. 그래서 렌더 산출물은 백업에서 되살리고, 손 수정본인 체크아웃은 트리 전체로
  되돌린다.
- `git checkout --detach` 는 작업 트리의 변경을 들고 간다. 그래서 먼저 `reset --hard`
  로 손 변경을 버리고(두 판의 손 수정은 각자의 패치에 있다) 깨끗한 트리로 옮긴다.
- `tar` 풀기는 덮어쓰기라 T 가 새로 만든 추적 밖 파일을 지우지 않는다. 그래서 추적 밖이
  모두 두 묶음(T · 반영 전) 안의 이름일 때만 `clean` 하고 묶음을 푼다.
- 판마다 무시 규칙이 다르다 — `hls.min.js` 는 반영 전 판에서 추적 밖이고 T 판에서는
  무시된다. `list_untracked` 는 반영 전 목록의 이름을 지금 무시돼도 담는다. 그래서 B 는
  반영 전 묶음으로 · D 는 T 묶음으로 그 내용까지 되돌리고, C · E-1 이 내용을 본다.
- compose 와 env 의 짝은 HEAD 로 고르고(`paired_compose`), `${DEMO_HOME}/.env` 는 단계
  끝에서만 바꾼다. 그래서 B · D 를 어디서 멈추든 처음부터 다시 돌리면 첫 `down` 이 그
  트리와 짝 맞는 env 로 풀린다.

### 11-3. 단계 블록

```bash
# ── 블록: 롤백 0 — 머리 + 이 본문. 인자 없음. 사전 확인(읽기만) ──
# 하나라도 어긋나면 롤백을 시작하지 않는다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local name head_sha
  # 체크아웃 소유자로 git 이 돈다(소유자 검사로 멈추면 여기서 안다)
  owner_git status --porcelain > /dev/null
  for name in .env.pre-T.bak mediamtx.demo.yml.pre-T.bak pre-T.sha pre-T.head \
    pre-T.docker pre-T.untracked.tgz; do
    [[ -s "${DEMO_DIR}/${name}" ]] \
      || mismatch "반영 전 백업이 없거나 비었다: ${name}"
  done
  # 손 수정 · 추적 밖이 없으면 비어 있을 수 있다
  for name in pre-T.status pre-T.worktree.patch pre-T.untracked.z; do
    [[ -e "${DEMO_DIR}/${name}" ]] || mismatch "반영 전 백업이 없다: ${name}"
  done
  tar -tzf "${DEMO_DIR}/pre-T.untracked.tgz" > /dev/null
  PRE_SHA="$(< "${DEMO_DIR}/pre-T.sha")"
  readonly PRE_SHA
  # 반영 전 커밋이 체크아웃에 있다(얕은 클론)
  owner_git cat-file -e "${PRE_SHA}^{commit}"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" != "${PRE_SHA}" ]] \
    || mismatch 'HEAD 가 반영 전과 같다 — 롤백할 T 가 없다(반영 도중이면 5-2)'
  echo '단계 0 통과 — 다음은 A'
}
main "$@"
```

```bash
# ── 블록: 롤백 A — 머리 + 보존 본문 + 이 본문. 인자 없음. ① T 상태 보존 ──
# 지우지 않는다 — T 는 그대로 돈다. 옛 반영의 T 백업이 남아 있으면 지금 상태와 견줘
# 「일치 없음」으로 멈춘다(덮어쓰지 않는다).
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local pre_sha head_sha
  pre_sha="$(< "${DEMO_DIR}/pre-T.sha")"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" != "${pre_sha}" ]] || mismatch 'HEAD 가 반영 전과 같다'
  preserve_state 'T'
  echo 'T 상태 보존 끝 — 백업 아홉 단언 통과. 다음은 B(잇달아)'
}
main "$@"
```

```bash
# ── 블록: 롤백 B — 머리 + 이 본문. 인자 없음. ②–⑤ 옛 판으로. 지운다 ──
# 실행 직전 kty 승인(A 와 잇달아). 처음부터 다시 돌려도 이어진다(11-5).
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local head_sha tracked
  PRE_SHA="$(< "${DEMO_DIR}/pre-T.sha")"
  T_SHA="$(< "${DEMO_DIR}/T.sha")"
  readonly PRE_SHA T_SHA
  [[ "${PRE_SHA}" != "${T_SHA}" ]] || mismatch 'T 와 반영 전 SHA 가 같다'
  # ② 내리고(env 는 HEAD 로 고른다), 반영 전 SHA 의 깨끗한 작업 트리를 만든다. 추적
  # 파일의 손 변경은 버린다 — T 것은 T, 반영 전 것은 pre-T 의 worktree.patch 에 있다
  paired_compose down --remove-orphans
  owner_git reset -q --hard HEAD
  # 추적 밖 파일과 겹치면 git 이 멈춘다(종료 2 — 아무것도 지우지 않았다)
  owner_git checkout -q --detach "${PRE_SHA}"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" == "${PRE_SHA}" ]] || mismatch 'HEAD 가 반영 전 SHA 가 아니다'
  tracked="$(owner_git status --porcelain --untracked-files=no)"
  [[ -z "${tracked}" ]] || mismatch '추적 파일이 깨끗하지 않다'
  # ③ 손 수정 패치 — 빈 패치면 대지 않는다
  if [[ -s "${DEMO_DIR}/pre-T.worktree.patch" ]]; then
    owner_git apply --check --binary < "${DEMO_DIR}/pre-T.worktree.patch"
    owner_git apply --binary < "${DEMO_DIR}/pre-T.worktree.patch"
  fi
  owner_git diff --binary HEAD > "${WORK}/worktree.patch"
  assert_same "${WORK}/worktree.patch" "${DEMO_DIR}/pre-T.worktree.patch"
  # ④ 두 묶음 밖의 추적 밖 파일이 있으면 지우지 않고 멈춘다(이름을 kty 에게 보인다)
  untracked_outside_bundles > "${WORK}/extra.z"
  if [[ -s "${WORK}/extra.z" ]]; then
    tr '\0' '\n' < "${WORK}/extra.z" >&2
    mismatch '두 묶음 밖의 추적 밖 파일이 있다 — 지우지 않는다'
  fi
  # 지우는 것은 두 묶음 안의 이름뿐이다. 반영 전 규칙이 무시하는 파일은 남는다
  owner_git clean -fd
  # ⑤ 추적 밖 묶음 · HEAD 형태 · 렌더 산출물을 되살리고 올린다. .env 는 마지막이다
  tar -C "${REPO}" -xzf "${DEMO_DIR}/pre-T.untracked.tgz"
  owner_git status --porcelain > "${WORK}/status"
  assert_same "${WORK}/status" "${DEMO_DIR}/pre-T.status" \
    '작업 트리가 반영 전 기록과 다르다 — 섞인 트리로 띄우지 않는다'
  restore_head_shape "${DEMO_DIR}/pre-T.head" "${PRE_SHA}"
  cp -p -- "${DEMO_DIR}/mediamtx.demo.yml.pre-T.bak" \
    "${DEMO_DIR}/mediamtx.demo.yml"
  assert_same "${DEMO_DIR}/mediamtx.demo.yml.pre-T.bak" \
    "${DEMO_DIR}/mediamtx.demo.yml"
  # 옛 compose 가 짝 맞는 env 로 풀리는지(출력 없음 — 비밀을 내지 않는다)
  paired_compose config --quiet
  # 모든 컨테이너를 새로 만들어 단일 파일 마운트도 새로 묶인다
  paired_compose up -d
  cp -p -- "${DEMO_DIR}/.env.pre-T.bak" "${DEMO_DIR}/.env"
  assert_same "${DEMO_DIR}/.env.pre-T.bak" "${DEMO_DIR}/.env"
  echo '옛 판으로 끝 — 다음은 C'
}
main "$@"
```

```bash
# ── 블록: 롤백 C — 머리 + 이 본문. 인자 없음. ⑥ 복귀 확인(읽기만) ──
# 반영 취소 ⓒ · T 백업 처분의 첫머리와 같은 함수 하나로 반영 전 기록과 견준다(11-4).
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  PRE_SHA="$(< "${DEMO_DIR}/pre-T.sha")"
  readonly PRE_SHA
  assert_back_to_pre_t
  echo '복귀 확인 끝 — 반영 전 기록과 같다. 사람이 볼 것은 11-4'
}
main "$@"
```

```bash
# ── 블록: 롤백 D — 머리 + 이 본문. 인자 없음. 리허설에서만 T 로 다시. 지운다 ──
# D 는 리허설(실측 8)에서만 쓴다(kty 81차). 실제 롤백 · 최초 반영 실패 · 실측 실패로
# 접는 중에는 B · C 에서 막혀도 D 로 돌아가지 않는다 — 원인을 고쳐 B · C 를 끝내고 T
# 백업 처분 → R0 다(11-6). 실행 직전 kty 승인(승인 문장에 리허설임을 적는다). 처음부터
# 다시 돌려도 이어진다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local head_sha tracked
  PRE_SHA="$(< "${DEMO_DIR}/pre-T.sha")"
  T_SHA="$(< "${DEMO_DIR}/T.sha")"
  readonly PRE_SHA T_SHA
  [[ "${PRE_SHA}" != "${T_SHA}" ]] || mismatch 'T 와 반영 전 SHA 가 같다'
  # 응답기를 다시 띄우므로 거부 목록 파일부터 본다(7-4) — 어긋나면 바꾼 것 없이 멈춘다
  assert_ready_to_start_ingest_auth
  # T 체크아웃 뒤 · .env 복원 앞에서 멈췄어도 T compose + .env.T.bak 이다
  paired_compose down --remove-orphans
  owner_git reset -q --hard HEAD   # 옛 손 수정 — 반영 전 패치에 있다
  owner_git checkout -q --detach "${T_SHA}"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" == "${T_SHA}" ]] || mismatch 'HEAD 가 T SHA 가 아니다'
  tracked="$(owner_git status --porcelain --untracked-files=no)"
  [[ -z "${tracked}" ]] || mismatch '추적 파일이 깨끗하지 않다'
  if [[ -s "${DEMO_DIR}/T.worktree.patch" ]]; then
    owner_git apply --check --binary < "${DEMO_DIR}/T.worktree.patch"
    owner_git apply --binary < "${DEMO_DIR}/T.worktree.patch"
  fi
  owner_git diff --binary HEAD > "${WORK}/worktree.patch"
  assert_same "${WORK}/worktree.patch" "${DEMO_DIR}/T.worktree.patch"
  untracked_outside_bundles > "${WORK}/extra.z"
  if [[ -s "${WORK}/extra.z" ]]; then
    tr '\0' '\n' < "${WORK}/extra.z" >&2
    mismatch '두 묶음 밖의 추적 밖 파일이 있다 — 지우지 않는다'
  fi
  # 옛 추적 밖은 반영 전 묶음에 있다. T 규칙이 무시하는 파일(hls.min.js)은 남는다
  owner_git clean -fd
  # 덮어쓴다 — 남은 hls.min.js 도 T 의 내용으로
  tar -C "${REPO}" -xzf "${DEMO_DIR}/T.untracked.tgz"
  owner_git status --porcelain > "${WORK}/status"
  assert_same "${WORK}/status" "${DEMO_DIR}/T.status" \
    '작업 트리가 T 기록과 다르다 — 섞인 트리로 띄우지 않는다'
  restore_head_shape "${DEMO_DIR}/T.head" "${T_SHA}"
  cp -p -- "${DEMO_DIR}/mediamtx.demo.yml.T.bak" "${DEMO_DIR}/mediamtx.demo.yml"
  assert_same "${DEMO_DIR}/mediamtx.demo.yml.T.bak" \
    "${DEMO_DIR}/mediamtx.demo.yml"
  paired_compose config --quiet
  # 운영 순서 — 응답기 먼저. healthy 시한을 넘기면 MediaMTX 는 띄우지 않는다
  paired_compose up -d ingest-auth
  wait_ingest_auth_healthy
  paired_compose up -d
  cp -p -- "${DEMO_DIR}/.env.T.bak" "${DEMO_DIR}/.env"
  assert_same "${DEMO_DIR}/.env.T.bak" "${DEMO_DIR}/.env"
  echo 'T 로 다시 끝 — 다음은 E-1'
}
main "$@"
```

```bash
# ── 블록: 롤백 E-1 — 머리 + 이 본문. 인자 없음. 리허설 뒤 T 상태 단언(읽기만) ──
# 어긋나면 아무것도 지우지 않는다 — T 가 A 의 기록대로 돌아오지 않았다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local t_sha head_sha
  t_sha="$(< "${DEMO_DIR}/T.sha")"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" == "${t_sha}" ]] || mismatch 'HEAD 가 T SHA 가 아니다'
  owner_git status --porcelain > "${WORK}/status"
  assert_same "${WORK}/status" "${DEMO_DIR}/T.status"
  owner_git diff --binary HEAD > "${WORK}/worktree.patch"
  assert_same "${WORK}/worktree.patch" "${DEMO_DIR}/T.worktree.patch"
  # 추적 밖 이름(-z)과 그 내용(T 에서 바뀐 hls.min.js 포함)이 A 의 기록과 같다
  list_untracked > "${WORK}/untracked.z"
  assert_same "${WORK}/untracked.z" "${DEMO_DIR}/T.untracked.z"
  assert_bundle "${DEMO_DIR}/T.untracked.tgz"
  assert_same "${DEMO_DIR}/.env.T.bak" "${DEMO_DIR}/.env"
  assert_same "${DEMO_DIR}/mediamtx.demo.yml.T.bak" \
    "${DEMO_DIR}/mediamtx.demo.yml"
  # 리허설이 만든 컨테이너 · 망 0 — A 의 기록과 같다
  list_docker_names > "${WORK}/docker"
  assert_same "${WORK}/docker" "${DEMO_DIR}/T.docker"
  list_running_services > "${WORK}/running"
  printf '%s\n' edge ingest-auth mediamtx > "${WORK}/running.want"
  assert_same "${WORK}/running" "${WORK}/running.want"
  wait_ingest_auth_healthy
  report_head_shape "${DEMO_DIR}/T.head"
  echo 'T 상태 단언 끝 — 사람이 볼 것을 본 뒤 E-2'
}
main "$@"
```

- E-1 뒤 사람이 본다(실측 8) — `config --images` 에 포크 `.2`, 토큰 경로 송출 · 재생,
  응답기 `publish_allowed`.

```bash
# ── 블록: 롤백 E-2 — 머리 + 이 본문. 인자 없음. 리허설 뒤 정리 · 잔여 0. 지운다 ──
# E-1 이 통과한 뒤에만 돈다. 실행 직전 kty 승인. 있는 것만 지운다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local t_sha head_sha
  # E-1 을 건너뛰어도 T 비밀의 마지막 사본을 단언 없이 지우지 않는다 — T 가 돌고
  # .env 가 그 사본과 같을 때만 지운다
  if [[ -e "${DEMO_DIR}/.env.T.bak" ]]; then
    t_sha="$(< "${DEMO_DIR}/T.sha")"
    head_sha="$(owner_git rev-parse HEAD)"
    [[ "${head_sha}" == "${t_sha}" ]] \
      || mismatch 'HEAD 가 T SHA 가 아니다 — 접힌 뒤면 T 백업 처분(11-6)'
    assert_same "${DEMO_DIR}/.env.T.bak" "${DEMO_DIR}/.env" \
      '.env 가 T 백업과 다르다 — D 를 다시 돈 뒤 E-1 → E-2'
  fi
  dispose_t_backups
  echo '리허설 정리 끝 — T 백업 이름 0'
}
main "$@"
```

### 11-4. 복귀 확인

단계 C(와 반영 취소 ⓒ · T 백업 처분의 첫머리)가 `assert_back_to_pre_t` 하나로 기계
단언한다(읽기만).

- HEAD = 반영 전 SHA. `git status --porcelain` 과 손 수정 패치가 반영 전 기록과 같다 —
  그래서 compose · 이미지 핀도 같다.
- 추적 밖 이름(`-z` — `list_untracked`)이 반영 전 목록과 같고, 그 내용이 반영 전 묶음과
  같다(`tar --compare` — `hls.min.js` 등).
- `.env` · 렌더 산출물이 반영 전 백업과 바이트까지 같다.
- 이 compose 프로젝트의 컨테이너 · 망 이름이 반영 전 기록과 같고(`ingest-auth` 없음),
  도는 서비스가 edge · mediamtx 둘이다.
- HEAD 형태는 견줘 알리기만 한다 — 반영 전이 브랜치였고 그 브랜치가 옮겨졌으면 B 가
  분리 HEAD 로 둔다.

사람이 본다 — `config --images` 에 포크 `.1`, 옛 단일 경로 OBS 송출 · 재생(`index.m3u8`
의 LL-HLS 마커). 롤백 목표는 반영 전에 확인한 마지막 정상 상태(반영 전 SHA + 손 수정 +
`.1`)다.

### 11-5. 멈춘 자리별 상태

B · D 는 처음부터 다시 돌려도 이어진다 — 명령과 명령 사이에서 멈춘 경우다(명령 도중
끊김은 표 마지막 행). compose 와 env 는 HEAD 로 고르고, 모든 줄이 되풀이해도 같은
결과이며(`down` · `reset` · `checkout` · 패치는 `reset` 뒤에 다시 대고 · `clean` 은 두
목록 안의 이름만 지우고 · `tar` 는 덮어쓴다), `.env` 는 단계 끝에서만 바뀐다. 다시
돌려도 같은 자리에서 멈추는 것은 결정이 필요한 어긋남뿐이다.

**「다른 길」 열의 D 는 리허설(실측 8)에서만 쓴다(kty 81차).** 실제 롤백 · 최초 반영
실패 · 실측 실패로 0 → A → B → C 를 돌다 B · C 에서 막히면 D 로 돌아가지 않는다 — kty
판단으로 막힌 원인을 고쳐 B · C 를 끝내고, T 백업 처분 → R0 부터 다시 반영한다(11-6).
그래서 D · E-1 · E-2 의 행은 리허설에서만 생긴다.

| 멈춘 자리 | 상태 | 그 자리에서 다시 돌리면 | 다른 길 |
|---|---|---|---|
| R0(반영 전 보존) | 5-1 F0 | 5-1 F0 | 첫머리(T 백업 남음)에서 멈췄으면 T 백업 처분(11-6) 뒤 다시 |
| 단계 0 | 바뀐 것 없음 — T 가 돈다 | 원인(신원 · 백업 · 도구 판)을 고친 뒤 이어진다 | HEAD 가 반영 전이면 롤백이 아니다 — 반영 도중이면 반영 취소(5-3) |
| 단계 A | T 가 돈다. T 백업 일부만 있다(반쪽은 `.part`) | 이어진다 — 있는 백업은 지금 상태와 같은지 단언하고 그대로 쓴다 | 단언이 어긋나면(종료 1) 지금 상태가 T 기록과 다르다 — A 를 B 뒤에 돌린 경우 · 끊긴 A 뒤 추적 밖 내용이 바뀐 경우 · 접은 뒤 처분 없이 다시 반영한 경우(옛 반영의 T 백업 — R0 첫머리가 먼저 막는다) 등. 덮어쓰지 않았으므로 그 자리에 맞는 아래 행으로 간다 |
| B ② `down` 앞 | 바뀐 것 없음 | 이어진다 | — |
| B ② `down` 뒤 – `checkout` 앞 | 컨테이너 내려감. HEAD = T. `.env` T | 이어진다 — 첫 `down` 은 T compose + `.env.T.bak` | 리허설이면 D 로 T 에 돌아가도 된다. 실제 롤백은 B 를 다시 돌려 끝낸다 |
| B ② `checkout` 이 멈춤(추적 밖 파일과 겹침) | 컨테이너 내려감. HEAD = T, 추적 파일 깨끗(`reset` 뒤). 아무것도 지우지 않았다 | 같은 자리에서 다시 멈춘다(종료 2 — git 이 이름을 알린다) | 그 파일은 T 묶음에 있다. kty 가 정한 뒤(옮김) B 를 다시 돌리면 이어진다. 리허설이면 D 로 T 에 돌아가도 된다 — 실제 롤백은 D 를 쓰지 않는다 |
| B ② `checkout` 뒤 – ③ | 컨테이너 내려감. HEAD = 반영 전. 손 수정 패치 일부 · 전부. `.env` T | 이어진다 — `down` 은 옛 compose + `.env.pre-T.bak`, `reset` 이 반쪽 패치를 버린다 | `apply --check` 가 실패했으면(종료 2) 패치가 반영 전 SHA 에 맞지 않는다 — 다시 돌려도 같은 자리다. kty 가 원인을 고친 뒤 B 를 다시. 리허설이면 D — 실제 롤백은 D 를 쓰지 않는다 |
| B ④ 목록 대조 실패 | 아무것도 지우지 않았다. 컨테이너 내려감. HEAD = 반영 전 + 손 수정. `.env` T | 같은 자리에서 다시 멈춘다(종료 1) | 리허설의 D 도 같은 대조에서 멈춘다 — 그 이름은 T 목록에도 없다. 원인 후보 — A 뒤 생긴 파일 · T 에서 새로 생긴, T 판이 무시하고 반영 전 판은 무시하지 않는 파일 · 반영 전에 스테이징된 새 파일. kty 가 그 파일을 정한다(보존할 곳으로 옮김 — 지우면 승인). 그 뒤 B 를 다시 돌리면 이어진다 |
| B ④ `clean` 뒤 – ⑤ 상태 대조 앞 | 지금 규칙의 추적 밖이 비었다(두 묶음에 있음). 컨테이너 내려감. `.env` T | 이어진다 — 대조는 빈 목록을 통과하고 `tar` 가 되살린다 | 리허설이면 D |
| B ⑤ 상태 대조 실패 | 작업 트리가 반영 전 기록과 다르다. 컨테이너 내려감. `.env` T | 같은 자리에서 다시 멈춘다(종료 1) | 원인 후보 — 반영 전의 스테이징 · 묶음과 트리의 차이. 띄우지 않는다. 차분을 보고 kty 가 원인을 고친 뒤 B 를 다시. 리허설이면 D 로 T 에 돌아가도 된다 — 실제 롤백은 D 를 쓰지 않는다 |
| B ⑤ HEAD 형태 · 렌더 산출물 복원 – `up` | 옛 판 트리 · 옛 렌더. 컨테이너 내려감 또는 일부. `.env` T | 이어진다. `config --quiet` 가 실패하면(종료 2) 옛 compose 가 `.env.pre-T.bak` 으로 풀리지 않는다 — 다시 돌려도 같은 자리, kty 가 원인을 고친 뒤 B 를 다시 | 리허설이면 D |
| B ⑤ `.env` 복원 앞(띄운 뒤) | 옛 판이 돈다. `.env` 만 T | 이어진다(한 번 더 내렸다 올린다) | 리허설이면 D |
| 단계 C | B 의 끝 상태. C 는 읽기만 | 같은 결과다 | 어긋남(종료 1)은 B 의 끝 상태가 반영 전 기록과 다르다는 뜻 — 추적 밖 이름 · 내용 포함. B 가 끝나지 않았으면 B 를 다시. 그 밖은 차분을 보고 kty 가 원인을 고친 뒤 B → C 를 다시 돈다 — 실제 롤백은 그다음 T 백업 처분 → R0 다. 리허설이면 D 로 T 에 돌아가도 된다 |
| D 첫머리 — 거부 목록 확인(종료 1) | 바뀐 것 없음 | 같은 자리에서 다시 멈춘다 | 7-4 — 파일이 없으면 되살릴 내용을 kty 가 정한다. 틀린 줄이면 kty — 틀린 줄 걷기(10절)는 응답기 컨테이너가 있어야 돈다 |
| D 어디서든(명령 사이) | HEAD 는 반영 전 또는 T. `.env` 는 옛 판(단계 끝에서만 바뀐다) | 이어진다 — 첫 `down` 은 HEAD 로 고른 짝이다. T 체크아웃 뒤 · `.env` 복원 앞에서 멈췄어도 T compose + `.env.T.bak` 이다 | ④ 목록 대조 실패는 B ④ 행과 같다. ⑤ 상태 대조 실패는 kty |
| D 의 healthy 시한 초과 | `ingest-auth` 만 떠 있고 MediaMTX 는 띄우지 않았다(운영 순서). `.env` 옛 판 | 원인(응답기 로그)을 고친 뒤 이어진다. 거부 목록의 틀린 줄이면 손으로 고치지 않고 틀린 줄 걷기(10절 — 재시작 없음)를 돈다 | — |
| 단계 E-1 | 바뀐 것 없음(읽기만) | 같은 결과다 | 어긋남(종료 1)은 T 가 A 의 기록대로 돌아오지 않았다는 뜻 — 추적 밖 이름 · 내용 포함. 아무것도 지우지 않았다. kty |
| 단계 E-2 | T 백업 일부가 지워졌다 | 이어진다 — 있는 것만 지운다. `.env.T.bak` 이 남아 있으면 먼저 HEAD = T · `.env` = `.env.T.bak` 을 본다. 어긋나면(D 가 `.env` 복원 앞에서 멈춘 채 E-1 을 건너뜀 · 접힌 뒤) 아무것도 지우지 않고 멈춘다(종료 1). 잔여가 남으면(종료 1) 그 이름을 보이고 kty | `.env` 가 다르면 D 를 다시 돌린 뒤 E-1 → E-2. 접힌 뒤면 T 백업 처분 |
| T 백업 처분 | 첫머리 단언에서 멈췄으면 지운 것이 없다. 지우는 중에 멈췄으면 T 백업 일부가 지워졌다 — 반영 전 상태는 그대로 | 이어진다 — 있는 것만 지운다. 첫머리(HEAD = 반영 전 · `.env` = `.env.pre-T.bak` · 복귀 확인)가 어긋나면(종료 1) 아무것도 지우지 않는다 — T 가 돌거나 B 가 끝나지 않았거나 접힌 끝 상태가 반영 전 기록과 다르다. `.env.pre-T.bak` 이 없으면 검사 실패(종료 2)다 | B 가 끝나지 않았으면 B 를 다시 돌린 뒤 C → 처분. 복귀 확인이 어긋나면 단계 C 행과 같다(kty). T 가 돌면 처분하지 않는다 — 그 T 백업은 롤백의 길이다. 반영 전 비밀을 먼저 지웠으면 이 블록은 돌지 못한다(11-7) |
| 반영 전 비밀 파기 | 첫머리(T 백업 남음)에서 멈췄으면 지운 것이 없다. 지우는 중에 멈췄으면 비밀 하나만 지워졌을 수 있다 | 이어진다 — 있는 것만 지운다 | T 백업이 남았으면 접힌 뒤는 T 백업 처분, T 가 도는 리허설 뒤는 E-1 → E-2 가 먼저다 |
| 명령 도중 끊김(프로세스가 죽음 — 상자 재부팅 · 명령 시한 등) | 트리 일부만 바뀌었거나 `.git/index.lock` 이 남았다. HEAD 는 옛 값일 수 있다 | `apply` · `clean` · `tar` 풀기 도중이면 이어질 것으로 본다(추론 — 남은 이름이 두 목록 안이고 `reset` · `clean` · `tar` 가 다시 맞춘다). `reset` · `checkout` 도중이면 지우기 전에 멈춘다(종료 2) — `reset` 이 남은 lock 에서, 또는 첫 `down` 이 HEAD 로 고른 env 와 판이 어긋난 compose 의 `:?` 에서 | kty — 남은 lock · 트리 상태를 보고 정한다(lock 을 지우는 것은 승인). 블록은 RunShellScript 로 보내 세션 끊김으로는 이 행에 오지 않는다 |

### 11-6. 접은 뒤 다시 T 로

0 → A → B → C 로 접은 뒤(실제 롤백 · 최초 반영 실패 · 실측 실패 — 5-1 F5–F7) 다시 T
로 갈 때는 **T 백업 처분 → R0 부터 새로 반영한다**(5-4).

- **D 를 쓰지 않는다** — D 는 리허설(실측 8)에서만 쓴다(kty 81차). D 는 단계 A 가 남긴 T
  백업을 되살리는데, 최초 반영 실패에서 그 「T」는 반쪽 상태(HEAD · `.env` 만 T)이거나
  고치기 전 판이라 E-1 의 기계 단언까지 지나 고치기 전 판이 「T」로 남을 수 있다. 온전한
  T 에서 시작한 실제 롤백에도 같은 규칙을 쓴다 — 규칙 한 줄이라 급할 때 판단할 것이 없고,
  dev 서버 · 지금 서비스 사용자 0 이라 앞으로 가는 길의 대가가 작다. 사용자가 생기면
  다시 정한다.
- **T 백업 처분이 먼저다.** 남겨 두면 다음 반영의 단계 A 가 옛 T 백업과 견줘 「일치
  없음」으로 멈추고, E-2 도 옛 T 백업(HEAD · `.env`)과 어긋나 멈춘다. R0 첫머리가 T
  백업이 남은 다시 반영을 반영 앞에서 막는다.
- **처분은 접힌 뒤에만 지운다.** 첫머리에서 HEAD = 반영 전 SHA · `.env` = 반영 전 백업 ·
  단계 C 의 복귀 확인(`assert_back_to_pre_t`)을 본다. T 가 도는 동안의 T 백업은 롤백의
  길이라 지우지 않는다. E-2 와 다른 블록이다 — E-2 는 T 가 도는 리허설 뒤에 지운다. 지우는
  줄은 E-2 와 같은 함수(`dispose_t_backups`)다.
- 반영 취소로 접었으면(F1–F4) T 백업이 없다 — 곧바로 R0 다.

```bash
# ── 블록: T 백업 처분 — 머리 + 이 본문. 인자 없음. 접은 뒤 다시 반영하기 전. 지운다 ──
# 실행 직전 kty 승인. E-2 와 다른 블록이다 — 접힌 뒤(단계 C 를 통과한 끝 상태)에만
# 지운다. .env.pre-T.bak 이 없으면 검사 실패(종료 2)다 — 롤백 창을 닫을 때는 반영 전
# 비밀을 지우기 전에 이 블록을 돈다(11-7).
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local head_sha
  PRE_SHA="$(< "${DEMO_DIR}/pre-T.sha")"
  readonly PRE_SHA
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" == "${PRE_SHA}" ]] \
    || mismatch 'HEAD 가 반영 전 SHA 가 아니다 — 접힌 뒤가 아니면 지우지 않는다'
  assert_same "${DEMO_DIR}/.env.pre-T.bak" "${DEMO_DIR}/.env" \
    '.env 가 반영 전이 아니다 — B 가 끝나지 않았다'
  # 단계 C 의 복귀 확인 — 접힌 끝 상태가 반영 전 기록과 같을 때만 지운다
  assert_back_to_pre_t
  dispose_t_backups
  echo 'T 백업 처분 끝 — T 백업 이름 0. 다음은 R0(4절)'
}
main "$@"
```

### 11-7. 남는 것과 정리

- **응답기의 흔적.** `ingest-auth` 이미지와 `${DEMO_HOME}/ingest-auth/{deny,state}` 가
  남는다. 비밀은 없다(공개 토큰 · 연결 id). T 로 다시 올리면 그대로 다시 쓴다(거부 목록
  합집합 · 기록 세대). 롤백 창을 닫을 때 이미지를 지우고, 디렉터리는 기록이 든 것이라
  지우기 전에 kty 에게 묻는다.
- **리허설의 T 백업**은 E-2 가 곧바로 지운다.
- **`shred -u` 는 이름 제거 + 최선 노력이다.** 저널링 파일 시스템 · EBS 에서는 덮어쓴
  자리가 옛 블록과 같다는 보장이 없다 — 지운 비밀이 디스크 블록에 남을 수 있다. 끝까지
  없애는 것은 상자 철거(12절 7 — 인스턴스 종료로 볼륨이 지워진다)다.

**롤백 창 닫기(12단계 — 실측 통과 뒤 첫 팀 실사용 방송이 무사하면).** 차례가 있다.

1.  실제로 롤백했고 다시 반영하지 않았으면 먼저 T 백업 처분(11-6)을 돈다. 그 블록의
    첫머리가 `.env.pre-T.bak` 을 보므로 반영 전 비밀보다 먼저다. 거꾸로 하면 처분 블록이
    검사 실패(종료 2)로 돌지 못하고, 남은 T 일곱이 다음 반영의 R0 첫머리를 막는다. 접은
    뒤 다시 반영했으면 처분이 그 전에 지웠고, 리허설 뒤면 E-2 가 지웠다.
1.  그다음 아래 「반영 전 비밀 파기」로 반영 전 비밀 둘(`.env.pre-T.bak` ·
    `mediamtx.demo.yml.pre-T.bak` — 옛 passphrase · 옛 렌더 산출물)과 그 `.part` 를
    지운다. 이 블록은 T 백업이 하나라도 남아 있으면 지우지 않고 멈춘다 — 위 차례를
    블록이 지킨다.
1.  반영 전의 나머지 일곱(`.sha` · `.head` · `.status` · `.worktree.patch` ·
    `.untracked.z` · `.untracked.tgz` · `.docker`)은 비밀이 아니라 남겨도 된다. 반영 전
    비밀을 지운 뒤에는 이 판으로 롤백할 수 없다.
1.  반영 전에 상자에 남아 있던 `compose.yml.bak-*` 둘과 롤백용 옛 이미지의 처분도 그때
    kty 에게 묻는다.

```bash
# ── 블록: 반영 전 비밀 파기 — 머리 + 이 본문. 인자 없음. 롤백 창 닫기 2. 지운다 ──
# 실행 직전 kty 승인. T 백업이 하나라도 남아 있으면 아무것도 지우지 않고 멈춘다(종료 1)
# — T 백업 처분(접힌 뒤) · E-2(리허설 뒤)가 먼저다. 지운 뒤에는 이 판으로 롤백할 수 없다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local name path left=''
  t_backup_names > "${WORK}/t-left"
  [[ ! -s "${WORK}/t-left" ]] \
    || mismatch 'T 백업이 남아 있다 — T 백업 처분 · E-2 가 먼저다:' \
      "$(tr '\n' ' ' < "${WORK}/t-left")"
  for name in .env.pre-T.bak mediamtx.demo.yml.pre-T.bak; do
    for path in "${DEMO_DIR}/${name}" "${DEMO_DIR}/${name}.part"; do
      if [[ -e "${path}" ]]; then
        shred -u -- "${path}"
      fi
    done
  done
  # 비밀이 아닌 일곱은 남긴다. 끊긴 보존이 남긴 그 .part 만 지운다
  for name in sha head status worktree.patch untracked.z untracked.tgz \
    docker; do
    rm -f -- "${DEMO_DIR}/pre-T.${name}.part"
  done
  ls -1A -- "${DEMO_DIR}" > "${WORK}/names"   # 목록을 먼저 받는다 — 못 받으면 검사 실패
  while IFS= read -r name; do
    case "${name}" in
      *.pre-T.bak | *.pre-T.bak.part | pre-T.*.part) left+="${name} " ;;
    esac
  done < "${WORK}/names"
  [[ -z "${left}" ]] || mismatch "반영 전 비밀 잔여: ${left}"
  echo '반영 전 비밀 파기 끝 — 남은 반영 전 백업은 비밀이 아닌 일곱'
}
main "$@"
```

---

## 12. 철거

상자를 없애기로 kty 가 정했을 때만 한다. 순서를 지킨다 — 보안그룹은 인스턴스가 종료된
뒤에만 지워진다.

```bash
# 세션이 다르면 SG·인스턴스 ID 를 이름·태그로 다시 집는다.
SG=$(aws ec2 describe-security-groups --filters Name=group-name,Values=pokeclip-media-demo-sg \
      --query 'SecurityGroups[0].GroupId' --output text)
IID=$(aws ec2 describe-instances --filters Name=tag:Name,Values=pokeclip-media-demo \
       Name=instance-state-name,Values=running,stopped \
       --query 'Reservations[0].Instances[0].InstanceId' --output text)
```

1. **terminate 전에 캡처**: 볼륨 ID와 `DeleteOnTermination` 값을 적어 둔다. `false` 면 먼저 `true` 로 바꾸거나 종료 후 볼륨을 직접 지운다.
   ```bash
   aws ec2 describe-instances --instance-ids <instance-id> \
     --query 'Reservations[0].Instances[0].BlockDeviceMappings[].Ebs.{id:VolumeId,del:DeleteOnTermination}'
   ```
2. `aws ec2 terminate-instances --instance-ids <instance-id>` → describe 가 `terminated`
3. `aws ec2 describe-volumes --volume-ids <vol-id>` → `InvalidVolume.NotFound`
4. `aws ec2 delete-security-group --group-id "$SG"` → describe 0건
5. A 레코드 `DELETE`(1-3의 change-batch에서 Action 만 바꾼다) → `dig` 응답 없음
6. 계정 훑기: `describe-instances` · `describe-volumes` · `describe-addresses` 에 demo 태그 잔여 0건
7. 상자의 비밀 파기 — `.env` · 반영 전 · T `.bak`(인스턴스 종료로 자동이지만 명시한다)
8. 노트북의 로컬 리허설 디렉터리(`.env` · 렌더물) 삭제 + **클립보드 비우기**
9. OBS 플러그인 — 시험 계정의 연결을 해제한다
10. 저장소 정리 PR — `infra/dev-media/` 와 그 포인터를 지울지 kty 가 정한다

### 중간에 멈출 때 (역순 롤백)

| 어디까지 갔나 | 되돌릴 것 | 확인 |
|---|---|---|
| 운영 중 | 위 1–10 전부 | 철거 체크리스트 |
| 배포까지(4절) | A 레코드 DELETE → terminate → 볼륨 부재 → 보안그룹 삭제 | 각 describe 0건 |
| DNS까지(1-3) | A 레코드 DELETE → terminate → 보안그룹 삭제 | 위와 동일 |
| 보안그룹만(1-1) | 보안그룹 삭제 | describe 0건 |

어느 경우에도 팀 dev EC2와 공용 compose 파일은 건드리지 않으므로 팀에 미치는 영향은 없다.
