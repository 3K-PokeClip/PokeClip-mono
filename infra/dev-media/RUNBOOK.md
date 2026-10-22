# RUNBOOK — dev-media 계정별 방송 서버

위에서 아래로 순서대로 친다. 설정값은 이 문서에 옮겨 적지 않는다 — 값이 궁금하면 해당
파일을 본다(`mediamtx.demo.yml.tmpl` · `compose.yml` · `nginx.conf`). 배경과 구성도는
`README.md` 다. 블록 안의 고정값(UID 10002 · 10003 · 64 KiB · 토큰 꼴)은 그 파일들과
응답기(`media/cmd/ingest-auth`)가 정한 값을 옮긴 것이다 — 그쪽이 바뀌면 함께 고친다.

**서버에서 실행하는 명령은 전부 root 다.** `.env` 가 `root:root 0600` 이라서, `sudo` 를
빼면 render.sh 는 `.env` 읽기 검사에서 중단되고, compose 는 변수 보간에 실패한다.

**docker 그룹 = 상자 root 다(비밀 열람 포함).** docker 그룹은 `docker run -v /:/host`
로 상자 파일을 root 로 읽고 쓸 수 있다. 응답기의 비밀 둘(resolve 출입증 · Control API
비번)도 컨테이너 환경 변수라 `docker inspect` 로 보인다. 상자에서 docker 그룹에 사람을
더하지 않는다.

**이 저장소는 public 이다 — 실제 AWS 자원 ID · 공인 IP · 사설 IP 를 이 문서에 적지
않는다**(git 히스토리에 영구히 남는다). 아래 셸 변수를 세션 시작 시 채워서 쓴다. 값은 AWS
콘솔이나 팀 비공개 채널에서 확인한다. `VPC_ID` 는 팀 dev VPC, `SUBNET_ID` 는 퍼블릭
서브넷, `HOSTED_ZONE_ID` 는 pokeclip.com 존이다. `AMI_ID` 가 없으면 1-2 의 SSM
파라미터로 최신 AL2023 을 조회한다.

```bash
export VPC_ID=<vpc-id>
export SUBNET_ID=<subnet-id>
export AMI_ID=<al2023-ami-id>
export HOSTED_ZONE_ID=<hosted-zone-id>
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
- 로컬 `.env` 에도 `MTX_IMAGE_REGISTRY` 줄이 있어야 compose 가 풀린다(`:?`). MediaMTX
  이미지는 비공개 ECR 에 있어, R3′ · R6 은 ECR 읽기 자격이 있는 프로필로만 돈다(상자는
  인스턴스 역할 — 1-5).

**로컬 실측 표기.** 본문의 「로컬 실측 S7-4 · S7-5 · S7-7 · fix3」은 이 판의 블록을 리눅스
dind(Docker 안에서 따로 도는 Docker 엔진) 안에서 바이트 그대로 돌린 기록이다. 기록은 이
저장소 밖의 로컬 실험 루트에 있다 —
`2026-10-17-p7-dev-media/<s7-4 · s7-5 · s7-7 · fix3>/record.md`. 그 뒤의 이름(L10 ·
D13 · E2 등)은 그 기록의 시나리오다. 상자(AL2023)에서 잰 값이 아니다.

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
현재 공인 IP 는 그때그때 산출한다(상수로 적지 않는다).

```bash
MYIP=$(curl -s https://checkip.amazonaws.com)
SG=$(aws ec2 create-security-group --group-name pokeclip-media-demo-sg \
      --description "dev-media per-account broadcast server (always-on)" \
      --vpc-id "$VPC_ID" --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol udp --port 8890 --cidr "$MYIP/32"
aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol tcp --port 8888 --cidr "$MYIP/32"
```

검증: 규칙 2건 · 0.0.0.0/0 은 0건.

```bash
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
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=pokeclip-media-demo}]'
```

- `$AMI_ID` 가 비었거나 만료됐으면 최신 AL2023으로 채운다:
  `export AMI_ID=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 --query Parameter.Value --output text)`
- 상시 서버라 만료 태그를 달지 않는다. 이름(`pokeclip-media-demo` — 보안그룹은
  `pokeclip-media-demo-sg`)은 12절 철거가 자원을 다시 집는 열쇠라 그대로 둔다. 옛 임시
  데모 때 만든 자원에는 `expires` 태그가 남아 있을 수 있다 — 그 날짜로 지우지 않는다.
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

dig +short media-dev.pokeclip.com
```

`dig` 에 위 IP 가 나와야 한다 (네임서버는 존 조회로 확인).

### 1-4. 접속 확인

첫 명령(aws)의 결과에 `Online` 이 나와야 한다.

```bash
aws ssm describe-instance-information --query "InstanceInformationList[?InstanceId=='<instance-id>'].PingStatus"
nc -z -w3 media-dev.pokeclip.com 22 && echo "22 열림 — 즉시 조사" || echo "22 닫힘(정상)"
```

### 1-5. 이미지 받기 권한 (ECR 읽기)

MediaMTX 이미지는 계정 ECR 의 비공개 저장소 `pokeclip/mediamtx` 에 있다. 상자는 인스턴스
역할로 로그인해 받으므로(R3′) 그 역할에 ECR 읽기 권한이 있어야 한다 — SSM 기본 정책
(`AmazonSSMManagedInstanceCore`)에는 없다. 받기 동작 셋은 이 저장소 하나로 좁히고, 로그인
토큰 발급(`ecr:GetAuthorizationToken`)만 자원을 좁힐 수 없어 `*` 다. 상자의 aws CLI(AL2023
기본 포함)가 이 역할로 돈다.

kty 로컬 터미널에서 돌린다 — 계정 번호가 명령 · 응답에 들어가므로 에이전트 세션 · 문서에
붙여 넣지 않는다. 계정 번호는 손으로 적지 않고 셸에서 얻는다. 세 블록을 차례로 붙여
넣는다 — 1 이 상자에서 역할을 거꾸로 집어 이름을 보이고, 2 가 그 역할이 상자 전용 역할
`pokeclip-media-dev-ssm-role` 일 때만 정책을 넣는다. 공유 역할(`pokeclip-dev-ssm-role`
등 — 팀 dev EC2 와 함께 쓰는 역할)이나 다른 이름이면 2 가 「멈춤」을 내고 아무것도 넣지
않는다. 그때는 kty 가 정한다(상자를 1-2 의 공유 프로필로 다시 띄웠다면 상자 전용 프로필을
먼저 붙인다). 2 는 따로 띄운 bash 안에서 돈다 — 멈춰도 터미널은 닫히지 않고, 계정
번호는 지금 셸에 남지 않으며 화면에도 나오지 않는다. 다만 안의 aws 가 실패하면(정책
넣기 권한 거부 등) 오류 문구에 호출자 ARN(계정 번호가 든다)이 찍힐 수 있다 — 그 출력도
에이전트 세션 · 문서에 붙여 넣지 않는다.

세 블록은 맥 기본 셸(zsh)에 그대로 붙여도 돌게 썼다. 대화형 zsh 는 기본으로 `#` 를
주석으로 읽지 않아서(`interactive_comments` 꺼짐) 블록 안 주석 줄의 `)` 가 붙여 넣은
묶음 전체를 `parse error near ')'` 로 멈춘다 — 2026-10-20 에 옛 꼴의 블록 2 · 3 이 이렇게
아무것도 돌지 않았다. 그래서 블록 안에 주석을 두지 않는다 — 원인은 이 주석 줄이다(주석
줄을 뺀 옛 블록 2 는 zsh 에서도 돌았다). 2 를 `bash <<'SH'` 안에 둔 것은 붙인 셸이
무엇이든 가드 · 정책 만들기를 bash 가 읽게 하려는 것이다. 그 bash 는 PATH 에서 찾은
bash 이고 맥 기본 bash(3.2)에서도 돈다 — 0절의 「맥의 셸(bash 3.2)에서 돌리지
않는다」는 2절 머리를 쓰는 상자 블록을 맥의 bash 3.2 로 돌리지 말라는 얘기라 이 로컬
명령과는 상관없다.

블록 1(역할 집기) — 상자에서 거꾸로 집는다. 찍는 것은 역할 이름뿐이다(계정 번호 없음).

```bash
PROFILE_ARN="$(aws ec2 describe-instances --instance-ids <instance-id> \
  --query 'Reservations[0].Instances[0].IamInstanceProfile.Arn' --output text)"
ROLE="$(aws iam get-instance-profile \
  --instance-profile-name "${PROFILE_ARN##*/}" \
  --query 'InstanceProfile.Roles[0].RoleName' --output text)"
unset PROFILE_ARN
echo "역할: ${ROLE}"
```

블록 2(정책 넣기) — 상자 전용 역할일 때만 넣는다. 어긋나면 「멈춤」 · 종료 1 이고 넣지
않는다. 정책 문서는 `printf` 가 만든다(`%s` 자리가 계정 번호). 안의 aws 는 표준 입력을
`/dev/null` 로 받는다 — 이 bash 는 스크립트를 표준 입력으로 읽으므로, 표준 입력을 읽는
명령이 있으면 뒷줄을 삼킨다.

```bash
ROLE="${ROLE:-}" bash <<'SH'
set -euo pipefail
[[ "${ROLE}" == pokeclip-media-dev-ssm-role ]] \
  || { echo "멈춤 — 상자 전용 역할이 아니다: ${ROLE:-(비었음)}"; exit 1; }
ACCOUNT="$(aws sts get-caller-identity --query Account --output text \
  < /dev/null)" || ACCOUNT=''
[[ "${ACCOUNT}" =~ ^[0-9]{12}$ ]] \
  || { echo '멈춤 — 계정 번호를 얻지 못했다'; exit 1; }
POLICY="$(printf '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrLogin",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "PullMediaMtx",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchCheckLayerAvailability"
      ],
      "Resource": "arn:aws:ecr:ap-northeast-2:%s:repository/pokeclip/mediamtx"
    }
  ]
}' "${ACCOUNT}")"
aws iam put-role-policy --role-name "${ROLE}" \
  --policy-name pokeclip-media-dev-ecr-pull --policy-document "${POLICY}" \
  < /dev/null
echo '정책 넣음'
SH
```

블록 3(검증) — 정책 이름이 보이고 동작이 위 넷이다(자원 — 계정 번호 — 은 찍지 않는다).

```bash
aws iam list-role-policies --role-name "${ROLE}"
aws iam get-role-policy --role-name "${ROLE}" \
  --policy-name pokeclip-media-dev-ecr-pull \
  --query 'PolicyDocument.Statement[].Action'
```

- 권한이 없거나 저장소 · 리전이 다르면 R3′ 가 로그인 · 받기에서 검사 실패(종료 2)로
  멈춘다. 컨테이너를 바꾸기 전이라 F3 과 같다(5-1 — R3′ 의 종료 1 갈래도 그 행에 있다).
- 상자를 철거하면 이 정책도 지운다(12절 11).

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
  적용 · 되돌림 적용 · 틀린 줄 걷기 · 응답기 살리기 · 다시 시작 · IP 한도 켜기 · 끄기 ·
  MediaMTX 최후 재시작)은
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
# chown/chmod --reference) · GNU tar(--compare) · sudo · docker compose v2 ·
# flock(util-linux — lock_ip_limit 를 부르는 블록만).
# 종료 코드: 0 통과 · 1 일치 없음(기록 · 기대와 다르다) · 2 검사 실패(목록 · 조회 · 도구가
# 돌지 못했다 · 인자가 틀렸다). 일치 없음은 명령 치환 · 파이프 밖에서만 낸다 — 안에서
# 내면 바깥의 ERR 덫이 검사 실패로 바꿔 찍는다. 파일은 "$(cat -- …)" 로 읽는다 —
# "$(< …)" 는 파일이 없을 때 ERR 덫을 거치지 않고 종료 1 이다.
# 상자 파일을 쓰거나 지우는 블록은 첫머리에서 assert_demo_dir_safe 를 부른다.
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
readonly LOGIN_DIR="${DEMO_DIR}/.ecr-login"            # R3′ 의 ECR 로그인 자격
readonly IP_LIMIT_LOCK="${DEMO_DIR}/ip-limit.lock"     # 7-5 의 실행 잠금(비밀 아님)
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

# assert_demo_dir_safe 는 DEMO_DIR · ${DEMO_DIR}/ingest-auth · DENY_DIR 이 root
# 소유 · 링크 아님 · 그룹 · 기타 쓰기 없음인지 본다. 남이 쓸 수 있으면 예측할 수 있는 이름에
# 링크를 심어 root 의 cp · shred · > 를 딴 파일로 돌릴 수 있다. 뒤의 둘은 첫 반영(R5)
# 전에는 없으므로 있을 때만 본다. 어긋나면 일치 없음이다.
assert_demo_dir_safe() {
  local dir info uid mode
  for dir in "${DEMO_DIR}" "${DEMO_DIR}/ingest-auth" "${DENY_DIR}"; do
    if [[ "${dir}" != "${DEMO_DIR}" && ! -e "${dir}" && ! -L "${dir}" ]]; then
      continue
    fi
    [[ -d "${dir}" && ! -L "${dir}" ]] \
      || mismatch "${dir} 가 없거나 디렉터리가 아니다(링크 포함)"
    info="$(stat -c '%u %a' -- "${dir}")"
    read -r uid mode <<< "${info}"
    [[ "${uid}" == 0 ]] || mismatch "${dir} 가 root 소유가 아니다(${uid})"
    (( (8#${mode} & 8#022) == 0 )) \
      || mismatch "${dir} 에 root 밖의 쓰기 권한이 있다(${mode})"
  done
}

# remove_box_file 은 상자 파일 $2 를 지운다 — $1 이 shred 면 shred -u, rm 이면 rm 이다.
# 없으면 그대로 두고, 일반 파일이 아니거나 링크면 지우지 않고 일치 없음이다(shred 와
# [[ -e ]] 는 링크를 따라가 그 대상을 덮어쓴다).
remove_box_file() {
  local -r how="$1" path="$2"
  if [[ ! -e "${path}" && ! -L "${path}" ]]; then
    return 0
  fi
  [[ -f "${path}" && ! -L "${path}" ]] \
    || mismatch "일반 파일이 아니거나 링크다 — 지우지 않는다: ${path}"
  case "${how}" in
    shred) shred -u -- "${path}" ;;
    rm) rm -f -- "${path}" ;;
    *) check_failed "지우는 법은 shred · rm 가운데 하나다: ${how}" ;;
  esac
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

# paired_env_file 은 트리의 HEAD 와 짝 맞는 env 파일 이름을 변수 $1 에 넣는다(T →
# .env.T.bak · 반영 전 → .env.pre-T.bak). 둘 다 아니면 일치 없음이다. 앞에서 PRE_SHA ·
# T_SHA 를 readonly 로 둔다.
paired_env_file() {
  local head_sha
  head_sha="$(owner_git rev-parse HEAD)"
  if [[ "${head_sha}" == "${T_SHA}" ]]; then
    printf -v "$1" '%s' "${DEMO_DIR}/.env.T.bak"
  elif [[ "${head_sha}" == "${PRE_SHA}" ]]; then
    printf -v "$1" '%s' "${DEMO_DIR}/.env.pre-T.bak"
  else
    mismatch "HEAD 가 T 도 반영 전도 아니다 — compose 와 짝 맞는 env 가 없다"
  fi
}

# paired_compose 는 docker compose 를 트리의 HEAD 와 짝 맞는 env 로 부른다. 롤백의
# docker compose 는 모두 이것으로 부르고, 출력은 파일로 받는다. 앞에서 PRE_SHA · T_SHA 를
# readonly 로 둔다.
paired_compose() {
  local env_file
  paired_env_file env_file
  docker compose -p "${PROJECT}" --env-file "${env_file}" \
    -f "${REPO}/${COMPOSE_YML}" "$@"
}

# live_compose 는 docker compose 를 지금의 .env 로 부른다 — 반영 · 운영 블록이 쓴다(롤백은
# paired_compose). 출력은 파일로 받는다.
live_compose() {
  docker compose -p "${PROJECT}" --env-file "${DEMO_DIR}/.env" \
    -f "${REPO}/${COMPOSE_YML}" "$@"
}

# mask_account 는 파일 $1 을 12자리 숫자(ECR 레지스트리 호스트의 계정 번호)를 가려 낸다.
mask_account() {
  sed -E 's/[0-9]{12}/<계정>/g' -- "$1"
}

# compose_up_masked 는 live_compose · paired_compose($1)와 같은 env 로 up 을 부른다(나머지
# 인자). 도구 출력에는 이미지 좌표 전체가 찍힐 수 있어(받기 진행 · 오류) 파일로 받고, 계정
# 번호를 가려 낸다. 실패하면 검사 실패다. env 는 받기 밖에서 고른다 — || 안의 함수는
# set -e 가 꺼지고 일치 없음 문구가 파일로 들어가 사라지므로, 받는 것은 docker 하나뿐이다.
compose_up_masked() {
  local env_file
  local -i rc=0
  case "$1" in
    live_compose) env_file="${DEMO_DIR}/.env" ;;
    paired_compose) paired_env_file env_file ;;
    *) check_failed "compose_up_masked: 모르는 compose 함수다: $1" ;;
  esac
  shift
  docker compose -p "${PROJECT}" --env-file "${env_file}" \
    -f "${REPO}/${COMPOSE_YML}" up "$@" > "${WORK}/up.log" 2>&1 || rc=$?
  mask_account "${WORK}/up.log" >&2
  (( rc == 0 )) || check_failed "docker compose up $* 실패(종료 ${rc})"
}

# container_id 는 이 compose 프로젝트에서 서비스 $1 의 컨테이너 id 하나를 변수 $2 에
# 넣는다. 없거나 둘 이상이면 일치 없음이다.
container_id() {
  container_id_or_none "$1" "$2"
  [[ -n "${!2}" ]] || mismatch "$1 컨테이너가 없다"
}

# container_id_or_none 은 container_id 와 같되, 컨테이너가 없으면 변수 $2 를 비운다(접힌
# 뒤 · 첫 반영 전). 둘 이상이면 일치 없음이다.
container_id_or_none() {
  local -a ids
  docker ps -a -q --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter "label=com.docker.compose.service=$1" > "${WORK}/id.$1"
  mapfile -t ids < "${WORK}/id.$1"
  (( ${#ids[@]} <= 1 )) || mismatch "$1 컨테이너가 둘 이상이다(${#ids[@]}개)"
  printf -v "$2" '%s' "${ids[0]:-}"
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
# 앞에서 PRE_SHA 를 readonly 로 둔다. 도는 서비스는 반영 전 판의 compose 가 정의한
# 서비스 전부다 — 첫 반영 주기는 edge · mediamtx, 계정 ECR 판을 올린 주기는 edge ·
# ingest-auth · mediamtx 다(주기마다 다르므로 고정값으로 두지 않는다).
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
  # 컨테이너 · 망 이름이 반영 전과 같다(첫 주기면 ingest-auth · mtx-control 이 없다)
  list_docker_names > "${WORK}/docker"
  assert_same "${WORK}/docker" "${DEMO_DIR}/pre-T.docker"
  list_running_services > "${WORK}/running"
  # 위에서 HEAD · .env 가 반영 전임을 단언했다 — 그 compose 의 서비스가 기대값이다
  docker compose -p "${PROJECT}" --env-file "${DEMO_DIR}/.env.pre-T.bak" \
    -f "${REPO}/${COMPOSE_YML}" config --services > "${WORK}/services"
  sort -- "${WORK}/services" > "${WORK}/running.want"
  assert_same "${WORK}/running" "${WORK}/running.want" \
    '도는 서비스가 반영 전 판 compose 의 서비스와 다르다'
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

# stray_secret_names 는 끊긴 R1 · 렌더 · R3′ · IP 한도 켜기 · 끄기(7-5)가 남길 수 있는 비밀
# 임시 파일(.env.next · .env.iplimit.* — resolve 출입증 · API 비번,
# mediamtx.demo.yml.tmp.* — 잠금값, .ecr-login — ECR 로그인 자격)의 이름을 한 줄에
# 하나씩 낸다.
stray_secret_names() {
  local name
  ls -1A -- "${DEMO_DIR}" > "${WORK}/stray-names"   # 못 받으면 검사 실패
  while IFS= read -r name; do
    case "${name}" in
      .env.next | .env.iplimit.* | mediamtx.demo.yml.tmp.* | .ecr-login)
        printf '%s\n' "${name}"
        ;;
    esac
  done < "${WORK}/stray-names"
}

# dispose_ecr_login 은 R3′ 의 ECR 로그인 자리(LOGIN_DIR — 안에 12시간 유효한 토큰이 든
# config.json 하나)를 치운다. config.json 은 shred -u, 디렉터리는 rmdir 이다. 없으면 그대로
# 둔다. 링크 · 디렉터리 아닌 것이거나 config.json 밖의 파일이 들어 있으면 기대와 다른
# 상태라 일치 없음이다 — 그 이름을 보이고 모르는 것은 지우지 않는다(config.json 은 먼저
# 지운다).
dispose_ecr_login() {
  local left
  if [[ ! -e "${LOGIN_DIR}" && ! -L "${LOGIN_DIR}" ]]; then
    return 0
  fi
  [[ -d "${LOGIN_DIR}" && ! -L "${LOGIN_DIR}" ]] \
    || mismatch "디렉터리가 아니거나 링크다 — 지우지 않는다: ${LOGIN_DIR}"
  remove_box_file shred "${LOGIN_DIR}/config.json"
  ls -1A -- "${LOGIN_DIR}" > "${WORK}/login-left"   # 못 받으면 검사 실패
  left="$(tr '\n' ' ' < "${WORK}/login-left")"
  [[ -z "${left}" ]] \
    || mismatch "${LOGIN_DIR} 에 모르는 파일이 있다 — 지우지 않는다: ${left}"
  rmdir -- "${LOGIN_DIR}"
}

# lock_ip_limit 는 IP_LIMIT_LOCK 에 flock -n 을 잡는다 — 이 프로세스가 끝나면 풀린다. 이미 잡았으면
# 그대로다(같은 프로세스가 두 번 열면 flock 끼리 막힌다). 다른 실행이 쥐고 있으면 일치 없음이다.
# 7-5 와 .env.iplimit.* 를 지우는 공통 정리(dispose_stray_secrets)가 잡는다 — 잠금 없이 겹치면
# 정리의 shred 가 7-5 의 후보를 연 채로, 그 후보가 .env 로 들어간 뒤 덮어쓸 수 있다.
lock_ip_limit() {
  local -i rc=0
  [[ -z "${IP_LIMIT_LOCK_FD:-}" ]] || return 0
  command -v flock > /dev/null || check_failed 'flock 이 없다(util-linux)'
  [[ ! -L "${IP_LIMIT_LOCK}" ]] || mismatch "링크다 — 쓰지 않는다: ${IP_LIMIT_LOCK}"
  exec {IP_LIMIT_LOCK_FD}>> "${IP_LIMIT_LOCK}" \
    || check_failed "잠금 파일을 열지 못했다: ${IP_LIMIT_LOCK}"
  flock -n "${IP_LIMIT_LOCK_FD}" || rc=$?
  (( rc != 1 )) \
    || mismatch 'IP 한도 켜기 · 끄기(7-5)가 돌고 있다(ip-limit.lock) — 그 실행이 끝난 뒤 다시'
  (( rc == 0 )) || check_failed "잠금을 잡지 못했다(flock 종료 ${rc})"
}

# dispose_stray_secrets 는 stray_secret_names 의 파일을 shred -u 로 지운다(링크면 멈춘다 —
# .ecr-login 은 dispose_ecr_login). T 백업 처분 · E-2 · 반영 전 비밀 파기가 부른다 — 셋 다
# 반영 · 렌더가 돌지 않는 안정 상태를 첫머리에서 단언한 뒤다. 먼저 lock_ip_limit 를 잡는다 —
# 7-5 가 돌고 있으면 아무것도 지우지 않고 일치 없음이다(그 실행이 끝난 뒤 다시).
dispose_stray_secrets() {
  local name
  lock_ip_limit
  stray_secret_names > "${WORK}/stray"
  while IFS= read -r name; do
    if [[ "${name}" == .ecr-login ]]; then
      dispose_ecr_login
    else
      remove_box_file shred "${DEMO_DIR}/${name}"
    fi
  done < "${WORK}/stray"
}

# dispose_t_backups 는 T 백업 아홉과 그 .part 를 있는 것만 지운다 — 비밀 둘(.env.T.bak —
# resolve 출입증 · API 비번, 렌더 산출물 백업 — 잠금값)은 shred -u(이름 제거 + 최선 노력
# — 11-7), 나머지 일곱은 rm. 끊긴 R1 · 렌더 · R3′ 의 비밀 임시 파일도 shred -u 로 지운다. 링크 ·
# 일반 파일 아닌 것은 지우지 않고 멈춘다. 끝에 그 이름 0 을 단언한다. 롤백 E-2 와 T 백업
# 처분 블록이 이 함수 하나를 부른다(첫머리 단언은 각 블록에 있다). 지우기 전에 lock_ip_limit 를
# 잡는다 — 7-5 가 돌고 있으면 하나도 지우지 않고 멈춘다.
dispose_t_backups() {
  local name
  lock_ip_limit
  for name in .env.T.bak mediamtx.demo.yml.T.bak; do
    remove_box_file shred "${DEMO_DIR}/${name}"
    remove_box_file shred "${DEMO_DIR}/${name}.part"
  done
  for name in T.sha T.head T.status T.worktree.patch T.untracked.z \
    T.untracked.tgz T.docker; do
    remove_box_file rm "${DEMO_DIR}/${name}"
    remove_box_file rm "${DEMO_DIR}/${name}.part"
  done
  dispose_stray_secrets
  t_backup_names > "${WORK}/t-left"
  stray_secret_names >> "${WORK}/t-left"
  [[ ! -s "${WORK}/t-left" ]] \
    || mismatch "T 백업 · 비밀 임시 파일 잔여: $(tr '\n' ' ' < "${WORK}/t-left")"
}

# assert_backups_whole 은 상태 $1(pre-T · T)의 백업 아홉이 다 있는지 본다. 비면 안 되는
# 여섯(.env · 렌더 산출물 · SHA · HEAD 형태 · 컨테이너 · 망 이름 · 묶음)은 크기까지, 비어
# 있을 수 있는 셋(status · 손 수정 패치 · 추적 밖 목록)은 있음만 본다. 없으면 일치
# 없음이다. 묶음은 tar -tzf 로 읽혀야 한다(못 읽으면 검사 실패).
assert_backups_whole() {
  local -r state="$1"
  local name
  for name in ".env.${state}.bak" "mediamtx.demo.yml.${state}.bak" \
    "${state}.sha" "${state}.head" "${state}.docker" \
    "${state}.untracked.tgz"; do
    [[ -s "${DEMO_DIR}/${name}" ]] \
      || mismatch "${state} 백업이 없거나 비었다: ${name}"
  done
  for name in status worktree.patch untracked.z; do
    [[ -e "${DEMO_DIR}/${state}.${name}" ]] \
      || mismatch "${state} 백업이 없다: ${state}.${name}"
  done
  tar -tzf "${DEMO_DIR}/${state}.untracked.tgz" > /dev/null
}

# deny_scan 은 거부 목록 파일 $1 을 줄 문법(응답기 DenyList 와 같은 규칙)으로 읽는다.
# 줄마다 '#' 뒤를 버리고 앞뒤 공백(C 로캘 [[:space:]] 의 여섯 — 스페이스 · 탭 · LF · VT ·
# FF · CR)을 걷은 값이 토큰이다. 빈 값은 건너뛰고, 토큰 꼴이 아니거나 NUL 이 든 줄은
# 틀린 줄이다. 끝 줄바꿈이 없는 마지막 줄도 한 줄이다. 파일 셋을 쓴다.
#   $2.tokens  유효 토큰 집합(바이트 순 · 한 줄에 하나 · 같은 토큰은 하나)
#   $2.bad     틀린 줄 수(십진수 한 줄)
#   $2.kept    $3 이 있으면 값이 $3 인 줄을, 없으면 틀린 줄을 뺀 나머지 줄(원래 차례 ·
#              바이트 — 끝 줄바꿈이 없던 마지막 줄은 그대로 없이). NUL 이 든 줄은 늘
#              뺀다.
deny_scan() {
  local -r file="$1" out="$2" drop="${3:-}"
  local line value flag
  local -i n=0 bad=0 eof=0
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
  # read 가 실패하면(끝 줄바꿈 없는 마지막 줄 · 파일 끝) eof 를 세운다
  while IFS= read -r line || { eof=1; [[ -n "${line}" ]]; }; do
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
    if (( eof )); then
      printf '%s' "${line}" >> "${out}.kept"   # 원래 끝 줄바꿈이 없다
    else
      printf '%s\n' "${line}" >> "${out}.kept"
    fi
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

# assert_deny_file_shape 는 $1 이 응답기가 읽을 수 있는 거부 목록 꼴인지 본다 — 일반
# 파일(링크 아님) · 64 KiB 이하 · 응답기가 읽음. 넘거나 일반 파일이 아니면 응답기는 읽기
# 실패로 다룬다(새 연결 전부 거부 · cut 은 아무것도 끊지 않는다 — 7-4). 어긋나면 일치
# 없음이다.
assert_deny_file_shape() {
  local size
  [[ -f "$1" && ! -L "$1" ]] \
    || mismatch "거부 목록 파일이 없거나 일반 파일이 아니다(7-4): $1"
  size="$(stat -c '%s' -- "$1")"
  (( size <= DENY_MAX_BYTES )) \
    || mismatch "거부 목록이 64 KiB 를 넘는다(${size} 바이트): $1"
  assert_responder_may "$1" 4
}

# assert_ready_to_start_ingest_auth 는 응답기를 (다시) 띄우기 전에 그 입력을 본다(7-4 —
# kty 106차). 거부 목록 파일이 없으면 응답기는 빈 목록으로 떠 거부했던 토큰이 신호 없이
# 다시 붙으므로 멈춘다. 거부 목록은 일반 파일 · 64 KiB 이하 · 응답기가 읽음 · 틀린 줄 0,
# 상태 디렉터리는 10003 소유 · 응답기가 쓸 수 있음을 본다. 인자 $1 이 있으면 살아 있는
# 파일 대신 그 파일(끼울 후보 — 해제 · 되돌림 적용)을 본다.
assert_ready_to_start_ingest_auth() {
  local -r file="${1:-${DENY_FILE}}"
  local bad owner
  [[ -d "${DENY_DIR}" && ! -L "${DENY_DIR}" ]] \
    || mismatch "거부 목록 디렉터리가 없다: ${DENY_DIR}"
  assert_deny_file_shape "${file}"
  assert_responder_may "${DENY_DIR}" 1
  deny_scan "${file}" "${WORK}/ready"
  bad="$(cat -- "${WORK}/ready.bad")"
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
# 같다(commented 면 그 뒤에 '#' 로 시작하는 한 줄만 더 있다 — 사본의 마지막 줄에 끝
# 줄바꿈이 없었으면 그 사이에 줄바꿈 하나) · 새 파일이 64 KiB 이하 일반 파일이고 응답기가
# 읽는다. 어긋나면 일치 없음이고 아무것도 바꾸지 않는다.
assert_unblock_files() {
  local -r copy="$1" new="$2" target="$3" stage="$4"
  local t bad size lines last first
  local -i found=0
  deny_scan "${copy}" "${WORK}/copy" "${target}"
  deny_scan "${new}" "${WORK}/new"
  bad="$(cat -- "${WORK}/copy.bad")"
  [[ "${bad}" == 0 ]] || mismatch "사본에 틀린 줄이 ${bad}개 — 틀린 줄 걷기(10절) 뒤 준비부터"
  bad="$(cat -- "${WORK}/new.bad")"
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
      cp -- "${WORK}/copy.kept" "${WORK}/want.head"
      end_with_newline "${WORK}/want.head"   # 주석은 따로 한 줄이다
      size="$(stat -c '%s' -- "${WORK}/want.head")"
      head -c "${size}" -- "${new}" > "${WORK}/new.head"
      assert_same "${WORK}/want.head" "${WORK}/new.head" \
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
  assert_deny_file_shape "${new}"
}

# assert_walk_files 는 틀린 줄 걷기의 단언이다. 사본 $1 과 새 파일 $2 의 유효 토큰 집합이
# 같다 · 새 파일에 틀린 줄 0 · 틀린 줄 밖의 줄은 차례 · 바이트까지 같다 · 새 파일을
# 응답기가 읽는다. 어긋나면 일치 없음이다.
assert_walk_files() {
  local -r copy="$1" new="$2"
  local bad
  deny_scan "${copy}" "${WORK}/walk-copy"
  deny_scan "${new}" "${WORK}/walk-new"
  bad="$(cat -- "${WORK}/walk-new.bad")"
  [[ "${bad}" == 0 ]] || mismatch "새 파일에 틀린 줄이 ${bad}개"
  assert_same "${WORK}/walk-copy.tokens" "${WORK}/walk-new.tokens" \
    '유효 토큰 집합이 사본과 다르다'
  assert_same "${WORK}/walk-copy.kept" "${new}" '틀린 줄 밖의 줄이 사본과 다르다'
  assert_responder_may "${new}" 4
}

# assert_revert_file 은 사본 되돌림의 단언이다. 되돌릴 파일 $3 의 유효 토큰 집합 = 사본
# $1 의 집합 ∪ 덧붙은 줄 $2 의 집합 · 틀린 줄 0 · 사본의 바이트가 그대로 앞에 있다 ·
# 64 KiB 이하 일반 파일이고 응답기가 읽는다. 어긋나면 일치 없음이다.
assert_revert_file() {
  local -r copy="$1" added="$2" revert="$3"
  local bad size
  deny_scan "${copy}" "${WORK}/rv-copy"
  deny_scan "${added}" "${WORK}/rv-added"
  deny_scan "${revert}" "${WORK}/rv"
  bad="$(cat -- "${WORK}/rv.bad")"
  [[ "${bad}" == 0 ]] || mismatch "되돌릴 파일에 틀린 줄이 ${bad}개"
  sort -u -- "${WORK}/rv-copy.tokens" "${WORK}/rv-added.tokens" \
    > "${WORK}/rv.want"
  assert_same "${WORK}/rv.want" "${WORK}/rv.tokens" \
    '되돌릴 파일의 집합이 사본 ∪ 덧붙은 토큰이 아니다'
  size="$(stat -c '%s' -- "${copy}")"
  head -c "${size}" -- "${revert}" > "${WORK}/rv.head"
  assert_same "${copy}" "${WORK}/rv.head" '되돌릴 파일이 사본으로 시작하지 않는다'
  assert_deny_file_shape "${revert}"
}

# end_with_newline 은 파일 $1 이 비지 않았고 끝 줄바꿈이 없으면 줄바꿈 하나를 붙인다 —
# 뒤에 덧붙일 줄이 끝 줄바꿈 없는 마지막 줄에 붙지 않게.
end_with_newline() {
  if [[ -s "$1" && -n "$(tail -c 1 -- "$1")" ]]; then
    printf '\n' >> "$1"
  fi
}

# deny_name_stamp 는 파일 이름 $1 이 ${DENY_FILE}.$2.<시각> 꼴인지 보고 그 시각을 변수
# $3 에 넣는다. 시각은 준비 블록이 쓰는 꼴(YYYYMMDDTHHMMSSZ)이라 / · .. 가 들 수 없다.
# 꼴이 아니면 검사 실패(인자가 틀렸다)다. 준비가 낸 짝인지는 두 시각이 같은지로 본다.
deny_name_stamp() {
  local -r path="$1" kind="$2"
  local -r stamp="${path#"${DENY_FILE}.${kind}."}"
  [[ "${path}" == "${DENY_FILE}.${kind}.${stamp}" \
    && "${stamp}" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] \
    || check_failed "이름이 ${DENY_FILE}.${kind}.<시각> 꼴이 아니다: ${path}"
  printf -v "$3" '%s' "${stamp}"
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

`.env` 는 `root:root 0600` 이고 값 다섯 줄이다(`.env.example`). 컨테이너는 `.env` 를 읽지
않는다 — `--env-file` 은 상자의 docker CLI 가 root 로 읽는다.

| 줄 | 어디서 오나 |
|---|---|
| `DEMO_HOME` | `/opt/pokeclip-demo` 그대로 |
| `STREAM_KEY_RESOLVE_TOKEN` | auth 의 resolve 전용 출입증. dev auth 쪽과 같은 값이 반영 전에 이미 들어가 있다(사전 확인 단계 — 그때 resolve 200 을 확인한다) |
| `STREAM_KEY_RESOLVE_URL` | `http://<dev EC2 사설 주소>:8082` — 사설 주소여야 한다(공인 주소면 auth 출발지 잠금에 걸려 403) |
| `MTX_API_PASS` | 응답기가 MediaMTX Control API 를 부를 때의 자격. 상자에서 새로 만든다 — 사람이 보지 않는다 |
| `MTX_IMAGE_REGISTRY` | MediaMTX 이미지의 ECR 레지스트리 호스트(`<계정>.dkr.ecr.ap-northeast-2.amazonaws.com`). 상자에서 계정 번호를 얻어 만든다 — 손으로 적지 않고 화면 · 저장소에 내지 않는다(비밀은 아니지만 저장소가 public 이다) |

**R1 — 값 투입은 SSM 대화형 세션 안에서만 한다.** `send-command` 의 인자로 값을 넘기지
않는다 — 명령 이력이 SSM 에 남는다. 세션 로깅이 꺼져 있는지 먼저 본다(켜져 있으면 세션
입력이 S3 · CloudWatch 에 남는다 → 중단).

로깅 설정은 계정의 기본 세션 문서 `SSM-SessionManagerRunShell` 에 든다. 이 문서는
Session Manager 기본 설정을 저장할 때 만들어지고, 세션 기록을 보낼 S3 버킷 ·
CloudWatch Logs 그룹을 담는다 — 문서가 없으면 기록 설정도 없다
([AWS — 기본 세션 문서](https://docs.aws.amazon.com/systems-manager/latest/userguide/getting-started-default-session-document.html) ·
[AWS — CLI 로 Session Manager 기본 설정 바꾸기](https://docs.aws.amazon.com/systems-manager/latest/userguide/getting-started-configure-preferences-cli.html)).
그래서 판정은 셋이다 — 문서 있음(두 칸이 다 빈 값일 때만 세션을 연다) · 문서 없음(통과 —
kty 2026-10-20, 그날 이 계정은 문서가 없었다) · 그 밖 오류(중단 — 못 읽은 것이지 없는 것이
아니다).

kty 로컬 터미널에서 판정 블록 → 세션 열기 순으로, 같은 셸(같은 프로필 · 리전)에서 친다.
판정 블록은 문서 본문이나 오류 문구(호출자 ARN — 계정 번호가 든다)를 찍을 수 있으므로
1-5 처럼 에이전트 세션 · 문서에 붙여 넣지 않는다.

```bash
if DOC_OUT="$(aws ssm get-document --name SSM-SessionManagerRunShell \
    --query Content --output text 2>&1)"; then
  printf '%s\n' "${DOC_OUT}"
  echo '문서 있음 — s3BucketName · cloudWatchLogGroupName 이 둘 다 빈 값일 때만 세션을 연다'
elif [[ "${DOC_OUT}" == *'(InvalidDocument)'*'does not exist'* ]]; then
  echo '통과 — 문서 없음(세션 로깅 설정이 없다)'
else
  printf '%s\n' "${DOC_OUT}"
  echo '중단 — 문서를 읽지 못했다. 세션을 열지 않는다'
fi
unset DOC_OUT
```

「통과」를 봤거나, 「문서 있음」이고 두 칸이 다 빈 값일 때만 세션을 연다.

```bash
aws ssm start-session --target <instance-id>
```

**`STREAM_KEY_RESOLVE_URL` 에 넣는 주소는 눈으로 확인한다** — 같은 VPC 의 사설 주소(dev EC2
사설 IP)여야 한다. 공인 · 오타 주소면 응답기가 연결마다 조회 토큰을 평문 HTTP 로 그 주소에
보낸다. 아래 R1 값 단언 블록은 사설 대역(10/8 · 172.16/12 · 192.168/16)의 IPv4 만
받는다(10단계 반영 전 작업에서 좁혔다 — kty 109 · 110차). 같은 VPC 의 주소인지는 블록이
보지 못한다 — 이웃 서브넷 · 피어링된 VPC · 도커 기본 다리(172.17.x) · 네트워크 · 방송
주소도 사설 꼴이면 통과하므로 눈 확인은 그대로 한다.

세션 **안에서**(반영 전 보존 R0 이 끝난 뒤) 여섯 단계로 나눠 붙인다. 한 번에 붙이면
`sudo bash` 뒤의 줄이 새 root 셸에 들어가지 않거나 `read` 가 그다음 줄을 값으로 삼킨다
(2026-10-20 상자 — 친 주소가 명령으로 실행됐다. SSM 세션 안에서 어느 쪽이었는지는
가리지 못했다). 단계마다 앞 단계의 끝을 눈으로 보고 다음을 붙인다.

1.  root 대화형 셸을 연다. 프롬프트가 `#` 로 바뀐 것을 본다.

    ```bash
    sudo bash
    ```

2.  셸을 준비한다. 표준 입력을 읽는 명령이 없어 한 번에 붙여도 된다.

    ```bash
    unset HISTFILE
    umask 077
    DEMO_DIR=/opt/pokeclip-demo
    INFO="$(stat -c '%u %a' -- "${DEMO_DIR}")"   # root 소유 · 그룹 · 기타 쓰기 없음(2-2)
    # 상자 계정 번호 — 레지스트리 호스트에 든다. 화면에 내지 않는다(저장소에 적지 않음)
    ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
    ```

3.  이 한 줄만 붙이고, 이어서 `http://<dev EC2 사설 주소>:8082` 를 친다(비밀 아님 ·
    저장소에 적지 않음).

    ```bash
    read -r URL
    ```

4.  세 칸을 눈으로 본다 — 친 주소 · `/opt/pokeclip-demo` · `0 750` 같은 꼴(root 소유 ·
    그룹 · 기타 쓰기 없음). 주소가 틀렸으면 3 부터, 나머지가 틀렸으면 2 부터 다시 친다.

    ```bash
    echo "[${URL}] [${DEMO_DIR}] [${INFO}]"
    ```

5.  `.env.next` 를 만들어 끼운다. 끝에 「.env 를 끼웠다」가 나와야 한다.

    ```bash
    if [[ -L "${DEMO_DIR}" || ! "${INFO}" =~ ^0\ [0-7]?[0-7][0145][0145]$ ]]; then
      echo "멈춤: ${DEMO_DIR} 가 root 전용 디렉터리가 아니다(${INFO})"
    elif [[ ! "${ACCOUNT}" =~ ^[0-9]{12}$ ]]; then
      echo '멈춤: 상자 계정 번호를 얻지 못했다(aws sts get-caller-identity)'
    elif [[ -L "${DEMO_DIR}/.env.next" || ( -e "${DEMO_DIR}/.env.next" \
        && ! -f "${DEMO_DIR}/.env.next" ) ]]; then
      echo '멈춤: .env.next 가 일반 파일이 아니거나 링크다 — 쓰지 않았다'
    elif [[ -s "${DEMO_DIR}/.env.pre-T.bak" ]]; then
      {
        grep -E '^(DEMO_HOME|STREAM_KEY_RESOLVE_TOKEN)=' "${DEMO_DIR}/.env"
        printf 'STREAM_KEY_RESOLVE_URL=%s\n' "${URL}"
        printf 'MTX_API_PASS=%s\n' "$(openssl rand -hex 32)"
        printf 'MTX_IMAGE_REGISTRY=%s.dkr.ecr.ap-northeast-2.amazonaws.com\n' \
          "${ACCOUNT}"
      } > "${DEMO_DIR}/.env.next"
      chown root:root "${DEMO_DIR}/.env.next"
      chmod 0600 "${DEMO_DIR}/.env.next"
      command mv -T "${DEMO_DIR}/.env.next" "${DEMO_DIR}/.env" && echo '.env 를 끼웠다'
    else
      echo '멈춤: 반영 전 보존(R0)이 먼저다'
    fi
    ```

6.  「.env 를 끼웠다」를 본 뒤에만 변수를 지우고 root 셸을 닫는다.

    ```bash
    unset URL INFO ACCOUNT
    exit
    ```

5 가 「.env 를 끼웠다」를 내지 않았으면(「멈춤」 · `mv` 실패) 6 으로 가지 않고 같은 root
셸에 남는다. 남았을 수 있는 `.env.next` 를 아래 블록으로 지우고, 원인을 고친 뒤 2 단계부터
다시 친다. 세션이 끊겼으면 새 세션에서 1 · 2 단계를 친 뒤 이 블록으로 지우고 3 단계부터
잇는다. 끝에 「.env.next 남은 개수 0」이 나와야 한다. root 셸이 아니거나 `DEMO_DIR` 이
비었으면 지우지 않고 「멈춤」을 낸다 — 그때는 1 · 2 단계부터 친다.

`DEMO_DIR` 이 root 전용 디렉터리가 아니거나 `.env.next` 가 일반 파일이 아니거나 링크여도
지우지 않고 「멈춤」이다 — 2-2 `remove_box_file` 과 같은 까닭이다. `shred` 와 `>`
리다이렉트는 링크를 따라가 그 대상(예: `.env.pre-T.bak`)을 덮어쓰고, `[[ -e ]]` 만으로는
끊어진 링크를 찾지 못한다(그래서 `-L` 을 함께 본다). 5 단계도 같은 까닭으로 링크인
`.env.next` 에는 `>` 로 쓰지 않고 「멈춤」을 낸다. 재시도나 5 단계가 이 셋(링크 · 일반
파일 아님 · root 전용 아님)으로 「멈춤」을 내면 지우거나 고치지 말고 `ls -la "${DEMO_DIR}"`
출력을 들고 kty 가 정한다.

```bash
if [[ "$(id -u)" != 0 || -z "${DEMO_DIR:-}" ]]; then
  echo '멈춤: root 셸(1 단계)이 아니거나 DEMO_DIR 이 비었다(2 단계) — 지우지 않았다'
elif [[ -L "${DEMO_DIR}" || ! -d "${DEMO_DIR}" \
    || ! "$(stat -c '%u %a' -- "${DEMO_DIR}")" \
    =~ ^0\ [0-7]?[0-7][0145][0145]$ ]]; then
  echo "멈춤: ${DEMO_DIR} 가 root 전용 디렉터리가 아니다 — 지우지 않았다"
elif [[ -L "${DEMO_DIR}/.env.next" || ( -e "${DEMO_DIR}/.env.next" \
    && ! -f "${DEMO_DIR}/.env.next" ) ]]; then
  echo '멈춤: .env.next 가 일반 파일이 아니거나 링크다 — 지우지 않았다'
else
  [[ ! -e "${DEMO_DIR}/.env.next" ]] || shred -u -- "${DEMO_DIR}/.env.next"
  if [[ -e "${DEMO_DIR}/.env.next" || -L "${DEMO_DIR}/.env.next" ]]; then
    echo '멈춤: .env.next 가 남았다 — 지우지 못했다'
  else
    echo '.env.next 남은 개수 0'
  fi
fi
```

- 옛 두 줄(`DEMO_PATH` · `SRT_PASSPHRASE`)은 옮기지 않는다 — 반영 전 값은 R0 의
  `.env.pre-T.bak` 에 있다. resolve 출입증 줄은 이미 들어간 줄을 그대로 옮긴다.
- `MTX_API_PASS` 는 `printf` 내장 명령이 받아 파일에만 쓴다 — 화면 · 명령 인자에 나오지
  않는다.
- `MTX_IMAGE_REGISTRY` 의 계정 번호는 상자의 인스턴스 역할로 얻는다 —
  `aws sts get-caller-identity` 는 권한 없이 돈다. 변수와 `.env` 에만 있고 화면에
  나오지 않는다. T `.env` 에 이미 이 줄이 있어도 옮기지 않고 다시 만든다.
- `.env.next` 를 다 쓴 뒤 이름 바꾸기로 끼우므로 세션이 끊겨도 반쪽 `.env` 가 생기지
  않는다. 남은 `.env.next` 는 비밀(출입증 · API 비번)이 든 파일이라 위 블록으로 지우고
  다시 친다.
- 끼우기는 `command mv` 다 — AL2023 의 root 대화형 셸은 `mv` 가 `mv -i` 별칭이라
  덮어쓰기를 묻고, 붙여 넣은 다음 줄이 그 답으로 들어간다(2026-10-20 상자에서 물었다).
  거절돼도 `mv -i` 는 종료 0 이므로(GNU coreutils 8.32) 끼웠는지는 끝의 「.env 를
  끼웠다」로만 안다.

이어서 꼴을 단언한다(값은 내지 않는다).

```bash
# ── 블록: R1 값 단언 — 머리 + 이 본문. 인자 없음. 읽기만 ──
# .env 가 root:root 0600 이고 다섯 줄이 하나씩 꼴대로 있는지 본다. 값은 내지 않는다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local info line key
  local -A seen=()
  # resolve 주소는 사설 IPv4(RFC 1918 — 10/8 · 172.16/12 · 192.168/16)만
  # 받는다. 옥텟은 0–255 이고 앞자리 0 은 받지 않는다.
  local octet='(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])'
  local prefix="(10\.${octet}|172\.(1[6-9]|2[0-9]|3[01])|192\.168)"
  local url_re="http://${prefix}(\.${octet}){2}:8082"
  # 레지스트리는 계정 ECR(ap-northeast-2)의 호스트 하나 — 경로 · 포트 없이
  local registry_re='[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com'
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
        [[ "${line}" =~ ^STREAM_KEY_RESOLVE_URL=${url_re}$ ]] \
          || mismatch 'STREAM_KEY_RESOLVE_URL 이 http://<사설 IPv4>:8082 꼴이 아니다'
        ;;
      STREAM_KEY_RESOLVE_TOKEN | MTX_API_PASS)
        [[ "${line}" =~ ^[A-Z_]+=[0-9a-f]{64}$ ]] \
          || mismatch "${key} 가 64자 소문자 hex 가 아니다"
        ;;
      MTX_IMAGE_REGISTRY)
        [[ "${line}" =~ ^MTX_IMAGE_REGISTRY=${registry_re}$ ]] \
          || mismatch 'MTX_IMAGE_REGISTRY 가 계정 ECR(ap-northeast-2) 호스트 꼴이 아니다'
        ;;
      *) mismatch '모르는 줄이 있다 — 옛 DEMO_PATH · SRT_PASSPHRASE 를 빼야 한다' ;;
    esac
    seen["${key}"]=$(( ${seen["${key}"]:-0} + 1 ))
  done < "${DEMO_DIR}/.env"
  for key in DEMO_HOME STREAM_KEY_RESOLVE_URL STREAM_KEY_RESOLVE_TOKEN \
    MTX_API_PASS MTX_IMAGE_REGISTRY; do
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

- **사전 확인(읽기)이 끝났다** — 체크아웃 상태 · 이미지 · 도구 판(2-2 머리의 전제 ·
  아래 buildx 판) · 디스크 · 시계 · 보안 그룹(SRT 인바운드가 팀 IP/32 만) · dev auth 의
  resolve 200.
- **docker buildx 가 0.17 이상이다.** compose v5 의 `compose build`(R3)가 buildx 0.17
  이상을 요구한다. 낮으면 R3 이 `compose build requires buildx 0.17.0 or later` 로
  실패한다(F3). 1-2 의 부팅 스크립트는 compose 를 최신 판으로 받지만 buildx 는 docker
  패키지의 판 그대로라 어긋날 수 있다 — 10단계 반영(2026-10-19)에서 상자 buildx 가
  0.12.1 이라 R3 이 실패했다.

  ```bash
  # 확인 — 0.17 이상이어야 한다
  sudo docker buildx version
  ```

  모자라면 공식 릴리스 바이너리를 sha256 확인 뒤
  `/usr/local/lib/docker/cli-plugins/docker-buildx` 에 둔다(승인). 이 자리는 패키지가
  까는 자리보다 먼저 쓰인다 — 패키지 판은 지우지 않아도 된다. 해시는 2026-10-19 에
  v0.38.0 릴리스의 `checksums.txt` 에서 옮겼고 같은 날 받은 바이너리의 sha256 과
  대조했다. 판을 올릴 때는 주소와 해시를 함께 고친다. 받기 · 확인 · 두기는 root 가 만든
  전용 임시 디렉터리(`mktemp -d` — 0700)에서 한 사슬로 돌린다. 남이 미리 만들어 둔 파일을
  검사하거나 둘 수 없고, 어느 단계든 실패하면 뒤 단계로 넘어가지 않는다. 끝에 판이 0.17
  이상인지 단언한다. 어긋나면 「멈춤」 줄을 내고 종료 1 이다 — 괄호(서브셸) 안이라 붙여
  넣은 세션은 닫히지 않는다.

  ```bash
  # 받기 · 확인 · 두기 · 판 단언(승인) — 실패하면 「멈춤」 · 종료 1
  (
    set -o pipefail
    T="$(sudo mktemp -d)" || { echo '멈춤 — 임시 디렉터리를 만들지 못했다'; exit 1; }
    sudo curl -fsSL -o "${T}/docker-buildx" \
        https://github.com/docker/buildx/releases/download/v0.38.0/buildx-v0.38.0.linux-amd64 \
      && echo "4fe4cc38adf48169132749b6ca22a990928db0118e3407584ee553723115d287  ${T}/docker-buildx" \
        | sudo sha256sum -c - \
      && sudo install -o root -g root -m 0755 "${T}/docker-buildx" \
        /usr/local/lib/docker/cli-plugins/docker-buildx
    rc=$?
    sudo rm -rf -- "${T}"
    [ "${rc}" -eq 0 ] || { echo '멈춤 — 받기 · 해시 · 두기 가운데 하나가 실패했다'; exit 1; }
    sudo docker buildx version \
      | awk '{ v = $2; sub(/^v/, "", v); split(v, n, "."); ok = (n[1] + 0 > 0 || n[2] + 0 >= 17) }
             END { exit !ok }' \
      || { echo '멈춤 — buildx 가 0.17 미만이거나 판을 읽지 못했다'; exit 1; }
    sudo docker buildx version
  )
  ```

- **상자 역할에 ECR 읽기 권한이 있다(1-5).** R3′ 가 인스턴스 역할로 ECR 에 로그인해
  MediaMTX 이미지를 받는다. 없으면 R3′ 가 로그인 · 받기에서 검사 실패(종료 2)로 멈춘다(F3
  — R3′ 의 종료 1 갈래도 그 행에 있다).

- **hls.js 가 compose.yml 옆에 있다.** 재생 페이지는 hls.js 를 같은 오리진
  `/hls.min.js` 에서 받는다(MediaMTX 동봉본 의존은 실측에서 실패해 폐기). compose 가
  `./hls.min.js` 를 마운트하므로 없으면 내려받는다. 판은 1.7.3 으로 고정한다. 받기와
  확인을 나눈다 — 받기는 파일이 없을 때만 돌고, 확인(sha384 단언)은 파일이 이미 있어도 돈다.
  확인이 `FAILED` 면 `&&` 뒤의 `chown` 이 돌지 않는다 — 그 자리에서 멈추고, 그 파일을
  지울지(승인 — 이미 있던 파일이면 다른 판일 수 있다) kty 가 정한다. 해시는 2026-10-17
  23:24 KST 에 jsDelivr 에서 받아 셈했다(jsDelivr 목록의 sha256 · npm 묶음 `hls.js-1.7.3.tgz`
  의 같은 파일과 바이트 대조). 판을 올릴 때는 새 판을 받아 해시를 다시 셈하고 받기 · 확인
  두 줄을 함께 고친다.

  ```bash
  # 받기 — 파일이 없을 때만
  sudo test -e /opt/pokeclip-demo/repo/infra/dev-media/hls.min.js \
    || sudo curl -fsSL -o /opt/pokeclip-demo/repo/infra/dev-media/hls.min.js \
      https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js
  # 확인 — 있든 받았든. FAILED 면 chown 으로 넘어가지 않는다
  echo '71c889d338bc775b8c282db325dee3bcf6381d0b7b5b8072508fc594c925b6f062df5696eb5adca55061a665bcfcdc17  /opt/pokeclip-demo/repo/infra/dev-media/hls.min.js' \
    | sudo sha384sum -c - \
    && sudo chown 10002:10002 /opt/pokeclip-demo/repo/infra/dev-media/hls.min.js
  ```

- **체크아웃의 세 칸을 반영 전에 본다(읽기).** 블록은 이 셋을 보지 않는다 — 어긋나면
  반영하지 않고 멈추고 kty. 소유자는 아래 첫 명령의 `%U` 다.

  ```bash
  sudo stat -c '%U:%G' /opt/pokeclip-demo/repo
  sudo -u <체크아웃 소유자> git -C /opt/pokeclip-demo/repo status --porcelain \
    --untracked-files=normal
  # 통째 추적 밖 디렉터리의 소유 · 모드 기록 — 출력을 반영 기록에 남긴다(11-5 알려진 예외)
  sudo -u <체크아웃 소유자> git -C /opt/pokeclip-demo/repo status --porcelain \
    --untracked-files=normal | sed -n 's|^?? \(.*\)/$|\1|p' \
    | while IFS= read -r d; do sudo stat -c '%U:%G %a %n' "/opt/pokeclip-demo/repo/${d}"; done
  sudo -u <체크아웃 소유자> git -C /opt/pokeclip-demo/repo diff --cached --stat
  sudo stat -c '%a %U:%G %n' /opt/pokeclip-demo/repo/infra/dev-media/player.html \
    /opt/pokeclip-demo/repo/infra/dev-media/hls.min.js
  ```

  - **소유자 · 통째 추적 밖 디렉터리** — 소유자가 root 가 아니고 `status` 에 `/` 로 끝나는
    `??` 줄(안의 파일이 모두 추적 밖인 디렉터리)이 있으면 멈춘다. 롤백 B · D 가 그 디렉터리를
    `root:root 0755` 로 다시 만들어 다음 B · D 의 `git clean` 이 권한 실패로 멈춘다(11-5
    머리의 알려진 예외). `--untracked-files=normal` 은 체크아웃 설정
    (`status.showUntrackedFiles` 가 `all` · `no`)과 무관하게 그 줄을 내게 한다. 「기록」
    주석 아래 명령의 `stat` 출력(소유 · 모드)은 소유자와 무관하게 반영 기록에 남긴다 —
    11-5 예외에서 되돌릴 기준이다. 이름에 특수 문자가 있어 `git` 이 따옴표로 감싼 디렉터리
    줄은 이 기록 명령에서 오류 없이 빠진다 — `?? "…/"` 줄이 보이면 그 디렉터리는 손으로
    `stat` 해 기록한다.
  - **스테이징** — `diff --cached --stat` 출력이 비어 있지 않으면 멈춘다. 롤백 B ③ 이 이유
    문구 없이 「일치 없음」으로 멈추고, 다시 돌리면 `already exists` 로 멈춘다(로컬 실측
    S7-7 L9).
  - **edge 가 읽는 파일의 기타 읽기 비트** — `%a` 의 마지막 자리가 4 · 5 · 6 · 7 이 아니면
    멈춘다. edge 가 읽지 못해 재생 페이지가 403 이다(로컬 실측 S7-4 — 0640 root:root 에서
    403).

- 지금 서비스 사용자가 없어 시각 제약은 없다. 반영 중 송출 끊김 길이는 기록만 한다.

### 4-2. 반영 차례

| 단계 | 블록 | 하는 일 | 멈추면 |
|---|---|---|---|
| R0 | R0 반영 전 보존 | T 백업이 없음을 단언 → 반영 전 백업 아홉(`pre-T`) | F0 |
| R1 | 3절 값 투입 → R1 값 단언 | T `.env` | F1 |
| R2 | R2 T 체크아웃 | 손 수정을 버리고(패치에 있다) T SHA 로 분리 체크아웃 | F2 · F2′ |
| R3 | R3 응답기 이미지 빌드 | `build ingest-auth` · 이미지 User 단언 | F3 |
| R3′ | R3′ MediaMTX 이미지 받기 | ECR 로그인(블록 안 임시 자격) → `pull mediamtx` → 자격 지움 · 이미지 있음 단언 | F3 |
| R4 | R4 렌더 | `${DEMO_HOME}` 소유 단언 → render.sh → `chown root:10002` · `chmod 0640` → 소유 · 모드 단언 | F4 |
| R5 | R5 응답기 기동 | 디렉터리 준비 · 거부 목록 확인 → `up -d ingest-auth` → healthy(120초) | F5 |
| R6 | R6 전체 기동 | `up -d`(MediaMTX · edge 재생성) → 판 · 아치 · 좌표 · 권한 오류 · `MTX_` 단언 | F6 |

순서의 까닭.

- **`.env` 를 먼저, 체크아웃을 그다음에.** 옛 compose 는 `${DEMO_HOME}` 하나만 보간해 T
  `.env` 로도 풀리고, T compose 는 T `.env` 가 있어야 풀린다(`:?`).
- **빌드를 렌더 · `up` 앞에.** 빌드가 실패하면 렌더 산출물과 컨테이너를 바꾸기 전에
  멈춘다.
- **이미지 받기도 렌더 · `up` 앞에.** R6 의 `up -d` 는 상자에 있는 이미지를 다시 받지
  않는다. 받기를 R6 에 맡기면 로그인 없이 받다가 컨테이너를 바꾸는 도중(F6)에 실패하고,
  그 오류에 레지스트리 호스트(계정 번호)가 찍힌다.
- **응답기 healthy 뒤에 MediaMTX.** 운영 순서(7절)다.
- 반영 중 옛 MediaMTX 는 옛 설정으로 돈다 — 렌더 산출물은 단일 파일 마운트라 이름
  바꾸기로 바뀐 새 파일을 옛 컨테이너가 보지 않는다(추론 — 로컬 실측에서 확인).
- **R4 뒤 · R6 앞에 옛 MediaMTX 가 다시 시작되면 모든 송출이 끊긴다.** 재시작 정책이
  같은 컨테이너를 다시 띄우면(프로세스가 죽음 · 엔진 재시작 · 상자 재부팅) 마운트가 새로
  묶여 새 렌더를 읽는다. 옛 컨테이너는 제어망에 붙어 있지 않아 새 렌더의 Control API
  주소를 묶지 못하고 기동을 되풀이한다(로그
  `listen tcp 10.250.0.2:9997: bind: cannot assign requested address`). 이어서
  R5 · R6 을 돌리면(R5 를 이미 돌았으면 R6 만) R6 이 MediaMTX 를 제어망과 함께 새로 만들어
  풀린다. 접으려면 롤백 0 → A → B → C 다 — B 가 반영 전 렌더를 되살려 옛 판을 새로
  만든다(11절). 로컬 실측 fix3 E6 — 이어서 돌리기 R4 뒤 3/3 · R5 뒤 1/1, 접기 1/1.

```bash
# ── 블록: R0 반영 전 보존 — 머리 + 보존 본문 + 이 본문. 인자 없음 ──
# T 백업이 하나라도 남아 있으면 아무것도 만들지 않고 멈춘다(종료 1) — 접은 뒤 다시 반영할
# 때는 T 백업 처분(11절)이 먼저다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  assert_demo_dir_safe
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
  assert_demo_dir_safe
  [[ "${t_sha}" =~ ^[0-9a-f]{40}$ ]] || check_failed 'T SHA 는 40자 소문자 hex 다'
  for name in .env.pre-T.bak mediamtx.demo.yml.pre-T.bak pre-T.sha pre-T.head \
    pre-T.docker pre-T.untracked.tgz; do
    [[ -s "${DEMO_DIR}/${name}" ]] || mismatch "반영 전 백업이 없다: ${name} — R0 부터"
  done
  for name in pre-T.status pre-T.worktree.patch pre-T.untracked.z; do
    [[ -e "${DEMO_DIR}/${name}" ]] || mismatch "반영 전 백업이 없다: ${name} — R0 부터"
  done
  pre_sha="$(cat -- "${DEMO_DIR}/pre-T.sha")"
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
  pre_sha="$(cat -- "${DEMO_DIR}/pre-T.sha")"
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
# ── 블록: R3′ MediaMTX 이미지 받기 — 머리 + 이 본문. 인자 없음 ──
# compose 가 가리키는 MediaMTX 이미지를 계정 ECR 에서 받아 둔다 — R6 의 up -d 가 받지
# 않게. 컨테이너 · 렌더 산출물은 건드리지 않는다. 레지스트리 호스트는 compose 가 .env 의
# MTX_IMAGE_REGISTRY 로 푼 좌표에서 읽는다. 호스트에 계정 번호가 들어 있어 내지 않는다 —
# 도구 출력은 파일로 받고, 실패하면 12자리 숫자를 가려 낸다. 로그인 자격(12시간 유효)은
# 상자의 정해진 자리(LOGIN_DIR — DOCKER_CONFIG)에만 두고 받은 뒤 지운다(dispose_ecr_login)
# — /root/.docker 에 남기지 않는다. 블록이 죽어 자격이 남으면 다음 R3′ 의 첫머리와 비밀 임시
# 파일 정리(dispose_stray_secrets)가 지운다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local image='' registry tag line
  local -i rc=0 n=0
  assert_demo_dir_safe
  command -v aws > /dev/null || check_failed 'aws CLI 가 없다(1-5)'
  dispose_ecr_login   # 끊긴 R3′ 가 남긴 자격
  # mediamtx 가 기대는 서비스(ingest-auth)의 이미지도 함께 나온다 — 저장소 경로로 고른다
  live_compose config --images mediamtx > "${WORK}/mtx.images"
  while IFS= read -r line; do
    if [[ "${line}" == */pokeclip/mediamtx:* ]]; then
      image="${line}"
      n+=1
    fi
  done < "${WORK}/mtx.images"
  (( n == 1 )) || mismatch "compose 의 pokeclip/mediamtx 좌표가 하나가 아니다(${n}개)"
  registry="${image%%/*}"
  [[ "${registry}" =~ ^[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com$ ]] \
    || mismatch 'MediaMTX 좌표의 레지스트리가 계정 ECR(ap-northeast-2)이 아니다'
  install -d -m 0700 -o root -g root -- "${LOGIN_DIR}"
  aws ecr get-login-password --region ap-northeast-2 2>> "${WORK}/pull.log" \
    | DOCKER_CONFIG="${LOGIN_DIR}" docker login --username AWS \
      --password-stdin "${registry}" >> "${WORK}/pull.log" 2>&1 \
    || rc=$?
  if (( rc == 0 )); then
    DOCKER_CONFIG="${LOGIN_DIR}" live_compose pull mediamtx \
      >> "${WORK}/pull.log" 2>&1 || rc=$?
  fi
  dispose_ecr_login   # 성패와 무관하게 먼저
  if (( rc != 0 )); then
    mask_account "${WORK}/pull.log" >&2
    check_failed "ECR 로그인 · 받기 실패(종료 ${rc}) — 역할 권한(1-5) · 망을 본다"
  fi
  docker image inspect -f '{{.Id}}' "${image}" > /dev/null 2>&1 \
    || mismatch '받은 뒤에도 MediaMTX 이미지가 상자에 없다'
  tag="${image#*:}"
  tag="${tag%%@*}"
  echo "MediaMTX 이미지 받기 끝 — ${tag} · 로그인 자격 지움"
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
  local info
  # 렌더 자리에 남이 쓸 수 없다 — root 소유 · 그룹 · 기타 쓰기 없음
  assert_demo_dir_safe
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
  assert_demo_dir_safe
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
# 이미지 좌표는 레지스트리 호스트(계정 번호)를 떼고 낸다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local mtx_id='' image tag arch line want_arch="${DEMO_ARCH:-amd64}"
  local -i i found=0 version=0 denied=0 unsaved=0 mtx_env=0
  wait_ingest_auth_healthy   # 운영 순서 — 응답기 healthy 뒤에만 MediaMTX
  compose_up_masked live_compose -d   # 출력에 이미지 좌표가 찍힐 수 있다 — 가려 낸다
  container_id mediamtx mtx_id
  image="$(docker inspect -f '{{.Config.Image}}' "${mtx_id}")"
  live_compose config --images > "${WORK}/images"
  while IFS= read -r line; do
    if [[ "${line}" == "${image}" ]]; then
      found=1
    fi
  done < "${WORK}/images"
  (( found )) || mismatch "도는 이미지가 compose 의 좌표가 아니다: ${image#*/}"
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
| F0 | R0 보존 | 반영 전 그대로 · 만든 백업만 | 다시 돌리면 이어진다. 첫머리 단언(T 백업 남음)에서 멈췄으면 접은 뒤 처분을 빠뜨렸다 — T 백업 처분(11절) 뒤 다시(T 가 도는 리허설 뒤면 E-1 → E-2). 보존 단언이 어긋나면(종료 1) 원인이 둘이다 — 남은 반영 전 기록(`pre-T.*`)이 직전 반영 주기의 것이거나(T 가 정착한 뒤의 다음 반영 — 롤백 창을 닫고 반영 전 일곱을 옮긴 뒤 다시, 11-7 ③ · kty 승인), 이번 보존 도중 반영 전 상태가 바뀌었다(반영을 멈추고 kty). 어느 쪽인지는 `pre-T.sha` 와 지금 HEAD 로 가린다 |
| F1 | R1 값 투입(세션 도중 끊김 · 꼴 단언 실패) | HEAD · 트리 · 렌더 · 컨테이너 = 반영 전(옛 판이 그대로 돈다 — 옛 compose 는 `${DEMO_HOME}` 하나만 보간하고 옛 MediaMTX 는 `.env` 를 다시 읽지 않는다). `.env` = 반영 전 또는 T | 반영 취소 ⓐ → ⓒ. 또는 값을 고쳐 다시 넣고 R2 로 |
| F2 | R2 체크아웃 — git 이 거부(추적 밖 파일과 겹침) | HEAD = 반영 전 · 손 수정은 버려짐(패치에 있다) · `.env` T · 렌더 · 컨테이너 반영 전 | 반영 취소 ⓑ → ⓐ → ⓒ. 겹친 파일은 kty 가 정한 뒤(옮김) R2 를 다시 |
| F2′ | R2 체크아웃 도중 끊김(프로세스가 죽음) | 트리 일부만 T · `.git/index.lock` 이 남을 수 있다 · HEAD 는 대개 반영 전(추론) | kty — 남은 lock · 트리 상태를 보고 정한다(lock 지움은 승인). HEAD 가 T 면 F3 행 |
| F3 | R3 빌드 실패 · R3′ 이미지 받기 실패 | HEAD · 트리 = T · `.env` T · 렌더 · 컨테이너 = 반영 전(옛 판이 돈다) | 원인(디스크 · 모듈 · GHCR · buildx 판 — `compose build requires buildx 0.17.0 or later` 면 4-1 의 buildx 칸 · R3′ 면 아래 둘)을 고쳐 멈춘 블록부터. R3′ 는 종료 2 — aws CLI 없음 · ECR 로그인 · 받기 실패(ECR 권한(1-5) · 망 — 출력은 계정 번호를 가린다) — 또는 종료 1 — compose 의 pokeclip/mediamtx 좌표가 하나가 아니거나 레지스트리가 계정 ECR(ap-northeast-2)이 아님 · 받은 뒤에도 이미지가 없음 · `.ecr-login` 이 링크 · 디렉터리 아님이거나 `config.json` 밖의 모르는 파일이 듦(이름을 보이고 지우지 않는다 — kty)이다. 접으려면 반영 취소 ⓓ → ⓑ → ⓐ → ⓒ(컨테이너를 건드리지 않고 T 백업을 만들지 않는다). 다시 T 로 갈 때는 곧바로 R0 부터 |
| F4 | R4 렌더 가드 실패(종료 1) · 소유 단언 실패 | F3 과 같다 — 렌더 산출물은 그대로(render.sh 가 임시 파일을 지운다) · 새 응답기 이미지는 남는다. 소유 단언에서 멈췄으면 새 산출물이 얹혔다 — `chown` · `chmod` 를 고쳐 R4 의 단언부터 | F3 과 같다 |
| F5 | R5 healthy 시한 초과 | HEAD · 트리 · `.env` · 렌더 = T · `ingest-auth` 만 떠 있고 unhealthy · MediaMTX 는 옛 컨테이너가 옛 설정으로 돈다(추론 — 그사이 다시 시작됐으면 새 렌더로 기동을 되풀이하고 송출이 모두 끊겨 있다, 4-2) | 응답기 로그로 원인을 고쳐 R5 부터. 원인이 거부 목록의 틀린 줄이면 손으로 고치지 않고 틀린 줄 걷기(10절 — 재시작 없음) 뒤 R5 부터. 접으려면 롤백 0 → A → B → C(11절). 다시 T 로 갈 때는 T 백업 처분 → R0 부터 — D 를 쓰지 않는다. 접는 중 B · C 에서 막혀도 D 로 돌아가지 않는다 — kty 판단으로 원인을 고쳐 B · C 를 끝낸다 |
| F6 | R6 `up -d` 실패 · MediaMTX 기동 실패 | T 가 일부 돈다 · 송출이 끊겼을 수 있다 | 롤백 0 → A → B → C. 다시 T 로 — F5 와 같다 |
| F7 | 실측 1 · 6 · 7 실패(그 자리에서 고칠 수 없음) | T 가 돈다 | 롤백 0 → A → B → C. 다시 T 로 — F5 와 같다 |

**블록이 멈추지 않았는데 R4 뒤 · R6 앞에 송출이 모두 끊겼으면** 위 표의 행이 아니다(R5 가
healthy 여도 생긴다). MediaMTX 가 재시작을 되풀이하고 로그에
`listen tcp 10.250.0.2:9997: bind: cannot assign requested address` 가 있으면 옛
MediaMTX 가 새 렌더로 다시 뜬 것이다(4-2). 이어서 R5 · R6(R5 를 돌았으면 R6 만)을
돌리거나, 접으려면 롤백 0 → A → B → C 다.

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
  assert_demo_dir_safe
  pre_sha="$(cat -- "${DEMO_DIR}/pre-T.sha")"
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
  assert_demo_dir_safe
  pre_sha="$(cat -- "${DEMO_DIR}/pre-T.sha")"
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
  assert_demo_dir_safe
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
  PRE_SHA="$(cat -- "${DEMO_DIR}/pre-T.sha")"
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

**송출 전 OBS 쪽 두 가지**(10단계 실측 2026-10-19).

- 수신 호스트는 버전 표기가 아니라 빌드로 가른다(둘 다 0.2.0 이다). 실측에 쓴
  설치본(2026-10-01 빌드)은 기본값이 옛 `ingest.pokeclip.com`(DNS 레코드 없음)이라
  플러그인 고급 설정에서 `media-dev.pokeclip.com` 으로 바꿔야 한다. 옛 기본값 이관이 든
  빌드(커밋 6dec5a2c 이후)는 dev 환경 설정이면 손대지 않은 옛 기본값을 이 값으로
  스스로 옮긴다.
- OBS 「설정 → 방송」 은 방송 플랫폼의 주소여야 한다. 데모 시절의 공용 경로가 남아
  있으면 OBS 자체 출력이 `path is not configured` 로 실패하고 플러그인 출력도 함께
  멈춘다.

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
  없다. ①(첫 줄)은 200 + 변형 URI 한 줄, ②(둘째 줄)는 마커다.

  ```bash
  curl -s "http://<host>:8888/<토큰>/index.m3u8"
  curl -s "http://<host>:8888/<토큰>/<위에서 얻은 URI>"
  ```

  ②의 합격 조건 — `EXT-X-PART` · `EXT-X-PART-INF` · `EXT-X-PRELOAD-HINT` ·
  `CAN-BLOCK-RELOAD` 존재 + `EXTINF` 가 4.0 근처. `PART-HOLD-BACK` 은 값을 기록만 한다.
- GOP 는 설정값을 믿지 않고 HLS 디먹서로 잰다 — `key_frame=1` 행의 `pts_time` 간격이
  2.0초여야 한다. 몇 초 뒤 Ctrl-C 로 멈춘다.

  ```bash
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

- **MediaMTX 를 (다시) 띄우기 전에 응답기가 healthy 인지 본다.** 응답기가 답하지 못하면
  새 연결이 모두 거부되고, MediaMTX 의 인증 요청 하나가 응답기 모양에 따라 최대 10초를
  붙잡아 줄이 쌓인다. 갈래별 값(로컬 실측 S7-4 · S7-5 · fix3 E3 — 상자 값은 재지 않았다):
  컨테이너는 있고 프로세스가 듣지 않으면(다시 시작 · 기동 중) 곧바로
  `connection refused` 다. 컨테이너가 멈춰 고정 주소(`10.250.0.3`)에 주인이 없으면, 이웃
  캐시(ARP — IP 주소와 장치 주소의 짝을 기억하는 표)에 그 주소가 남아 있을 때(방금까지
  살아 있었다) 10초(MediaMTX 인증 시한 `readTimeout` — `context deadline exceeded`),
  비었으면 약 3초(`connect: no route to host`)다. 연결은 받고 답이 없으면(프로세스가
  얼었다) 10초다.
- **응답기 고장은 응답기를 먼저 살려 푼다**(7-2 — 컨테이너가 없거나 멈췄으면 「응답기
  살리기」, 떠 있는데 고장이면 「응답기 다시 시작」). MediaMTX 재시작으로
  풀지 않는다 — 진행 중 송출까지 끊겨 한꺼번에 다시 붙으며 같은 줄을 스스로 만든다.
- **MediaMTX 재시작은 최후 수단이고 한 번만 쓴다(되풀이 금지).** 진행 중 송출이 모두
  끊긴다(플러그인 재시도가 5초 뒤 다시 붙인다). 쓰는 때는 8-3 의 갈래가 가리킬 때뿐이다.
- **응답기를 다시 띄우기 전에 거부 목록 파일을 확인한다**(7-4). 응답기를 띄우거나 다시
  시작하는 블록은 모두 `assert_ready_to_start_ingest_auth` 를 먼저 부른다. 파일을 바꿔
  끼우고 다시 시작하는 해제 적용 · 되돌림 적용은 끼울 후보 파일을 본다(64 KiB 이하 ·
  일반 파일 · 응답기가 읽음 · 틀린 줄 0, 그리고 상태 디렉터리).
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
# 응답기 컨테이너가 없거나 멈췄을 때. 입력을 확인한 뒤 up -d ingest-auth → healthy. 떠
# 있는 컨테이너에는 아무것도 하지 않는다 — 떠 있는데 고장이면 「응답기 다시 시작」이다.
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
| `ingestauth_ip_limited` `ip=…` `ip_kind=…` | 송출자 IP 하나가 시도 한도(기본 30번 몰아서, 그 뒤 3초에 하나)에 걸렸다 — 에피소드마다 한 줄. 그동안의 요청별 거절은 DEBUG 의 `publish_denied` `reason=ip_limited` 로만 난다. `ip_kind=public` 이 정상이다 — 공인 송출자인데 `private` 면 출발지가 바뀐 것(전원이 한 키로 묶인다 — 7-5 로 끈다) |
| `ingestauth_ip_limit_released` `denied=…` `limited_for_ms=…` | 그 IP 의 통이 다시 다 찼다(에피소드 끝). 같은 IP 가 돌아오지 않으면 늦게 나오거나 안 나올 수 있다 |
| `ingestauth_ip_limit_released_summary` `count=…` `denied_total=…` | 정리 한 번에 끝난 에피소드가 32개를 넘었다 — 넘은 몫을 한 줄로 묶었다(개수 · 거절 합) |
| `ingestauth_ip_limit_full` · `_recovered` `state_seq=…` | 기록하는 IP 가 상한(16,384)에 닿아 새 IP 를 한도 밖에 세우는 중 · 풀림. `state_seq` 가 가장 큰 줄이 지금 상태다(아래) |
| `ingestauth_ip_limit_unkeyed` `ip_kind=other` | 본문 `ip` 가 한도 키를 만들 수 없는 주소라 한도 밖에 세웠다 — 처음 한 번만. IP 로 해석된 값만 오므로 나오지 않는 것이 정상이다(추론) |
| `ingestauth_caller_basic_off` (WARN, 기동마다 한 줄) | 호출자 Basic 검사를 명시로 껐다(`INGEST_AUTH_CALLER_BASIC=off`). dev-media 는 제어망이 따로 있어 출발지 검사가 MediaMTX 를 가르므로 의도된 상태다 — 고장이 아니다. prod 에서 보이면 설정 누락이다 |
| MediaMTX `connection not found` ERR | cut 이 kick 한 연결이 이미 없어 404 가 났다 — 예상된 것이다. 가득 찬 cut 하나면 약 17,000줄이 난다 |

- **`ingestauth_ip_limit_full` 이 나면 새 IP 는 한도 없이 처리 시간 예산만 탄다
  (fail-open).** 지금 상태는 기간을 자르지 않고 기동 줄과 함께 본다(`--since` 로
  자르면 오래전에 한 번 난 `full` 이 빠져 0줄이 된다).

  ```bash
  sudo docker compose -p dev-media --env-file /opt/pokeclip-demo/.env \
    -f /opt/pokeclip-demo/repo/infra/dev-media/compose.yml \
    logs --no-log-prefix ingest-auth \
    | grep -E '"msg":"ingestauth_(started|ip_limit_(full|recovered))"'
  ```

  출력에서 **마지막 `ingestauth_started` 줄 뒤만** 본다. 그 뒤의 `full` · `recovered`
  가운데 **`state_seq` 가 가장 큰 줄**이 지금 상태다. 전이는 한도기 잠금 안에서 번호를
  받고 줄은 잠금 밖에서 쓰여, 줄의 차례로는 가릴 수 없다. 번호는 응답기가 다시 뜨면
  1 부터 다시 센다. 마지막 기동 뒤에 `full` · `recovered` 가 없으면 「상한 경보
  없음(정상)」이다. `ingestauth_started` 줄조차 없으면(로그 회전 · 컨테이너 교체로
  잘림) 「상태 미확인」이다 — kty 에게 알린다. `full` 이 가장 큰 번호여도 이미 풀렸을
  수 있다 — `recovered` 는 새 IP 가 올 때만 나서, 홍수가 끝난 뒤 새 송출이 없으면
  `full` 이 마지막으로 남는다. 항목은 보통 길어야 약 90초 뒤 만료되므로 다음 새
  송출이 오면 `recovered` 가 난다.
  `full` 이면 kty 에게 알린다. `full` 자체가 홍수의 신호다 — 서로 다른 IP 가 짧은
  사이에 상한(16,384)만큼 왔다(항목은 보통 길어야 약 90초 산다). 주소를 바꿔 가며
  쏘는 홍수는 한도로 막지 못하고 처리 시간 예산이 마지막 선이다.
  `ingestauth_ip_limited` 는 이미 기록된 IP 가 한도에 걸렸다는 뜻이라, 새 주소마다
  한 번씩 오는 이 홍수를 가리지 못한다. 응답기를 다시 띄우거나 끄지 않는다 — 다시
  띄우면 기록이 비어 IP 마다 30번을 다시 받고, 끄면 이미 기록된 IP 의 한도까지
  사라진다. 만료 항목은 새 IP 가 올 때 1초에 한 번 회수되니, `recovered` 가 가장 큰
  번호가 될 때까지 지켜본다.
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
컨테이너에는 `.env` 를 넘기지 않는다 — Control API 비번 한 줄의 값만 표준 입력으로 들어가고
(출입증은 들어가지 않는다), curl 은 그 값을 표준 입력의 설정(`-K -`)으로 받는다. 응답기의
망은 기본 망으로 밖에 나갈 수 있으므로 curl 이미지는 digest 로 고정한다(2026-10-17 —
`docker buildx imagetools inspect` 의 다중 아치 목록 digest).

```bash
AUTH_ID="$(sudo docker ps -q \
  --filter label=com.docker.compose.project=dev-media \
  --filter label=com.docker.compose.service=ingest-auth)"
CURL_IMAGE='curlimages/curl:8.10.1@sha256:d9b4541e214bcd85196d6e92e2753ac6d0ea699f0af5741f8c6cccbfcf00ef4b'
sudo sed -n 's/^MTX_API_PASS=//p' /opt/pokeclip-demo/.env \
  | sudo docker run --rm -i --network "container:${AUTH_ID}" "${CURL_IMAGE}" \
    sh -c '
  IFS= read -r pass
  api=http://10.250.0.2:9997/v3
  for p in srtconns/get/00000000-0000-0000-0000-000000000000 no-such-route; do
    printf "user = \"ingest-auth:%s\"\n" "${pass}" \
      | curl -sS -K - -w " — HTTP %{http_code}\n" "${api}/${p}"
  done'
```

첫 줄이 JSON 에러 본문 + 404, 둘째 줄이 `404 page not found` + 404 면 그대로다. 다르면
판 올림을 멈추고 cut 의 404 판정(`media/internal/mtxctl`)을 먼저 맞춘다.

### 7-4. 거부 목록 · 상태 디렉터리 규약

- **거부 목록**은 `${DEMO_HOME}/ingest-auth/deny/deny-tokens` 다(응답기 안에서
  `/etc/ingest-auth/deny-tokens` — 디렉터리 마운트라 이름 바꾸기로 끼운 파일이 닿는다).
  한 줄에 토큰 하나, `#` 뒤는 주석, 빈 줄 허용. 모드는 응답기(UID 10003)가 읽게 둔다 —
  첫 반영이 `root:10003 0640` 으로 만든다. 못 읽으면 새 연결이 모두 거부된다.
- **일반 파일 · 64 KiB 이하.** 넘거나 일반 파일이 아니면(FIFO · 디렉터리 등) 응답기는
  읽기 실패로 다룬다 — 새 연결이 모두 거부되고, **cut 도 아무것도 끊지 않는다**
  (`denylist_unreadable`). 응답기는 심볼릭 링크를 따라간다 — 끝이 일반 파일이면 그것을
  읽고, 끊긴 링크는 파일 없음과 같아 빈 목록이다(아래 「파일이 없으면」). 블록의
  단언(`assert_ready_to_start_ingest_auth` · 덧붙이기)은 더 엄격해 링크를 거부한다.
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

### 7-5. IP 한도 켜기 · 끄기

응답기는 publish 송출자 IP(IPv6 는 /64)마다 resolve 를 부르는 시도를 센다 — 30번은 몰아
쓰고 그 뒤 3초에 하나(`INGEST_AUTH_IP_LIMIT` · `_BURST` · `_INTERVAL`, 기본 on · 30 · 3s).
한 NAT 뒤 송출자들과 한 호스트에서 쏘는 벤치는 같은 IP 라 함께 센다 — 한 물결 30 을 넘는
벤치는 끄고 돌린다. 공인 송출자의 한도 로그가 `ip_kind=private` 이거나 서로 다른 송출자가
같은 `ip` 로 찍히면 곧바로 끈다(전원이 한 키로 묶여 30번 뒤 모두 거절된다).

**끄기 · 켜기는 응답기 재생성이다 — `restart` 가 아니다.** compose 는 `.env` 의
`INGEST_AUTH_IP_LIMIT` 를 응답기에 넘기고(`compose.yml` 의 그 줄), `restart` 는 환경을 다시
읽지 않는다. 적용값은 새 `ingestauth_started` 의 `ip_limit` 로 확인한다. 재생성 동안(다시
시작과 같은 창) 모든 계정의 새 연결 · 재접속이 거부된다.

```bash
# ── 블록: IP 한도 켜기 · 끄기 — 머리 + 이 본문. 인자 on | off ──
# .env 의 INGEST_AUTH_IP_LIMIT 한 줄을 인자로 맞추고 응답기만 다시 만든다(up -d
# --force-recreate). 새 ingestauth_started 의 ip_limit 가 인자와 같은지, MediaMTX 의
# 시작 시각이 앞뒤로 같은지 본다. compose 에 전달 줄이 없으면(상자 체크아웃이 그 판 전) 바꾸지 않고 멈춘다.
# 처음 바꿀 때 원래 키 줄(없었으면 빈 값)을 ip-limit.orig 에 적어 둔다. 인자가 원래 실효값(키가
# 없으면 on)과 같으면 원래 줄로(없었으면 줄을 빼서) 되돌리고 그 파일을 지운다 — 반대 인자로
# 돌리면 .env 가 바이트까지 처음과 같다. 그래서 지원 형식(ip_limit_line)이 아니면 바꾸지 않는다.
# 새 .env(비밀)는 .env.iplimit.* 에 쓰고, 끼우기 전에 실패하면 덫이 shred -u 로 지운다. 끊긴
# 앞 실행이 남긴 것은 다시 돌리면 첫머리가 지운다. 회수부터 끝까지 머리의 lock_ip_limit 잠금을
# 쥔다 — 두 실행이 겹치거나 공통 비밀 정리가 돌고 있으면 뒤의 것이 멈춘다.
readonly IP_LIMIT_KEY='INGEST_AUTH_IP_LIMIT'
readonly IP_LIMIT_ORIG="${DEMO_DIR}/ip-limit.orig"   # 원래 키 줄 — 비밀 아님
# 지원하는 키 줄. 키 이름이 든 줄은 # 주석 줄 밖에서 이것 하나뿐이어야 한다.
readonly IP_LIMIT_LINE_RE="^${IP_LIMIT_KEY}=(on|off)\$"
# 키 이름이 낱말로 든 줄(# 으로 시작하는 줄 · _BURST 같은 다른 이름은 뺀다)
readonly IP_LIMIT_ANY_RE="^([^#A-Za-z0-9_]|[^#].*[^A-Za-z0-9_])?${IP_LIMIT_KEY}"\
"([^A-Za-z0-9_]|\$)"
ENV_TMP=''    # 끼우기 전의 새 .env — 끼우면 비운다
ORIG_TMP=''   # 끼우기 전의 새 기억 파일 — 끼우면 비운다
# 머리의 덫(WORK 지우기)에 두 임시 파일 지우기를 더한다
trap 'discard_env_tmp; discard_orig_tmp; rm -rf -- "${WORK}"' EXIT

# discard_env_tmp 는 끼우지 못한 새 .env 를 shred -u 로 지운다.
discard_env_tmp() {
  if [[ -n "${ENV_TMP}" && -f "${ENV_TMP}" && ! -L "${ENV_TMP}" ]]; then
    shred -u -- "${ENV_TMP}"
  fi
}

# discard_orig_tmp 는 끼우지 못한 새 기억 파일(비밀 아님)을 지운다.
discard_orig_tmp() {
  if [[ -n "${ORIG_TMP}" && -f "${ORIG_TMP}" && ! -L "${ORIG_TMP}" ]]; then
    rm -f -- "${ORIG_TMP}"
  fi
}

# discard_stale_env_tmps 는 끊긴 앞 실행이 남긴 임시 파일을 지운다 — .env.iplimit.*(비밀)는
# shred -u, ip-limit.orig.*(기억 파일 사본)는 rm. 잠금을 쥔 뒤에만 부른다.
discard_stale_env_tmps() {
  local name
  ls -1A -- "${DEMO_DIR}" > "${WORK}/iplimit-names"   # 못 받으면 검사 실패
  while IFS= read -r name; do
    case "${name}" in
      .env.iplimit.*)
        remove_box_file shred "${DEMO_DIR}/${name}"
        echo "앞 실행이 남긴 임시 파일을 지웠다: ${name}"
        ;;
      ip-limit.orig.*)
        remove_box_file rm "${DEMO_DIR}/${name}"
        echo "앞 실행이 남긴 임시 파일을 지웠다: ${name}"
        ;;
    esac
  done < "${WORK}/iplimit-names"
}

# grep_found 는 grep 을 받은 인자 그대로 부른다. 찾았으면 참(0), 없으면 거짓(1)을 돌려주고, 그 밖(읽기 오류
# 등 — grep 종료 2 이상)은 검사 실패(종료 2)로 멈춘다 — 「없음」(일치 없음)과 도구 실패를 가른다. 마지막
# 인자(파일)를 사유에 싣는다. || 의 왼쪽에서 불러도 check_failed 의 exit 가 선다.
grep_found() {
  local -i rc=0
  grep "$@" || rc=$?
  (( rc <= 1 )) || check_failed "grep 이 실패했다(종료 ${rc}): ${*: -1}"
  return "${rc}"
}

# ip_limit_line 은 .env 의 키 줄을 변수 $1 에 넣는다(없으면 빈 값). 지원 형식만 받는다 —
# .env 가 줄바꿈으로 끝나고, 키 줄은 없거나 정확히 INGEST_AUTH_IP_LIMIT=on · =off 한 줄이다.
# 그 밖(따옴표 · 빈 값 · 인라인 주석 · CRLF · 앞 공백 · 끝 줄바꿈 없음 · 두 줄)은 바이트로
# 되돌릴 수 없어 바꾸기 전에 멈춘다. grep 은 종료 0 · 1 만 받는다 — 2(읽기 오류)를 「없음」
# 으로 읽으면 반쪽 .env 를 끼우게 된다. 지원 형식 밖은 일치 없음(종료 1), 읽기 오류는 검사
# 실패(종료 2)다.
ip_limit_line() {
  local -r env="${DEMO_DIR}/.env"
  local -i rc=0
  local found   # 부르는 쪽의 이름(line)과 겹치면 printf -v 가 이것을 채운다
  grep -E -e "${IP_LIMIT_ANY_RE}" -- "${env}" > "${WORK}/ip-limit-line" || rc=$?
  (( rc <= 1 )) \
    || check_failed ".env 를 읽지 못했다(grep 종료 ${rc}) — .env 를 바꾸지 않았다"
  [[ ! -s "${env}" || "$(tail -c 1 -- "${env}" | wc -l)" == 1 ]] \
    || mismatch '.env 가 줄바꿈으로 끝나지 않는다 — 지원 형식이 아니라 바꾸지 않았다'
  (( $(wc -l < "${WORK}/ip-limit-line") <= 1 )) \
    || mismatch ".env 에 ${IP_LIMIT_KEY} 줄이 둘 이상이다 — 손으로 하나로 줄인 뒤 다시"
  found="$(cat -- "${WORK}/ip-limit-line")"
  [[ -z "${found}" || "${found}" =~ ${IP_LIMIT_LINE_RE} ]] \
    || mismatch "${IP_LIMIT_KEY} 줄이 지원 형식(=on · =off 그대로)이 아니다 — 바꾸지 않았다"
  printf -v "$1" '%s' "${found}"
}

# ip_limit_orig 는 처음 바꾸기 전의 키 줄을 변수 $1 에 넣는다(빈 값 = 키 줄 없음).
# IP_LIMIT_ORIG 가 없으면 지금 줄을 적어 두고 그것을 낸다. 지금 .env 의 형식은 늘 본다. 기억
# 파일은 늘 줄바꿈으로 끝나는 한 줄이다(키 줄 없음 = 빈 줄) — 비었거나 줄바꿈 없이 끝나면 쓰다
# 끊긴 것이라 「키 없음」으로 읽지 않고 멈춘다.
ip_limit_orig() {
  local line
  ip_limit_line line
  if [[ ! -e "${IP_LIMIT_ORIG}" && ! -L "${IP_LIMIT_ORIG}" ]]; then
    write_ip_limit_orig "${line}"
  fi
  [[ -f "${IP_LIMIT_ORIG}" && ! -L "${IP_LIMIT_ORIG}" ]] \
    || mismatch "일반 파일이 아니거나 링크다: ${IP_LIMIT_ORIG}"
  [[ -s "${IP_LIMIT_ORIG}" \
    && "$(tail -c 1 -- "${IP_LIMIT_ORIG}" | wc -l)" == 1 ]] \
    || mismatch "${IP_LIMIT_ORIG} 가 비었거나 줄바꿈으로 끝나지 않는다(쓰다 끊김) — kty"
  line="$(cat -- "${IP_LIMIT_ORIG}")"
  [[ -z "${line}" || "${line}" =~ ${IP_LIMIT_LINE_RE} ]] \
    || mismatch "${IP_LIMIT_ORIG} 가 지원하는 키 줄도 빈 값도 아니다 — kty"
  printf -v "$1" '%s' "${line}"
}

# write_ip_limit_orig 는 IP_LIMIT_ORIG 를 $1 한 줄로 만든다 — 같은 디렉터리의 임시 파일에 쓰고
# 되읽어 견준 뒤 이름 바꾸기로 끼운다. 쓰다 실패하면(ENOSPC 등) IP_LIMIT_ORIG 는 생기지 않고
# 덫이 임시 파일을 지운다.
write_ip_limit_orig() {
  ORIG_TMP="$(mktemp -- "${IP_LIMIT_ORIG}.XXXXXX")"
  printf '%s\n' "$1" > "${ORIG_TMP}"
  [[ -s "${ORIG_TMP}" && "$(cat -- "${ORIG_TMP}")" == "$1" ]] \
    || mismatch '기억 파일을 되읽은 값이 쓴 값과 다르다 — .env 를 바꾸지 않았다'
  mv -T -- "${ORIG_TMP}" "${IP_LIMIT_ORIG}"
  ORIG_TMP=''
}

# write_env_candidate 는 키 줄을 $1 로 바꾼(빈 값이면 뺀) 새 .env 를 ENV_TMP 에 쓴다 — 키 줄
# 자리는 그대로, 없던 키는 끝에 붙인다. 모드 · 소유를 .env 와 맞춘다. 같은 디렉터리(root
# 소유 — assert_demo_dir_safe)에 0600 으로 만든다 — 비밀이 든 사본을 WORK 에 두지 않는다.
# 읽기 · 쓰기 · 메타데이터 복사가 실패하면 끼우기 전에 검사 실패(종료 2)로 멈춘다(덫이 ENV_TMP 를
# 지운다).
write_env_candidate() {
  local -r env="${DEMO_DIR}/.env"
  local -i rc=0
  ENV_TMP="$(mktemp -- "${DEMO_DIR}/.env.iplimit.XXXXXX")"
  KEY="${IP_LIMIT_KEY}=" LINE="$1" awk '
    index($0, ENVIRON["KEY"]) == 1 {
      seen = 1
      if (ENVIRON["LINE"] != "") print ENVIRON["LINE"]
      next
    }
    { print }
    END { if (!seen && ENVIRON["LINE"] != "") print ENVIRON["LINE"] }
  ' < "${env}" > "${ENV_TMP}" || rc=$?
  (( rc == 0 )) \
    || check_failed "새 .env 를 만들지 못했다(종료 ${rc}) — .env 를 바꾸지 않았다"
  chmod --reference="${env}" -- "${ENV_TMP}"
  chown --reference="${env}" -- "${ENV_TMP}"
}

main() {
  (( $# == 1 )) && [[ "$1" == on || "$1" == off ]] \
    || check_failed '사용법: on | off'
  local -r want="$1" env="${DEMO_DIR}/.env"
  local mtx_id='' before after since orig orig_value target
  assert_demo_dir_safe
  grep_found -q 'INGEST_AUTH_IP_LIMIT:' -- "${REPO}/${COMPOSE_YML}" \
    || mismatch 'compose.yml 에 INGEST_AUTH_IP_LIMIT 전달 줄이 없다 — .env 로 바꿀 수 없다'
  assert_ready_to_start_ingest_auth
  lock_ip_limit
  discard_stale_env_tmps
  echo ".env 앞: $(sha256sum -- "${env}" | cut -c1-12)"
  ip_limit_orig orig
  orig_value=on
  [[ -z "${orig}" ]] || orig_value="${orig#"${IP_LIMIT_KEY}="}"
  target="${IP_LIMIT_KEY}=${want}"
  [[ "${want}" != "${orig_value}" ]] || target="${orig}"
  write_env_candidate "${target}"
  echo '.env 교체 시작 — 이 줄이 없으면 .env 는 그대로다'
  mv -T -- "${ENV_TMP}" "${env}"
  ENV_TMP=''
  echo '.env 교체함 — 이 줄 뒤의 실패는 교체 뒤 실패다'
  echo ".env 뒤: $(sha256sum -- "${env}" | cut -c1-12)"
  if [[ "${target}" == "${orig}" ]]; then
    remove_box_file rm "${IP_LIMIT_ORIG}"
    echo '.env 를 원래 키 줄로 되돌렸다'
  fi
  container_id mediamtx mtx_id
  before="$(docker inspect -f '{{.State.StartedAt}}' "${mtx_id}")"
  since="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "재생성 명령: ${since}"
  compose_up_masked live_compose -d --no-deps --force-recreate ingest-auth
  wait_ingest_auth_healthy
  echo "healthy: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  live_compose logs --no-log-prefix --since "${since}" ingest-auth \
    > "${WORK}/auth.log"
  grep_found '"msg":"ingestauth_started"' -- "${WORK}/auth.log" \
    > "${WORK}/started" || mismatch '재생성 뒤 ingestauth_started 줄이 없다'
  grep_found -q "\"ip_limit\":\"${want}\"" -- "${WORK}/started" \
    || mismatch "새 ingestauth_started 의 ip_limit 가 ${want} 가 아니다"
  after="$(docker inspect -f '{{.State.StartedAt}}' "${mtx_id}")"
  [[ "${after}" == "${before}" ]] || mismatch 'MediaMTX 가 다시 시작됐다 — 「응답기만」이 깨졌다'
  echo "ip_limit=${want}"
}
main "$@"
```

**지원 형식.** `.env` 는 줄바꿈으로 끝나야 하고, 키 줄은 없거나 정확히
`INGEST_AUTH_IP_LIMIT=on` · `INGEST_AUTH_IP_LIMIT=off` 한 줄이어야 한다. 따옴표 · 빈 값 ·
인라인 주석 · CRLF · 앞 공백 · 두 줄은 지원하지 않는다(`#` 으로 시작하는 주석 줄과
`INGEST_AUTH_IP_LIMIT_BURST` 같은 다른 이름은 상관없다). 지원하지 않는 형식이면 `.env` 를
건드리지 않고 종료 1 이다 — 손으로 그 형식으로 고친 뒤 다시 돌린다.

**어디서 멈췄나는 `.env 교체 시작` · `.env 교체함` 두 줄로 가린다.** 블록은 이름 바꾸기
바로 앞에 앞의 줄을, 바로 뒤에 뒤의 줄을 찍는다.

- **`교체 시작` 줄이 없으면** `.env` 는 그대로다. 잠금 · 형식 · 읽기 · 쓰기 · 메타데이터
  복사 · 기억 파일 쓰기에서 멈췄다. 마지막 줄의 사유를 고쳐 다시 돌린다.
- **`교체 시작` 은 있고 `교체함` 이 없으면 바뀌었는지 모른다.** 이름 바꾸기 바로 앞뒤에서
  끊겼거나(신호 · 출력 실패) 이름 바꾸기가 실패했다. 지금 해시를 읽어
  (`sudo sha256sum -- /opt/pokeclip-demo/.env | cut -c1-12` — 읽기라 승인 묶음에 넣는다)
  출력의 `.env 앞` 과 견준다. 같으면 그대로다 — 같은 인자로 다시 돌린다. 다르면 아래 교체
  뒤 실패와 같다.
- **`교체함` 이 있으면 교체 뒤 실패다.** `.env` 는 이미 `.env 뒤` 해시이고, 응답기는 아직
  옛 값으로 돌 수 있다. 같은 인자로 다시 돌리면 이어서 끝난다(기억 파일 정리 · 재생성 ·
  확인). 처음으로 돌아가려면 반대 인자로 돌린다.

반대 인자로 되돌리면 `.env` 가 처음 `.env 앞` 해시로 돌아온다 — 처음 바꾸기 전의 키 줄을
`ip-limit.orig`(비밀 아님)가 기억하고, 되돌릴 때 지운다. 기억 파일은 임시 파일에 써서 이름
바꾸기로 끼우므로 쓰다 끊겨도 반쪽이 남지 않는다 — 비었거나 줄바꿈 없이 끝난 기억 파일을
만나면 블록이 종료 1 로 멈춘다(kty). 그 파일을 손으로 지우거나 고치지 않는다. 블록은 회수부터
끝까지 `ip-limit.lock`(비밀 아님)의 잠금을 쥔다 — 다른 7-5 실행이나 공통 비밀 정리(T 백업
처분 · E-2 · 반영 전 비밀 파기 — 같은 잠금을 잡는다)가 돌고 있으면 종료 1 로 멈추니, 그
실행이 끝난 뒤 다시 돌린다. 거꾸로 7-5 가 도는 동안 그 정리 블록들도 아무것도 지우지 않고
종료 1 로 멈춘다. 블록이 끊겨 새 `.env` 사본(`.env.iplimit.*` — 비밀)이 남았으면 이 블록을
다시 돌린다 — 첫머리가 잠금을 잡은 뒤 지운다(그 정리 블록들도 이 이름을 지운다). 실측
기록에는 `.env` 해시 앞뒤와 `ip_limit` 값을 남긴다.

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

덧붙이면 64 KiB 를 넘을 때도 덧붙이지 않고 종료 2 다 — 넘으면 응답기가 파일을 못 읽어 새
연결이 모두 거부되고 cut 도 아무것도 끊지 않는다(7-4). 그때는 kty 가 줄일 것(해제할
토큰 · 주석)을 정한다.

- **종료 2 「토큰 꼴(…)이 아니다」이고 붙인 것이 비밀이면** 그 값은 SSM 명령 기록에
  남는다 — 그 계정 키를 교체한다(9-7 의 수단) · kty.
- **종료 1 이면 덧붙이지 않았다** — 상자 디렉터리(`${DEMO_HOME}` · `ingest-auth` ·
  `deny`)가 root 소유가 아니거나 · 링크이거나 · 남이 쓸 수 있다(2-2
  `assert_demo_dir_safe`), 또는 거부 목록 파일이 없다(7-4). 그 토큰이 목록에 없어 cut 도
  아무것도 끊지 않으므로(`not_denied`) 긴급 절단이 통째로 막힌 것이다 — 곧바로 kty. 그
  자리의 낯선 링크 · 파일을 보고, 권한 복구는 kty 가 정한 뒤 덧붙이기부터 다시 돌린다.

```bash
# ── 블록: 덧붙이기 — 머리 + 이 본문. 인자: <토큰> ['<설명 한 줄>'] ──
# 꼴이 아니거나 덧붙이면 64 KiB 를 넘으면 덧붙이지 않고 종료 2 다. 파일을 지웠다가 다시
# 만드는 편집은 쓰지 않는다.
main() {
  (( $# == 1 || $# == 2 )) || check_failed "사용법: <토큰> ['<설명>']"
  local -r token="$1" note="${2:-}"
  local -i size add
  assert_demo_dir_safe
  # 값은 메시지에 싣지 않는다 — 토큰 자리에 비밀을 잘못 붙였을 수 있다
  [[ "${token}" =~ ${TOKEN_RE} ]] \
    || check_failed '토큰 꼴(26자 · 0-9A-HJKMNP-TV-Z)이 아니다 — 덧붙이지 않았다'
  [[ "${note}" != *$'\n'* ]] || check_failed '설명은 한 줄이다 — 덧붙이지 않았다'
  [[ -f "${DENY_FILE}" && ! -L "${DENY_FILE}" ]] \
    || mismatch "거부 목록 파일이 없다(7-4): ${DENY_FILE}"
  # 덧붙인 뒤 크기 — 줄바꿈 하나(끝 줄바꿈이 없으면) + 설명 줄 + 토큰 줄. 넘으면 응답기가
  # 파일을 못 읽어 새 연결이 모두 거부되고 cut 도 아무것도 끊지 않는다(7-4)
  size="$(stat -c '%s' -- "${DENY_FILE}")"
  add=$(( ${#token} + 1 ))
  if [[ -n "${note}" ]]; then
    add+=$(( ${#note} + 3 ))
  fi
  if [[ -s "${DENY_FILE}" && -n "$(tail -c 1 -- "${DENY_FILE}")" ]]; then
    add+=1
  fi
  (( size + add <= DENY_MAX_BYTES )) \
    || check_failed "덧붙이면 64 KiB 를 넘는다(${size} + ${add} 바이트) — 덧붙이지" \
      '않았다. kty'
  end_with_newline "${DENY_FILE}"   # 끝 줄바꿈 없는 마지막 줄에 붙지 않게
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
| 출력 줄 없음 | cut 이 돌지 않았다 — compose 의 오류 문구만 나오고 종료 코드는 compose 의 것이다. 문구로 가른다. 응답기 컨테이너가 없거나 멈췄으면(예: `service "ingest-auth" is not running` — 로컬 실측 S7-7 D13) 「응답기 살리기」(7-2) 뒤 cut 을 다시 돌린다. 첫 반영 주기를 접은 동안이면 cut 은 효력이 없다(11-1) — 살리기로 가지 않는다(그 옛 판에는 응답기가 없다). kty. `couldn't find env file: …/.env` 이면 응답기 탓이 아니다 — 명령의 `--env-file` · `-f` 자리가 그 상자 · 리허설의 `DEMO_HOME` 과 다르다(리허설 · 자리 오설정 — 로컬 실측 S7-5 D40). 응답기를 살리지 않는다. 자리부터 맞춘 뒤 cut 을 다시 돌린다 |

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
| `connlog_window_short` | 기록 밖이면서 30초가 안 된 판독 중 연결이 있다. 30초 뒤 cut 을 다시 돌린다. 그 연결의 허용 줄이 기록(지금 세대 · 직전 세대 `.1` 둘)에 없을 때 생긴다 — 기록이 처음 열린 뒤 30초 안(상태 디렉터리를 새로 만든 직후)이거나, 운영 중 연결이 몰려 기록이 30초 안에 두 번 회전해(세대마다 1 MiB) 그 줄이 밀려났을 때다(`media/internal/ingestauth/cut_race_test.go` 회전 경합 시험) |
| `denylist_unreadable` | 거부 목록을 못 읽었다 — **아무것도 끊지 않았다.** 파일의 권한 · 마운트 · 꼴(일반 파일 · 64 KiB — 7-4)을 고친 뒤 다시 돌린다. 그동안 새 연결은 모두 거부된다. 틀린 줄은 읽기 실패가 아니다 — 판정에서 건너뛰고 healthz 만 실패한다(10절). 해제 적용 뒤라면 살아 있는 파일의 모드가 틀린 것이다 — 사본 되돌림(9-6) 뒤 다시 돌린다 |
| `connlog_unreadable` | 기록 디렉터리를 못 읽었거나 줄이 하나도 없다 — 목록 갈래(등록된 연결)는 이미 끊었다. 권한 · 마운트를 고친 뒤 다시 돌린다. 줄이 하나도 없으면(시작 표시도 없음) 상태 디렉터리가 지워진 것이다 — 「응답기 다시 시작」(7-2)으로 새 세대와 시작 표시를 만든 뒤 다시 돌린다. 그동안 새 연결은 기록 쓰기 실패로 거부된다 |
| `api_auth` | Control API 가 401 이다 — MediaMTX 가 api 인증을 응답기에 묻다 실패했다. 응답기 로그 `ingestauth_api_denied` 와 MediaMTX 로그를 본다(7-3). ① `api_denied` 0 ∧ MediaMTX 로그에 `cannot assign requested address` → MediaMTX 쪽 임시 포트 고갈이다(큰 cut 직후 · 토큰 꼴 송출 시도가 몰릴 때). 60초 뒤 cut 을 한 번 다시 돌린다 → 또 나면 MediaMTX 최후 재시작(되풀이 금지) → cut 으로 확인. 커널 값은 조정하지 않는다(kty 106차). ② `api_denied` 가 0 이 아니면 그 `ip` 를 compose 의 `INGEST_AUTH_LISTEN` 주소와 견준다 — 다르면 출발지 설정이 틀렸다(kty). ③ 둘 다 아니면 응답기 고장이다. cut 이 이 줄을 냈으면 응답기 컨테이너는 떠 있다 — 「응답기 다시 시작」(7-2) 뒤 cut 을 다시 돌린다(컨테이너가 없거나 멈췄으면 「응답기 살리기」). 그래도 같으면 kty. MediaMTX 재시작으로 풀지 않는다 |
| `api_unreachable` | Control API 와의 왕복이 실패했다 — 무응답 · 전송 실패 · 401 · 404 밖의 HTTP 상태(500 등). 먼저 응답기가 healthy 인지 본다 — 아니면 「응답기 다시 시작」(7-2 — 컨테이너는 떠 있다. 없거나 멈췄으면 「응답기 살리기」) 뒤 **cut 부터 다시** 돌린다. 다시 시작해도 healthy 가 아니면 kty. 그다음 출력의 `kicked` · `already_gone` 과 stderr 줄(`ingest-auth: cut api_unreachable: mtxctl: <list·get·kick> — <까닭>`)의 까닭으로 가른다. ① `timeout`(또는 stderr 가 `context deadline exceeded` — 끊은 뒤 확인 단계에서 시한에 걸림)이고 `kicked + already_gone` 이 0 보다 크다 — cut 자신의 시한(2분)에 걸린 큰 cut 이다. Control API 인증은 돌고 있었다 — cut 을 한 번 더 돌린다. ② `timeout` 이고 0 이거나 `unreachable`(연결 거부 등) — Control API 가 답하지 않는다. MediaMTX 고장이다 — 응답기 healthy 를 확인한 뒤 MediaMTX 최후 재시작(되풀이 금지) → cut 으로 확인. ③ `HTTP <상태>`(500 등) — Control API 는 답했지만 처리하지 못했다. cut 을 한 번 다시 돌린다 → 같으면 `kicked` 를 보고 응답기 healthy 를 확인한 뒤 MediaMTX 최후 재시작 1회(되풀이 금지) → cut 으로 확인하고 kty 에 보고한다. 어느 갈래든 재시작 뒤 그 토큰의 재접속은 거부 목록이 막는다. ①–③ 밖의 꼴이면 멈추고 kty |
| `api_incompatible` | cut 이 아는 꼴이 아닌 Control API 응답이다(경로 없음 404 · 해석 실패 · 여러 페이지). **끊었는지 모른다 — 출력의 `kicked` 를 본다**(kick 한 뒤에도 날 수 있다). 응답기 healthy 를 확인한 뒤 MediaMTX 최후 재시작 1회(되풀이 금지) → MediaMTX 판 · 경로 호환을 고친 뒤(7-3 두 404 꼴) cut 으로 확인한다 |
| `internal_error` | cut 의 내부 오류(분류 밖 결과)다. `api_incompatible` 과 같은 처방(`kicked` 확인 → 응답기 healthy → MediaMTX 최후 재시작 1회)에 kty 보고를 더한다 |
| 종료 2 `not_denied` | 거부 목록에 그 토큰이 없다 — 아무것도 끊지 않았다. 덧붙이기(8-1) 뒤 다시 돌린다. 덧붙였는데 이것이면 해제 · 되돌림 적용 · 틀린 줄 걷기의 교체 구간에 줄이 사라진 것이다 — 다시 덧붙인다 |

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
  assert_demo_dir_safe
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
  # 4 절단 사유 주석 한 줄 — 새 파일의 마지막 줄에 끝 줄바꿈이 없으면 붙지 않게 하나 먼저
  end_with_newline "${new}"
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
  사본이 그 줄을 담는다). 다만 적용이 끝을 내지 못한 뒤(끊김) 다시 돌린 적용이 종료 1
  인데 살아 있는 파일이 새 파일과 같거나 새 파일로 시작하면 준비로 가지 않는다 — 9-6 첫
  행이다.

### 9-4. 해제 적용 (7–8)

실행 직전 kty 승인. **이 블록이 도는 동안에도 긴급 절단(덧붙이기 → cut)은 미루지
않는다.** 블록은 SSM 명령 하나라 끝날 때까지(재시작 · healthy 대기 — 최대 120초) 출력이
보이지 않고, 운영자는 어느 단계인지 알 수 없다. 덧붙인 줄을 잃는 것은 재확인부터 이름
바꾸기까지(7 — 수 초)의 순간뿐이다 — 그 사이에 덧붙인 줄은 옛 파일과 함께 사라진다.
cut 이 그 순간이 지난 뒤에 돌면 첫 확인이 종료 2(`not_denied` — 8-3)로 드러낸다. 그러나
덧붙이기와 cut 이 둘 다 그 순간 안에 끝나면 cut 은 옛 파일을 보고 종료 0 이라 드러나지
않는다. 그래서 **이 블록이 도는 동안 긴급 절단을 했으면 블록이 끝난 뒤 그 토큰으로 cut 을
한 번 더 돌린다** — 블록이 교체까지 갔으면 새 파일만 보이므로, 줄이 사라졌으면 반드시
종료 2 다. 종료 2 면 다시 덧붙이고 cut 을 다시 돌린다. 블록이 7 의 재확인에서 멈췄으면(종료
1) 교체가 없어 옛 파일과 그 줄이 그대로라 cut 은 종료 0 이다 — 그 줄은 남아 있고, 해제는
준비부터 다시 한다. 블록이 끝을 내지 못해(끊김 · 출력 없음) 다시 돌린 적용이 종료 1 인데
살아 있는 파일이 새 파일과 같거나 새 파일로 시작하면 교체는 이미 끝났다 — 준비로 가지 않고
9-6 첫 행을 따른다. 재시작부터 다시 돈 cut 까지는 그 토큰이 다시 붙을 수 있다 — 한계로
둔다(kty 107차). 그 창은 블록 안의 재시작 · healthy 대기(최대 120초)에 블록이 끝난 것을
보고 cut 을 다시 돌리기까지의 운영자 시간(확인 · 승인 · SSM 지연)이 더해진 것이라 정해진
상한이 없다 — 블록이 끝나면 곧바로 다시 돌린다. 이름 바꾸기 뒤에
덧붙인 줄은 새 파일에 남고, healthy 에 실패해 사본 되돌림(9-6)으로 가면 되돌림이 그 줄을
옮겨 붙인다.

7. 사본 · 새 파일이 한 번의 준비가 낸 짝인지 본다(이름 끝 시각이 같다 — 다르면 종료 2).
   끼울 새 파일과 상태 디렉터리를 기동 입력으로 본다(7-1). 살아 있는 파일이 사본과
   바이트까지 같은지 다시 본다(그사이 덧붙은 줄이 없다). 최종 새 파일에 5 의 단언을 한 번
   더 건다. 새 파일을 이름 바꾸기로 끼우고 `ingest-auth` 하나만 다시 시작한다 —
   MediaMTX 는 건드리지 않는다.
8. 120초 안에 healthy 인지 본다. 넘기면 9-6 이다.

```bash
# ── 블록: 해제 적용 — 머리 + 이 본문. 인자: <대상 토큰> <사본> <새 파일> ──
# 사본 · 새 파일은 한 번의 해제 준비가 낸 짝이다(시각 꼬리가 같다). 실행 직전 kty 승인.
main() {
  (( $# == 3 )) || check_failed '사용법: <대상 토큰> <사본> <새 파일>'
  local -r target="$1" copy="$2" new="$3"
  local copy_at='' new_at=''
  assert_demo_dir_safe
  [[ "${target}" =~ ${TOKEN_RE} ]] || check_failed '대상이 토큰 꼴이 아니다'
  deny_name_stamp "${copy}" copy copy_at
  deny_name_stamp "${new}" unblock new_at
  [[ "${copy_at}" == "${new_at}" ]] \
    || check_failed '사본 · 새 파일의 시각이 다르다 — 한 번의 해제 준비가 낸 짝이다'
  # 다시 시작할 입력 — 끼울 새 파일(64 KiB · 일반 파일 · 틀린 줄 0)과 상태 디렉터리
  assert_ready_to_start_ingest_auth "${new}"
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
- **재시작 창** — 적용 블록이 낸 「재시작 명령」 · 「healthy」 두 시각의 차다. 창 전체가
  거부 구간은 아니다 — 창의 대부분은 첫 healthcheck 를 기다리는 몫이다. compose 가
  `start_interval` 을 주지 않아 시작 유예(`start_period`) 안의 검사 간격이 엔진
  기본값인데, 그 기본값이 docker 25 · 26 은 `interval`(30초)이고 27 부터는 5초다(moby
  `daemon/health.go` —
  [v25.0.16 254행](https://github.com/moby/moby/blob/v25.0.16/daemon/health.go#L254) ·
  [v27.0.1 258행](https://github.com/moby/moby/blob/v27.0.1/daemon/health.go#L258) ·
  [v29.0.0 255행](https://github.com/moby/moby/blob/docker-v29.0.0/daemon/health.go#L255)).
  그래서 창이 상자(AL2023 docker 25)에서 약 31초, 로컬(docker 29)에서 6–7초다. 모든
  계정의 새 연결 · 재접속이 거부되는 것은 응답기가 멈췄다 다시 듣기까지의 앞부분뿐이다.
  로컬 실측 S7-5 에서 멈춤 → 시작이 0.19–0.29초(그동안 MediaMTX 인증 실패
  `connection refused` 0–2줄)였고, 멈춤부터 첫 정상 수락까지는 1.20–3.29초(시도 간격에
  묶인 값)였다 — healthy 보다 앞서 수락했다. 상자 실측 3 은 2026-10-20 재측정에서
  창 31초(재시작 명령 03:22:08Z → healthy 03:22:39Z — 2026-10-19 의 06:06:49Z →
  06:07:20Z 와 같다) 가운데 멈춤 → 시작이 0.86초, 멈춤부터 첫 정상 수락까지가
  4.06초(플러그인 재시도 간격에 묶인 값)였다. 「응답기 다시 시작」 블록 주석의 「그동안
  … 거부된다」는 이 짧은 구간을 말한다. 지금은 서비스 사용자가 없어 따로 알리지 않는다.
- `ingestauth_denylist_changed` 가 나면 그 `denied` 를 참고로 적는다(판정에는 쓰지
  않는다).

### 9-6. 적용 뒤 실패와 사본 되돌림

healthy 시한 초과 · 대상이 허용되지 않음 · 남은 토큰이 허용됨 · 다른 진행 송출이 끊김
가운데 하나면 멈춘다. 그때 살아 있는 파일은 최종 새 파일이고 응답기는 다시 시작했다(또는
unhealthy). 표의 첫 행만 다르다 — 적용 블록이 끼운 뒤 응답기를 다시 시작하기 전에 끊겼다.

- 그동안의 긴급 절단도 덧붙이기 → cut 차례 그대로다. 덧붙이기는 곧바로 효과가 있다(응답기가
  뜰 때 읽는다 — 응답기가 멈췄다 다시 듣기까지의 짧은 구간(9-5)에는 새 연결이 모두
  거부된다). **cut 은 먼저
  시도한다** — healthz 만 실패하는 unhealthy 에서는 api 인증이 돈다. 종료 1 이면 8-3 으로
  가른다 — `api_auth` 면 8-3 의 그 행대로 응답기를 다시 시작한(없으면 살린) 뒤,
  `denylist_unreadable`(살아 있는 파일의 모드가 틀림)이면 사본 되돌림 뒤 다시 돌린다.

| 실패 | 처방 |
|---|---|
| 해제 적용이 끝을 내지 못했다(끊김 · 출력 없음) · 다시 돌린 적용이 「살아 있는 파일이 사본과 다르다」인데 살아 있는 파일이 그 새 파일로 시작한다(같거나, 그 뒤에 긴급 절단 줄이 덧붙었다 — `sudo sh -c 'head -c "$(stat -c %s -- <새 파일>)" -- <살아 있는 파일> \| cmp -- <새 파일> -'` 종료 0. 되돌림 준비의 대조와 같은 꼴) | 끼움은 끝났고 응답기만 다시 시작하지 못했다. 덧붙은 줄은 살아 있는 파일에 있어 재시작 때 읽힌다. 준비부터 다시 하지 않는다 — 대상이 사본에 없어 「대상이 사본의 집합에 없다」로 막힌다. 「응답기 다시 시작」(7-2) → 동작 확인(9-5). 그사이 대상은 합집합에 남아 거부된다(안전 쪽). 실패한 준비가 남긴 사본 · 새 파일 짝은 기록이라 지우지 않아도 된다(지우면 승인). 로컬 실측 fix3 E2 — 처방 3/3 · 처방 없이 대상 거부 3/3 · 덧붙은 줄이 낀 갈래(E2b) 1/1 |
| 남은 토큰이 허용됨 · healthy 시한 초과 | 사본 되돌림(아래 — 준비 → 적용 · 응답기만 재시작 · healthy) → 사본의 토큰과 그사이 덧붙은 토큰이 모두 `reason=denylisted` 인지 시도. 허용된 토큰의 연결이 이미 붙었으면 cut 한다(먼저 시도). 되돌려도 unhealthy 면 파일 밖 원인이다 — 운영 순서(7절)대로 응답기를 살리고 kty |
| 대상이 허용되지 않음 | 차단이 유지된 쪽이라 급하지 않다. 스트리머가 없어 가려지지 않으면 시도 도구 확인(9-5)으로 가른다. 원인이 응답기가 다시 시작하지 않음(적용 블록이 끝을 내지 못했다)이면 첫 행이다 — 준비부터 다시 하지 않는다. 새 파일에 대상이 남았으면 원인을 본 뒤 준비부터 다시 한다 |
| 다른 진행 송출이 끊김 · MediaMTX 가 다시 시작됨 | 「응답기만」이 깨졌다 — 재시작 명령이 MediaMTX 까지 건드렸다. 플러그인 재시도가 다시 붙인다(전 방송 수 초 끊김). 기록하고 명령 꼴을 고친다(kty) |

**사본 되돌림** — 해제처럼 준비와 적용으로 나눈다. 모음과 이름 바꾸기 사이에 긴급 쓰기가
끼면 그 줄이 교체와 함께 사라지고, 재시작이 합집합을 파일에서 새로 쌓아 그 토큰이 다시
허용되기 때문이다. 덧붙은 줄을 옮기지 않고 사본만 끼우면 그 절단이 조용히 풀린다.

1. **되돌림 준비 — 모음.** 인자는 해제 준비가 낸 사본 · 새 파일의 짝이다(이름 끝 시각이
   같다 — 다르거나 그 꼴이 아니면 종료 2). 살아 있는 파일을
   `deny-tokens.gathered.<시각>` 으로 남긴다(모은 시점의 사본). 그것이 최종 새 파일로
   시작하는지 본다 — 아니면 멈추고 kty.
1. **되돌릴 파일.** 사본을 `cp -p` 로 `deny-tokens.revert.<시각>` 에 만들고, 모은 사본에서
   최종 새 파일 뒤에 덧붙은 줄(재시작 창의 긴급 절단)을 그 끝에 옮겨 붙인다.
1. **단언.** 되돌릴 파일의 집합 = 사본의 집합 ∪ 덧붙은 토큰 · 틀린 줄 0 · 사본의 줄은
   차례 · 바이트까지 같다 · 64 KiB 이하 일반 파일이고 응답기가 읽는다.
1. **되돌림 적용(실행 직전 승인).** 모은 사본 · 되돌릴 파일이 한 번의 되돌림 준비가 낸
   짝인지 본다(이름 끝 시각이 같다 — 낡은 되돌릴 파일에는 그 뒤 덧붙은 줄이 없다. 다르면
   종료 2). 되돌릴 파일과 상태 디렉터리를 기동 입력으로 본다(7-1). 살아 있는 파일이 모은
   사본과 바이트까지 같은지 다시 본다 — 다르면 종료 1 이고 아무것도 바꾸지 않는다(그사이 덧붙은 줄이 있다 — 1 부터 다시,
   새 모음이 그 줄을 담는다). 같으면 끼우고 `ingest-auth` 하나만 다시 시작 → healthy.

```bash
# ── 블록: 되돌림 준비 — 머리 + 이 본문. 인자: <사본> <새 파일>(해제 준비가 낸 짝) ──
# 살아 있는 파일은 바꾸지 않는다. 모은 사본과 되돌릴 파일의 이름을 낸다 — 되돌림 적용의 인자다.
main() {
  (( $# == 2 )) || check_failed '사용법: <사본> <새 파일>'
  local -r copy="$1" new="$2"
  local stamp gather revert size copy_at='' new_at=''
  assert_demo_dir_safe
  deny_name_stamp "${copy}" copy copy_at
  deny_name_stamp "${new}" unblock new_at
  [[ "${copy_at}" == "${new_at}" ]] \
    || check_failed '사본 · 새 파일의 시각이 다르다 — 한 번의 해제 준비가 낸 짝이다'
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
  end_with_newline "${revert}"   # 사본의 끝 줄바꿈이 없으면 덧붙은 첫 줄이 붙지 않게
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
# 두 이름은 한 번의 되돌림 준비가 낸 짝이다(시각 꼬리가 같다). 실행 직전 kty 승인.
main() {
  (( $# == 2 )) || check_failed '사용법: <모은 사본> <되돌릴 파일>'
  local -r gather="$1" revert="$2"
  local gather_at='' revert_at=''
  assert_demo_dir_safe
  deny_name_stamp "${gather}" gathered gather_at
  deny_name_stamp "${revert}" revert revert_at
  [[ "${gather_at}" == "${revert_at}" ]] \
    || check_failed '모은 사본 · 되돌릴 파일의 시각이 다르다 — 한 번의 되돌림 준비가 낸 짝이다'
  # 다시 시작할 입력 — 끼울 되돌릴 파일(64 KiB · 일반 파일 · 틀린 줄 0)과 상태 디렉터리.
  # 살아 있는 파일의 모드가 틀렸어도(9-6) 되돌림이 막히지 않게 후보를 본다
  assert_ready_to_start_ingest_auth "${revert}"
  # 4 재확인 — 그사이 덧붙은 줄이 있으면 멈춘다
  assert_same "${DENY_FILE}" "${gather}" \
    '살아 있는 파일이 모은 사본과 다르다 — 그사이 덧붙은 줄이 있다. 준비부터 다시'
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
   응답기 시작 시각이 그대로인지 본다. healthy 까지 걸린 시간을 적는다. 응답기 컨테이너가
   없으면(접힌 뒤 · 첫 반영 전 — R5 · 응답기 살리기 · 롤백 D 의 기동 입력 확인이 이 절로
   보낸 경우) 끼우기만 하고 시각은 「없음」이다. 걷기 확인은 돌지 않고, 보낸 블록을 다시
   돌린다(그 블록이 거부 목록을 다시 본다).

- 1–4 는 한 블록(수 초)이고 실행 직전 승인이다. 긴급 절단은 미루지 않는다 — 블록 앞에서
  끼면 4 의 재확인이 종료 1 로 멈춘다. 덧붙인 줄을 잃는 것은 재확인부터 이름 바꾸기까지의
  순간뿐인데, 덧붙이기와 cut 이 둘 다 그 순간 안에 끝나면 cut 은 옛 파일을 보고 종료 0
  이다. 이 블록은 응답기를 다시 시작하지 않지만, 응답기가 그 사이 그 토큰을 한 번도 읽지
  않았으면 합집합에도 없어 재접속이 허용된다. 그래서 **이 블록이 도는 동안 긴급 절단을
  했으면 블록이 끝난 뒤 그 토큰으로 cut 을 한 번 더 돌린다** — 종료 2 면 다시 덧붙이고
  cut 을 다시 돌린다(9-4 와 같다 — kty 107차). 교체부터 다시 돈 cut 까지는 그 토큰이 다시
  붙을 수 있다 — 한계로 둔다. 그 창에는 블록이 끝난 것을 보고 cut 을 다시 돌리기까지의
  운영자 시간(확인 · 승인 · SSM 지연)이 들어 정해진 상한이 없다 — 블록이 끝나면 곧바로
  다시 돌린다.
- 걷은 줄은 사본에 남는다. 그 줄이 막으려던 토큰이 있었으면(예: 소문자로 옮긴 값) 꼴을
  고쳐 덧붙이기(8-1)와 cut 으로 다시 막는다.
- **멈춤 안내가 이 절을 가리킨다** — 해제 준비의 집합 단언이 사본의 틀린 줄로 멈출 때,
  그리고 healthy 를 기다리는 블록이 시한을 넘기고 원인이 틀린 줄일 때다. 이 절을 돈 뒤 그
  블록으로 돌아간다.

```bash
# ── 블록: 틀린 줄 걷기 — 머리 + 이 본문. 인자 없음. 응답기를 다시 시작하지 않는다 ──
# 실행 직전 kty 승인. 끝에 응답기 시작 시각을 낸다 — 걷기 확인의 인자다. 응답기
# 컨테이너가 없으면(접힌 뒤 · 첫 반영 전) 끼우기만 하고 시각은 「없음」이다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local stamp copy new bad auth_id='' started
  assert_demo_dir_safe
  stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  copy="${DENY_FILE}.walk-copy.${stamp}"
  new="${DENY_FILE}.walk.${stamp}"
  [[ ! -e "${copy}" && ! -e "${new}" ]] || mismatch '같은 이름이 있다 — 1초 뒤 다시'
  # 1 사본
  cp -p -- "${DENY_FILE}" "${copy}"
  # 2 새 파일 — 틀린 줄만 뺀다. 모드 · 소유는 원본(사본)과 같게 지정한다
  deny_scan "${copy}" "${WORK}/walk"
  bad="$(cat -- "${WORK}/walk.bad")"
  [[ "${bad}" != 0 ]] || mismatch '틀린 줄이 없다 — 걷을 것이 없다'
  cp -- "${WORK}/walk.kept" "${new}"
  chown --reference="${copy}" -- "${new}"
  chmod --reference="${copy}" -- "${new}"
  printf '사본: %s\n새 파일: %s\n' "${copy}" "${new}"
  # 3 단언
  assert_walk_files "${copy}" "${new}"
  # 응답기 시작 시각 — 컨테이너가 없으면 걷기 확인이 없다(다음 기동 블록이 파일을 본다)
  container_id_or_none ingest-auth auth_id
  started='없음 — 응답기 컨테이너가 없다'
  if [[ -n "${auth_id}" ]]; then
    started="$(docker inspect -f '{{.State.StartedAt}}' "${auth_id}")"
  fi
  # 4 재확인 → 끼우기
  assert_same "${DENY_FILE}" "${copy}" \
    '살아 있는 파일이 사본과 다르다 — 그사이 덧붙은 줄이 있다. 다시 돌린다'
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
  bad="$(cat -- "${WORK}/now.bad")"
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
| B | 롤백 B | T 백업 아홉 확인 · (반영 전 판 — 손 수정 패치 포함 — 에 응답기가 있으면) 거부 목록 확인 → ② 내리고 반영 전 SHA 로 · ③ 손 수정 패치 · ④ 추적 밖 정리 · ⑤ 되살리고 올림(응답기 유무를 다시 가려 다르면 멈춤 · 있으면 거부 목록 다시 확인 → 응답기 먼저 · healthy 뒤 나머지) · `.env` 는 마지막 | 있음 |
| C | 롤백 C | ⑥ 복귀 확인(읽기만 — 11-4) | — |
| D | 롤백 D | **리허설에서만** — T 로 다시 올림 | 있음 |
| E-1 | 롤백 E-1 | 리허설 뒤 T 상태 단언(읽기만) | — |
| E-2 | 롤백 E-2 | 리허설 뒤(T 가 돎) T 백업 아홉 · 비밀 임시 파일 지움 · 잔여 0 | 있음 |
| — | T 백업 처분 | 접힌 뒤 T 백업 아홉 · 비밀 임시 파일 지움 · 잔여 0(11-6) | 있음 |
| — | 반영 전 비밀 파기 | 롤백 창 닫기 — 접힌 끝 · T 정착에서만 반영 전 비밀 둘 · 비밀 임시 파일 지움(11-7) | 있음 |

**D 는 리허설(실측 8)에서만 쓴다(kty 81차).** 실제 롤백 · 최초 반영 실패 · 실측 실패로
0 → A → B → C 를 돌다 B · C 에서 막혀도 D 로 돌아가지 않는다. kty 판단으로 막힌 원인을
고쳐 B · C 를 끝내고, 다시 T 로 갈 때는 T 백업 처분 → R0 부터 새로 반영한다(11-6).

**승인.** 블록마다 실행 직전 kty 승인이다(2-1).

- 지우는 블록(B · D · E-2 · T 백업 처분 · 반영 전 비밀 파기)은 승인 문장에 11-5 의 해당
  행을 붙인다.
- A 와 B 는 그 사이에 아무것도 손대지 않도록 한 자리에서 잇달아 승인받는다. B ④ 의
  지우기 전 대조는 이름만 본다 — 내용은 A(보존)와 C · E-1 이 본다.
- D 의 승인 문장에는 리허설(실측 8)임을 적는다. 리허설이 아니면 D 를 승인하지 않는다.
- 반영 전 비밀 파기의 승인 문장에는 「실측 통과 · 첫 팀 실사용 방송 무사」를 적는다. 블록의
  첫머리는 접힌 끝 · T 정착(MediaMTX 까지 R6 이 다시 만든 T 의 컨테이너) 둘 가운데
  하나인지만 본다 — 반영 도중(R1–R6 — R5 끝 · R6 앞 포함) · 접는 도중은 블록이 멈춘다. T 가
  다 떠 있는 채 실측이 실패한 자리(F6 · F7)는 T 정착과 상태가 같아 블록이 가르지 못하고, 이
  문장이 가른다.

**멈춤.** 어느 블록이든 종료 1(일치 없음) · 2(검사 실패)면 그 자리에서 멈추고 11-5 의
그 행을 따른다. 지우는 쪽으로 밀어붙이지 않는다.

**접혀 있는 동안.** 첫 반영 주기라면 B 가 응답기를 내리고 옛 판이 공용 passphrase 하나로
송출을 받는다. 그동안 거부 목록과 긴급 절단(cut)은 효력이 없다 — cut 은 응답기 컨테이너가
없어 돌지 않고, 송출 통제는 옛 passphrase 하나뿐이다. 거부 목록 파일은 그대로 둔다 — 다시
T 로 갈 때 R5 · D 가 응답기를 띄우기 전에 다시 읽는다. 계정 ECR 판을 올린 주기라면 반영 전
판(ghcr 포크 `.2`)에도 응답기가 있다. B 는 내리기 전에 거부 목록 파일을 보고(7-4 — 어긋나면
바꾼 것 없이 멈춘다), 응답기를 띄우기 직전에 한 번 더 본 뒤 응답기를 먼저 띄워 healthy 를
보고 나머지를 올린다. 응답기 유무는 반영 전 SHA 의 compose 에 손 수정 패치의 compose 몫을 댄
것으로 가르고(내리기 전), 되살린 트리의 compose 로 다시 가른다(띄우기 전) — 둘이 다르면
띄우지 않고 멈춘다. 첫 주기 판처럼 응답기가 없으면 거부 목록을 보지 않는다 — T 의 거부 목록
탓에 옛 판으로의 롤백이 막히지 않게 한다. B 의
`down` 부터 응답기가 healthy 가 될 때까지는 응답기가 없어 새 연결이 모두 거부되고 cut 도
돌지 않는다. 그 뒤 접혀 있는 동안은 거부 목록 · cut 이 효력이 있다(복귀 확인의 도는
서비스도 셋이다 — 11-4).

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
- MediaMTX 이미지 좌표도 그 짝을 따른다 — 롤백 좌표를 따로 지정하지 않는다. 계정 ECR
  판(`v1.21.2-pokeclip.1`)을 올린 주기라면 반영 전 판의 compose 는 ghcr 포크 `.2` 좌표를
  글자 그대로 들고 있어, `MTX_IMAGE_REGISTRY` 가 없는 `.env.pre-T.bak` 으로도 풀리고 B ⑤
  는 ECR 로그인 없이 옛 이미지로 올린다. 옛 이미지가 상자에 없으면 `up` 이 ghcr 에서
  로그인 없이 다시 받는다(공개 패키지 — 2026-10-20 익명 조회 200). 남겨 두면 받기 없이
  올라가므로 롤백용 옛 이미지는 창을 닫을 때까지 지우지 않는다(11-7). D 는 R3′ 가 받아 둔
  ECR 이미지를 쓴다(`down` 은 이미지를 지우지 않는다) — 리허설 중에는 지우지 않는다.
- `config --images` 의 ECR 좌표에는 계정 번호가 있다. 사람이 볼 때는
  `sed -E 's/[0-9]{12}/<계정>/g'` 를 거쳐 보고, 출력을 기록에 붙이지 않는다.

### 11-3. 단계 블록

```bash
# ── 블록: 롤백 0 — 머리 + 이 본문. 인자 없음. 사전 확인(읽기만) ──
# 하나라도 어긋나면 롤백을 시작하지 않는다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local head_sha
  # 체크아웃 소유자로 git 이 돈다(소유자 검사로 멈추면 여기서 안다)
  owner_git status --porcelain > /dev/null
  assert_backups_whole 'pre-T'
  PRE_SHA="$(cat -- "${DEMO_DIR}/pre-T.sha")"
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
  assert_demo_dir_safe
  pre_sha="$(cat -- "${DEMO_DIR}/pre-T.sha")"
  head_sha="$(owner_git rev-parse HEAD)"
  [[ "${head_sha}" != "${pre_sha}" ]] || mismatch 'HEAD 가 반영 전과 같다'
  preserve_state 'T'
  echo 'T 상태 보존 끝 — 백업 아홉 단언 통과. 다음은 B(잇달아)'
}
main "$@"
```

```bash
# ── 블록: 롤백 B — 머리 + 이 본문. 인자 없음. ②–⑤ 옛 판으로. 지운다 ──
# 실행 직전 kty 승인(A 와 잇달아). 처음부터 다시 돌려도 이어진다(11-5). 반영 전 판에
# 응답기가 있으면(계정 ECR 판을 올린 주기 · 손 수정 패치가 더한 경우 포함) 내리기 전과
# 띄우기 직전에 거부 목록 파일을 보고(7-4), 응답기 먼저 · healthy 뒤 나머지를 올린다(운영
# 순서 — 7절). 첫 주기 판에는 응답기가 없어 거부 목록을 보지 않는다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local head_sha tracked
  local -i pre_responder responder
  local -r pre_tree="${WORK}/pre-T.tree"
  assert_demo_dir_safe
  # 지우기 전에 A 의 T 백업 아홉이 다 있고 묶음이 읽히는지 본다 — 끊긴 A 뒤에 B 가 돌면
  # ④ 가 묶음 없는 T 의 추적 밖 파일을 지운다
  assert_backups_whole 'T'
  PRE_SHA="$(cat -- "${DEMO_DIR}/pre-T.sha")"
  T_SHA="$(cat -- "${DEMO_DIR}/T.sha")"
  readonly PRE_SHA T_SHA
  [[ "${PRE_SHA}" != "${T_SHA}" ]] || mismatch 'T 와 반영 전 SHA 가 같다'
  # 반영 전 판 compose — 반영 전 SHA 의 것(git 에서 읽는다)에 ③ 이 댈 손 수정 패치의
  # compose 몫을 대 본다. 서비스에 응답기가 있으면 응답기를 다시 띄우므로 거부 목록 파일부터
  # 본다 — 어긋나면 바꾼 것 없이 멈춘다
  install -d -m 0700 -- "${pre_tree}/${COMPOSE_YML%/*}"
  owner_git show "${PRE_SHA}:${COMPOSE_YML}" > "${pre_tree}/${COMPOSE_YML}"
  if [[ -s "${DEMO_DIR}/pre-T.worktree.patch" ]]; then
    git -C "${pre_tree}" apply --include="${COMPOSE_YML}" --binary \
      < "${DEMO_DIR}/pre-T.worktree.patch"
  fi
  docker compose -p "${PROJECT}" --env-file "${DEMO_DIR}/.env.pre-T.bak" \
    --project-directory "${REPO}/infra/dev-media" \
    -f "${pre_tree}/${COMPOSE_YML}" config --services > "${WORK}/pre-T.services"
  pre_responder="$(count_lines_with "${WORK}/pre-T.services" ingest-auth)"
  if (( pre_responder )); then
    assert_ready_to_start_ingest_auth
  fi
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
  # 모든 컨테이너를 새로 만들어 단일 파일 마운트도 새로 묶인다. 되살린 트리의 compose 에
  # 응답기가 있으면 운영 순서 — 응답기 먼저, healthy 시한을 넘기면 MediaMTX 는 띄우지
  # 않는다. 반영 전 판이 계정 ECR 판이면 출력에 계정 번호가 든 좌표가 찍힐 수 있다 —
  # 가려 낸다
  # 되살린 트리의 판정이 내리기 전 판정과 다르면 띄우지 않는다. 응답기를 띄우기 직전에
  # 거부 목록 파일을 한 번 더 본다 — 내리기 전 확인 뒤에 바뀌었을 수 있다
  paired_compose config --services > "${WORK}/services"
  responder="$(count_lines_with "${WORK}/services" ingest-auth)"
  (( responder == pre_responder )) \
    || mismatch '되살린 compose 의 응답기 유무가 내리기 전 판정과 다르다 — 띄우지 않는다'
  if (( responder )); then
    assert_ready_to_start_ingest_auth
    paired_compose up -d ingest-auth
    wait_ingest_auth_healthy
  fi
  compose_up_masked paired_compose -d
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
  PRE_SHA="$(cat -- "${DEMO_DIR}/pre-T.sha")"
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
  assert_demo_dir_safe
  PRE_SHA="$(cat -- "${DEMO_DIR}/pre-T.sha")"
  T_SHA="$(cat -- "${DEMO_DIR}/T.sha")"
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
  compose_up_masked paired_compose -d   # 출력에 이미지 좌표가 찍힐 수 있다 — 가려 낸다
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
  t_sha="$(cat -- "${DEMO_DIR}/T.sha")"
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

- E-1 뒤 사람이 본다(실측 8) — `config --images` 에 T 의 MediaMTX 좌표(계정 ECR 판이면
  `v1.21.2-pokeclip.1` — 계정 번호는 가려 본다, 11-2), 토큰 경로 송출 · 재생, 응답기
  `publish_allowed`.

```bash
# ── 블록: 롤백 E-2 — 머리 + 이 본문. 인자 없음. 리허설 뒤 정리 · 잔여 0. 지운다 ──
# E-1 이 통과한 뒤에만 돈다. 실행 직전 kty 승인. 있는 것만 지운다.
main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local t_sha head_sha
  assert_demo_dir_safe
  # E-1 을 건너뛰어도 T 비밀의 마지막 사본을 단언 없이 지우지 않는다 — HEAD = T · 도는
  # 서비스가 셋(T 가 돈다) · .env 가 그 사본과 같을 때만 지운다. B ② 에서 멈춘 상태(HEAD ·
  # .env 는 T, 컨테이너는 내려감)는 둘째에서 멈춘다
  if [[ -e "${DEMO_DIR}/.env.T.bak" ]]; then
    t_sha="$(cat -- "${DEMO_DIR}/T.sha")"
    head_sha="$(owner_git rev-parse HEAD)"
    [[ "${head_sha}" == "${t_sha}" ]] \
      || mismatch 'HEAD 가 T SHA 가 아니다 — 접힌 뒤면 T 백업 처분(11-6)'
    list_running_services > "${WORK}/running"
    printf '%s\n' edge ingest-auth mediamtx > "${WORK}/running.want"
    assert_same "${WORK}/running" "${WORK}/running.want" \
      '도는 서비스가 edge · ingest-auth · mediamtx 가 아니다 — B · D 도중이면 그 블록부터'
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
- 이 compose 프로젝트의 컨테이너 · 망 이름이 반영 전 기록과 같고, 도는 서비스가 반영 전
  판 compose 의 서비스 전부다 — 첫 반영 주기는 edge · mediamtx 둘(`ingest-auth` 없음),
  계정 ECR 판을 올린 주기는 edge · ingest-auth · mediamtx 셋이다. 기대값은 고정값이 아니라
  반영 전 SHA 의 compose 를 `.env.pre-T.bak` 으로 푼 `config --services` 다.
- HEAD 형태는 견줘 알리기만 한다 — 반영 전이 브랜치였고 그 브랜치가 옮겨졌으면 B 가
  분리 HEAD 로 둔다.

사람이 본다 — `config --images` 에 반영 전 판의 MediaMTX 좌표, 그 판의 OBS 송출 · 재생
(`index.m3u8` 의 LL-HLS 마커). 첫 반영 주기는 포크 `.1` · 옛 단일 경로이고, 계정 ECR
판을 올린 주기는 ghcr 포크 `.2` · 토큰 경로다(11-2). 롤백 목표는 반영 전에 확인한 마지막
정상 상태(반영 전 SHA + 손 수정 + 그 판의 이미지)다.

### 11-5. 멈춘 자리별 상태

B · D 는 처음부터 다시 돌려도 이어진다 — 명령과 명령 사이에서 멈춘 경우다(명령 도중
끊김은 표 마지막 행). compose 와 env 는 HEAD 로 고르고, 모든 줄이 되풀이해도 같은
결과이며(`down` · `reset` · `checkout` · 패치는 `reset` 뒤에 다시 대고 · `clean` 은 두
목록 안의 이름만 지우고 · `tar` 는 덮어쓴다), `.env` 는 단계 끝에서만 바뀐다. 다시
돌려도 같은 자리에서 멈추는 것은 결정이 필요한 어긋남뿐이다.

**알려진 예외 — 체크아웃 소유자가 root 가 아니고 통째 추적 밖 디렉터리가 있을 때.** B · D 의
묶음 풀기(root)가 그 디렉터리를 `root:root 0755` 로 다시 만든다. 그래서 두 번째 B · D 의
`git clean` 이 권한 실패(`failed to remove … Permission denied`)로 `down` 뒤 종료 2 로
멈춘다 — 컨테이너가 모두 내려간 채다. 그 디렉터리의 소유 · 모드를 반영 전 기록(4-1
확인에서 남긴 `stat -c '%U:%G %a %n'` 출력)대로 손으로 되돌린 뒤(승인) 다시 돌린다. 이어진
블록이 다시 바꾸므로 다음 B · D 마다 되풀이한다(로컬 실측 S7-7 결함 1 · kty 2026-10-17 한계로
둠). 4-1 의 확인이 이 조건을 반영 전에 거른다.

반영 R4 뒤 · R6 앞에 옛 MediaMTX 가 새 렌더로 기동을 되풀이하는 채(전 송출 중단 · 로그
`bind: cannot assign requested address` — 4-2) 접을 때도 0 → A → B → C 가 그대로 이어진다.
B 가 반영 전 렌더로 옛 판을 새로 만든다(로컬 실측 fix3 E6 접기 1/1).

**「다른 길」 열의 D 는 리허설(실측 8)에서만 쓴다(kty 81차).** 실제 롤백 · 최초 반영
실패 · 실측 실패로 0 → A → B → C 를 돌다 B · C 에서 막히면 D 로 돌아가지 않는다 — kty
판단으로 막힌 원인을 고쳐 B · C 를 끝내고, T 백업 처분 → R0 부터 다시 반영한다(11-6).
그래서 D · E-1 · E-2 의 행은 리허설에서만 생긴다.

| 멈춘 자리 | 상태 | 그 자리에서 다시 돌리면 | 다른 길 |
|---|---|---|---|
| R0(반영 전 보존) | 5-1 F0 | 5-1 F0 | 첫머리(T 백업 남음)에서 멈췄으면 접힌 뒤는 T 백업 처분(11-6), T 가 도는 리허설 뒤(E-2 를 빠뜨림)는 E-1 → E-2 뒤 다시. 보존 단언이 어긋났고(종료 1) 남은 반영 전 기록이 직전 반영 주기의 것이면(T 가 정착한 뒤의 다음 반영) 롤백 창을 닫고 반영 전 일곱을 옮긴 뒤 다시(11-7 ③ — kty 승인) |
| 단계 0 | 바뀐 것 없음 — T 가 돈다 | 원인(신원 · 백업 · 도구 판)을 고친 뒤 이어진다 | HEAD 가 반영 전이면 롤백이 아니다 — 반영 도중이면 반영 취소(5-3) |
| 단계 A | T 가 돈다. T 백업 일부만 있다(반쪽은 `.part`) | 이어진다 — 있는 백업은 지금 상태와 같은지 단언하고 그대로 쓴다 | 단언이 어긋나면(종료 1) 지금 상태가 T 기록과 다르다 — A 를 B 뒤에 돌린 경우 · 끊긴 A 뒤 추적 밖 내용이 바뀐 경우 · 접은 뒤 처분 없이 다시 반영한 경우(옛 반영의 T 백업 — R0 첫머리가 먼저 막는다) 등. 덮어쓰지 않았으므로 그 자리에 맞는 아래 행으로 간다 |
| B 첫머리 — T 백업 확인(종료 1 · 2) | 바뀐 것 없음 — T 가 돈다 | 같은 자리에서 다시 멈춘다 | T 백업 아홉 가운데 없는 것이 있으면(종료 1) A 가 끝나지 않았다 — A 를 다시 돌려 이은 뒤 B. 묶음이 읽히지 않으면(종료 2) kty |
| B 첫머리 — 반영 전 판 판정 실패(종료 2) | 바뀐 것 없음 — T 가 돈다 | 같은 자리에서 다시 멈춘다 | 반영 전 SHA 의 compose 를 git 에서 읽지 못함(`git show`) · 손 수정 패치의 compose 몫이 그 compose 에 대지지 않음(`git apply`) · 반영 전 판 compose 가 `.env.pre-T.bak` 으로 풀리지 않음(`config --services`) 가운데 하나다 — 출력의 마지막 줄로 가린다. kty |
| B 첫머리 — 거부 목록 확인(종료 1 — 반영 전 판에 응답기가 있을 때만. 손 수정 패치가 더한 응답기도 센다) | 바뀐 것 없음 — T 가 돈다 | 같은 자리에서 다시 멈춘다 | D 첫머리 행과 같다(7-4 · 10절) |
| B ② `down` 앞 | 바뀐 것 없음 | 이어진다 | — |
| B ② `down` 뒤 – `checkout` 앞 | 컨테이너 내려감. HEAD = T. `.env` T | 이어진다 — 첫 `down` 은 T compose + `.env.T.bak` | 리허설이면 D 로 T 에 돌아가도 된다. 실제 롤백은 B 를 다시 돌려 끝낸다 |
| B ② `checkout` 이 멈춤(추적 밖 파일과 겹침) | 컨테이너 내려감. HEAD = T, 추적 파일 깨끗(`reset` 뒤). 아무것도 지우지 않았다 | 같은 자리에서 다시 멈춘다(종료 2 — git 이 이름을 알린다) | 그 파일은 T 묶음에 있다. kty 가 정한 뒤(옮김) B 를 다시 돌리면 이어진다. 리허설이면 D 로 T 에 돌아가도 된다 — 실제 롤백은 D 를 쓰지 않는다 |
| B ② `checkout` 뒤 – ③ | 컨테이너 내려감. HEAD = 반영 전. 손 수정 패치는 아직 없음(③ 앞) 또는 전부(③ 뒤). `.env` T. `apply` 도중 끊긴 반쪽 패치는 이 행이 아니라 표 마지막 행이다 | 이어진다 — `down` 은 옛 compose + `.env.pre-T.bak`, `reset` 이 댄 패치를 버리고 ③ 이 다시 댄다 | `apply --check` 가 실패했으면(종료 2) 패치가 반영 전 SHA 에 맞지 않는다 — 다시 돌려도 같은 자리다. kty 가 원인을 고친 뒤 B 를 다시. 리허설이면 D — 실제 롤백은 D 를 쓰지 않는다 |
| B ④ 목록 대조 실패 | 아무것도 지우지 않았다. 컨테이너 내려감. HEAD = 반영 전 + 손 수정. `.env` T | 같은 자리에서 다시 멈춘다(종료 1) | 리허설의 D 도 같은 대조에서 멈춘다 — 그 이름은 T 목록에도 없다. 원인 후보 — A 뒤 생긴 파일 · T 에서 새로 생긴, T 판이 무시하고 반영 전 판은 무시하지 않는 파일 · 반영 전에 스테이징된 새 파일. kty 가 그 파일을 정한다(보존할 곳으로 옮김 — 지우면 승인). 그 뒤 B 를 다시 돌리면 이어진다 |
| B ④ `clean` 뒤 – ⑤ 상태 대조 앞 | 지금 규칙의 추적 밖이 비었다(두 묶음에 있음). 컨테이너 내려감. `.env` T | 이어진다 — 대조는 빈 목록을 통과하고 `tar` 가 되살린다 | 리허설이면 D |
| B ⑤ 상태 대조 실패 | 작업 트리가 반영 전 기록과 다르다. 컨테이너 내려감. `.env` T | 같은 자리에서 다시 멈춘다(종료 1) | 원인 후보 — 반영 전의 스테이징 · 묶음과 트리의 차이. 띄우지 않는다. 차분을 보고 kty 가 원인을 고친 뒤 B 를 다시. 리허설이면 D 로 T 에 돌아가도 된다 — 실제 롤백은 D 를 쓰지 않는다 |
| B ⑤ HEAD 형태 · 렌더 산출물 복원 – `up` | 옛 판 트리 · 옛 렌더. 컨테이너 내려감 또는 일부. `.env` T | 이어진다. `config --quiet` 가 실패하면(종료 2) 옛 compose 가 `.env.pre-T.bak` 으로 풀리지 않는다 — 다시 돌려도 같은 자리, kty 가 원인을 고친 뒤 B 를 다시 | 리허설이면 D |
| B ⑤ 응답기 판정 불일치 · 거부 목록 다시 확인(종료 1) | 옛 판 트리 · 옛 렌더. 컨테이너 내려감 — 아무것도 띄우지 않았다. `.env` T | 같은 자리에서 다시 멈춘다 | 판정 불일치면(되살린 compose 의 응답기 유무가 내리기 전 판정과 다르다) 트리 · 패치를 보고 kty. 거부 목록이면 D 첫머리 행과 같다(7-4 · 10절) — 고친 뒤 B 를 다시 돌리면 이어진다. 리허설이면 D |
| B ⑤ healthy 시한 초과(반영 전 판에 응답기가 있을 때) | 옛 판 트리 · 옛 렌더. `ingest-auth` 만 떠 있고 MediaMTX 는 띄우지 않았다(운영 순서). `.env` T | 원인(응답기 로그)을 고친 뒤 이어진다. 거부 목록의 틀린 줄이면 손으로 고치지 않고 틀린 줄 걷기(10절 — 재시작 없음)를 돈다 | 리허설이면 D |
| B ⑤ `.env` 복원 앞(띄운 뒤) | 옛 판이 돈다. `.env` 만 T | 이어진다(한 번 더 내렸다 올린다) | 리허설이면 D |
| 단계 C | B 의 끝 상태. C 는 읽기만 | 같은 결과다 | 어긋남(종료 1)은 B 의 끝 상태가 반영 전 기록과 다르다는 뜻 — 추적 밖 이름 · 내용 포함. B 가 끝나지 않았으면 B 를 다시. 그 밖은 차분을 보고 kty 가 원인을 고친 뒤 B → C 를 다시 돈다 — 실제 롤백은 그다음 T 백업 처분 → R0 다. 리허설이면 D 로 T 에 돌아가도 된다 |
| D 첫머리 — 거부 목록 확인(종료 1) | 바뀐 것 없음 | 같은 자리에서 다시 멈춘다 | 7-4 — 파일이 없으면 되살릴 내용을 kty 가 정한다. 틀린 줄이면 틀린 줄 걷기(10절 — 응답기 컨테이너가 없으면 끼우기만 한다) 뒤 D 를 다시 돌린다 |
| D 어디서든(명령 사이) | HEAD 는 반영 전 또는 T. `.env` 는 옛 판(단계 끝에서만 바뀐다) | 이어진다 — 첫 `down` 은 HEAD 로 고른 짝이다. T 체크아웃 뒤 · `.env` 복원 앞에서 멈췄어도 T compose + `.env.T.bak` 이다 | ④ 목록 대조 실패는 B ④ 행과 같다. ⑤ 상태 대조 실패는 kty |
| D 의 healthy 시한 초과 | `ingest-auth` 만 떠 있고 MediaMTX 는 띄우지 않았다(운영 순서). `.env` 옛 판 | 원인(응답기 로그)을 고친 뒤 이어진다. 거부 목록의 틀린 줄이면 손으로 고치지 않고 틀린 줄 걷기(10절 — 재시작 없음)를 돈다 | — |
| 단계 E-1 | 바뀐 것 없음(읽기만) | 같은 결과다 | 어긋남(종료 1)은 T 가 A 의 기록대로 돌아오지 않았다는 뜻 — 추적 밖 이름 · 내용 포함. 아무것도 지우지 않았다. kty |
| 단계 E-2 | T 백업 일부가 지워졌다 | 이어진다 — 있는 것만 지운다. `.env.T.bak` 이 남아 있으면 먼저 HEAD = T · 도는 서비스가 edge · ingest-auth · mediamtx · `.env` = `.env.T.bak` 을 본다. 어긋나면(B ② 에서 멈춤 — 컨테이너 내려감 · D 가 `.env` 복원 앞에서 멈춘 채 E-1 을 건너뜀 · 접힌 뒤) 아무것도 지우지 않고 멈춘다(종료 1). 링크 · 일반 파일이 아닌 이름, `.ecr-login` 안의 모르는 파일(끊긴 R3′ 의 자리 — `config.json` 만 있어야 한다)이나 잔여가 남으면(종료 1) 그 이름을 보이고 kty | B ② 에서 멈췄으면 그 행대로 B 를 끝내거나 D 로 T 에 돌아간 뒤 E-1 → E-2. `.env` 가 다르면 D 를 다시 돌린 뒤 E-1 → E-2. 접힌 뒤면 T 백업 처분 |
| T 백업 처분 | 첫머리 단언에서 멈췄으면 지운 것이 없다. 지우는 중에 멈췄으면 T 백업 일부가 지워졌다 — 반영 전 상태는 그대로 | 이어진다 — 있는 것만 지운다. 첫머리(HEAD = 반영 전 · `.env` = `.env.pre-T.bak` · 복귀 확인)가 어긋나면(종료 1) 아무것도 지우지 않는다 — T 가 돌거나 B 가 끝나지 않았거나 접힌 끝 상태가 반영 전 기록과 다르다. `.env.pre-T.bak` 이 없으면 검사 실패(종료 2)다. 지우는 중 링크 · 일반 파일이 아닌 이름 · `.ecr-login` 안의 모르는 파일을 만나면 그것은 지우지 않고 멈춘다(종료 1 — 그 이름을 보이고 kty) | B 가 끝나지 않았으면 B 를 다시 돌린 뒤 C → 처분. 복귀 확인이 어긋나면 단계 C 행과 같다(kty). T 가 돌면 처분하지 않는다 — 그 T 백업은 롤백의 길이다. 반영 전 비밀을 먼저 지웠으면 이 블록은 돌지 못한다(11-7) |
| 반영 전 비밀 파기 | 첫머리(T 백업 남음 · 안정 상태 아님 · 7-5 실행 중)에서 멈췄으면 지운 것이 없다. 지우는 중에 멈췄으면 비밀 하나만 지워졌을 수 있다 — `.env.pre-T.bak` 을 맨 먼저 지운다 | 이어진다 — 있는 것만 지운다. `.env.pre-T.bak` 이 이미 없으면 롤백 길이 이미 없어 안정 상태 단언을 건너뛴다 | T 백업이 남았으면 접힌 뒤는 T 백업 처분, T 가 도는 리허설 뒤는 E-1 → E-2 가 먼저다. 안정 상태(접힌 끝 · T 정착)가 아니면 반영 도중 · 접는 도중이다 — 반영(4절) · 반영 실패 복구(5절) · 롤백을 그 행대로 끝낸 뒤 다시. 「MediaMTX 가 제어망에 붙어 있지 않다」면 R5 끝 · R6 앞이다(반영은 R6 부터 잇는다). 「IP 한도 켜기 · 끄기(7-5)가 돌고 있다」면 그 실행이 끝난 뒤 다시. 링크 · 일반 파일이 아닌 이름 · `.ecr-login` 안의 모르는 파일로 멈췄으면(종료 1) 그 이름을 보고 kty |
| 명령 도중 끊김(프로세스가 죽음 — 상자 재부팅 · 명령 시한 등) | 트리 일부만 바뀌었거나 `.git/index.lock` 이 남았다. HEAD 는 옛 값일 수 있다 | `clean` · `tar` 풀기 도중이면 이어진다(남은 이름이 두 목록 안이고 `clean` · `tar` 가 다시 맞춘다 — 끊긴 뒤 상태를 손으로 만들어 본 로컬 실측 S7-7 L10). `apply` 도중이면 손 수정 패치가 고치는 파일이 지워지거나 잘린 채일 수 있다 — 그 파일이 `compose.yml` 이면 첫 `down` 이 compose 파일을 읽지 못해 같은 자리에서 다시 멈춘다(종료 2 — `failed to parse …/compose.yml` · `compose file … is invalid`. B 는 잘리거나 없는 compose.yml 을 손으로 만들어 본 로컬 실측 S7-7 L10, D 는 추론). `reset` · `checkout` 도중이면 지우기 전에 멈춘다(종료 2) — `reset` 이 남은 lock 에서, 또는 첫 `down` 이 HEAD 로 고른 env 와 판이 어긋난 compose 의 `:?` 에서 | kty — 남은 lock · 트리 상태를 보고 정한다(lock 을 지우는 것은 승인). `compose.yml` 이 깨졌으면 `sudo -u <체크아웃 소유자> git -C /opt/pokeclip-demo/repo checkout -- infra/dev-media/compose.yml`(그때 HEAD 판으로 되돌린다 — 승인) 뒤 B · D 를 다시 돌린다(B 에서 이어짐 — 같은 로컬 실측). 블록은 RunShellScript 로 보내 세션 끊김으로는 이 행에 오지 않는다 |

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
  assert_demo_dir_safe
  PRE_SHA="$(cat -- "${DEMO_DIR}/pre-T.sha")"
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
- **auth 출입증은 되돌리지 않는다.** 이 롤백이 되돌리는 범위는 상자의 설정 · 코드뿐이다.
  resolve 출입증은 반영 전 `.env` 에도 있어(3절 — 반영 전에 이미 들어갔다) 롤백한 뒤에도
  살아 있는 `.env` 에 남는다 — T 백업 · 반영 전 비밀을 지워도 상자에서 사라지지 않는다.
  옛 구성은 그 출입증을 쓰지 않는다. 롤백한 채 창을 닫으면 그 출입증의 처분(줄을 남김 ·
  뺌 · auth 쪽 창구 닫기 — 값 삭제 → auth 재생성)은 kty 가 정한다.
- **`shred -u` 는 이름 제거 + 최선 노력이다.** 저널링 파일 시스템 · EBS 에서는 덮어쓴
  자리가 옛 블록과 같다는 보장이 없다 — 지운 비밀이 디스크 블록에 남을 수 있다.
- **shred 를 거치지 않고 놓이는 비밀의 옛 판도 있다.** R1 의 이름 바꾸기(반영 전
  `.env`) · render.sh 의 이름 바꾸기(옛 렌더 산출물) · 반영 취소 ⓐ · B ⑤ · D 의 `cp -p`
  덮어쓰기(`.env` · 렌더 산출물의 옛 내용) · `compose down`(응답기 컨테이너 설정의 환경
  변수 — 비밀 둘)은 옛 내용을 지우지 않고 놓아 준다. 그 블록도 디스크에 남을 수 있다.
- **끝까지 없애는 것은 상자 철거다**(12절 7 — 인스턴스 종료로 볼륨이 지워진다). 그것도
  이 볼륨의 스냅숏 · 이 인스턴스로 만든 AMI 가 없을 때만이다 — 철거 때 함께 본다(12절 6).

**롤백 창 닫기(12단계 — 실측 통과 뒤 첫 팀 실사용 방송이 무사하면).** 차례가 있다.

1.  실제로 롤백했고 다시 반영하지 않았으면 먼저 T 백업 처분(11-6)을 돈다. 그 블록의
    첫머리가 `.env.pre-T.bak` 을 보므로 반영 전 비밀보다 먼저다. 거꾸로 하면 처분 블록이
    검사 실패(종료 2)로 돌지 못하고, 남은 T 일곱이 다음 반영의 R0 첫머리를 막는다. 접은
    뒤 다시 반영했으면 처분이 그 전에 지웠고, 리허설 뒤면 E-2 가 지웠다.
1.  그다음 아래 「반영 전 비밀 파기」로 반영 전 비밀 둘(`.env.pre-T.bak` — 옛
    passphrase · resolve 출입증(T 와 같은 값), `mediamtx.demo.yml.pre-T.bak` — 옛 렌더
    산출물)과 그 `.part`, 끊긴 R1 · 렌더 · R3′ 가 남긴 비밀 임시 파일(`.env.next` ·
    `mediamtx.demo.yml.tmp.*` · `.ecr-login`)을 지운다. 이 블록은 T 백업이 하나라도 남아 있거나 안정
    상태(접힌 끝 · T 정착)가 아니면 지우지 않고 멈춘다 — 위 차례와 반영 · 접기 도중이
    아님을 블록이 지킨다. T 가 다 떠 있는 채 실측이 실패한 자리(F6 · F7)는 블록이 가르지
    못해 승인 문장(11-1)이 가른다.
1.  반영 전의 나머지 일곱(`.sha` · `.head` · `.status` · `.worktree.patch` ·
    `.untracked.z` · `.untracked.tgz` · `.docker`)은 비밀이 아니라 남겨도 된다. 반영 전
    비밀을 지운 뒤에는 이 판으로 롤백할 수 없다. 다만 T 가 정착한 채 창을 닫았으면 남은
    일곱은 끝난 반영 주기의 기록이라, 다음 반영(T → 다음 판)의 R0 가 지금 상태와 견줘
    「일치 없음」으로 멈춘다(5-1 F0). 다음 반영 전에 그 일곱을 보관할 곳으로 옮긴다 —
    옮길 곳과 실행은 kty 승인이다(블록을 두지 않는다).
1.  반영 전에 상자에 남아 있던 `compose.yml.bak-*` 둘과 롤백용 옛 이미지의 처분도 그때
    kty 에게 묻는다.

```bash
# ── 블록: 반영 전 비밀 파기 — 머리 + 이 본문. 인자 없음. 롤백 창 닫기 2. 지운다 ──
# 실행 직전 kty 승인(승인 문장 — 11-1). 첫머리 단언 넷 가운데 하나라도 어긋나면 아무것도
# 지우지 않고 멈춘다(종료 1). ① 상자 디렉터리. ② T 백업이 하나도 없다 — T 백업 처분(접힌
# 뒤) · E-2(리허설 뒤)가 먼저다. ③ 반영 전 .env 백업이 남아 있으면 안정 상태 둘 가운데
# 하나다(assert_settled_for_pre_t_disposal). ④ IP 한도 켜기 · 끄기(7-5)가 돌고 있지 않다 —
# 첫 삭제 전에 lock_ip_limit 를 잡는다(뒤의 dispose_stray_secrets 도 같은 잠금이라 그대로
# 지나간다). 지운 뒤에는 이 판으로 롤백할 수 없다.

# assert_mediamtx_on_control_net 은 도는 MediaMTX 가 T 의 컨테이너인지 본다 — 제어망
# (mtx-control)은 T 의 compose 에만 있어, R6 이 다시 만든 MediaMTX 만 그 망에 붙는다. R5 는
# 응답기만 띄우므로 R5 끝 · R6 앞에는 옛 MediaMTX(기본 망만)가 돈다. 붙어 있지 않으면 일치
# 없음이다.
assert_mediamtx_on_control_net() {
  docker ps -q --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter "label=com.docker.compose.service=mediamtx" \
    --filter "network=${PROJECT}_mtx-control" > "${WORK}/mtx.on-control"
  [[ -s "${WORK}/mtx.on-control" ]] \
    || mismatch "MediaMTX 가 제어망(${PROJECT}_mtx-control)에 붙어 있지 않다 —" \
      'R5 끝 · R6 앞(옛 MediaMTX)이다'
}

# assert_settled_for_pre_t_disposal 은 반영 전 비밀을 지워도 되는 안정 상태인지 본다 —
# 접힌 끝(HEAD = 반영 전 · .env = .env.pre-T.bak · 복귀 확인) 또는 T 정착(HEAD ≠ 반영 전 ·
# 도는 서비스가 edge · ingest-auth · mediamtx · MediaMTX 가 제어망에 붙음 · 응답기 healthy ·
# .env ≠ .env.pre-T.bak). 반영 도중(R1–R6 — R5 끝 · R6 앞의 옛 MediaMTX 포함)과 접는 도중은
# 둘 다 아니라 멈춘다. F6 · F7(T 가 다 떠 있는 채 실패)은 상태로 가를 수 없어 승인 문장이
# 가른다(11-1).
assert_settled_for_pre_t_disposal() {
  local head_sha
  local -i rc=0
  PRE_SHA="$(cat -- "${DEMO_DIR}/pre-T.sha")"
  readonly PRE_SHA
  head_sha="$(owner_git rev-parse HEAD)"
  if [[ "${head_sha}" == "${PRE_SHA}" ]]; then
    assert_same "${DEMO_DIR}/.env.pre-T.bak" "${DEMO_DIR}/.env" \
      '.env 가 반영 전이 아니다 — 접힌 끝이 아니다(반영 R1 뒤 · B 도중)'
    assert_back_to_pre_t
    return 0
  fi
  list_running_services > "${WORK}/running"
  printf '%s\n' edge ingest-auth mediamtx > "${WORK}/running.want"
  assert_same "${WORK}/running" "${WORK}/running.want" \
    '도는 서비스가 edge · ingest-auth · mediamtx 가 아니다 — 반영 · 접기 도중이다'
  assert_mediamtx_on_control_net
  wait_ingest_auth_healthy
  cmp -s -- "${DEMO_DIR}/.env.pre-T.bak" "${DEMO_DIR}/.env" || rc=$?
  case "${rc}" in
    0) mismatch '.env 가 반영 전과 같다 — T 가 정착하지 않았다' ;;
    1) ;;
    *) check_failed "cmp ${rc}: .env.pre-T.bak · .env" ;;
  esac
}

main() {
  (( $# == 0 )) || check_failed '사용법: 인자 없음'
  local -r pre_env="${DEMO_DIR}/.env.pre-T.bak"
  local name left=''
  assert_demo_dir_safe
  t_backup_names > "${WORK}/t-left"
  [[ ! -s "${WORK}/t-left" ]] \
    || mismatch 'T 백업이 남아 있다 — T 백업 처분 · E-2 가 먼저다:' \
      "$(tr '\n' ' ' < "${WORK}/t-left")"
  # 반영 전 .env 백업을 이미 지웠으면(끊긴 파기를 다시 돌림) 롤백 길이 이미 없다 — ③ 을
  # 건너뛰고 남은 것만 지운다. 그래서 .env.pre-T.bak 을 맨 먼저 지운다
  if [[ -e "${pre_env}" || -L "${pre_env}" ]]; then
    assert_settled_for_pre_t_disposal
  fi
  lock_ip_limit   # ④ — 7-5 가 돌고 있으면 아무것도 지우지 않고 일치 없음
  for name in .env.pre-T.bak mediamtx.demo.yml.pre-T.bak; do
    remove_box_file shred "${DEMO_DIR}/${name}"
    remove_box_file shred "${DEMO_DIR}/${name}.part"
  done
  # 끊긴 R1 · 렌더 · R3′ · 7-5 가 남긴 비밀 임시 파일(.env.next · .env.iplimit.* ·
  # mediamtx.demo.yml.tmp.* · .ecr-login)
  dispose_stray_secrets
  # 비밀이 아닌 일곱은 남긴다. 끊긴 보존이 남긴 그 .part 만 지운다
  for name in sha head status worktree.patch untracked.z untracked.tgz \
    docker; do
    remove_box_file rm "${DEMO_DIR}/pre-T.${name}.part"
  done
  ls -1A -- "${DEMO_DIR}" > "${WORK}/names"   # 목록을 먼저 받는다 — 못 받으면 검사 실패
  while IFS= read -r name; do
    case "${name}" in
      *.pre-T.bak | *.pre-T.bak.part | pre-T.*.part | .env.next \
        | .env.iplimit.* | mediamtx.demo.yml.tmp.* | .ecr-login)
        left+="${name} "
        ;;
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
뒤에만 지워진다. 세션이 다르면 SG·인스턴스 ID 를 이름·태그로 다시 집는다.

```bash
SG=$(aws ec2 describe-security-groups --filters Name=group-name,Values=pokeclip-media-demo-sg \
      --query 'SecurityGroups[0].GroupId' --output text)
IID=$(aws ec2 describe-instances --filters Name=tag:Name,Values=pokeclip-media-demo \
       Name=instance-state-name,Values=running,stopped \
       --query 'Reservations[0].Instances[0].InstanceId' --output text)
```

1. **terminate 전에 캡처**: 볼륨 ID와 `DeleteOnTermination` 값을 적어 둔다. `false` 면 먼저 `true` 로 바꾸거나 종료 후 볼륨을 직접 지운다.
   상자 역할 이름도 1-5 의 블록 1(역할 집기)로 집어 둔다(11 이 `ROLE` 을 쓴다).
   ```bash
   aws ec2 describe-instances --instance-ids <instance-id> \
     --query 'Reservations[0].Instances[0].BlockDeviceMappings[].Ebs.{id:VolumeId,del:DeleteOnTermination}'
   ```
2. `aws ec2 terminate-instances --instance-ids <instance-id>` → describe 가 `terminated`
3. `aws ec2 describe-volumes --volume-ids <vol-id>` → `InvalidVolume.NotFound`
4. `aws ec2 delete-security-group --group-id "$SG"` → describe 0건
5. A 레코드 `DELETE`(1-3의 change-batch에서 Action 만 바꾼다) → `dig` 응답 없음
6. 계정 훑기: `describe-instances` · `describe-volumes` · `describe-addresses` 에 demo 태그 잔여 0건
   — 이 볼륨의 스냅숏 · 이 인스턴스로 만든 AMI 도 0건이어야 볼륨의 비밀이 끝난다(11-7).
   계정 전체가 아니라 1 에서 적은 볼륨 · 인스턴스 ID 와 demo 태그로 좁혀 본다 — 넷 다 빈
   출력이어야 한다. `source-instance-id` 는 CreateImage 로 만든 AMI 만 잡으므로 태그로도 본다.
   같은 리전 복사본(CopySnapshot · CopyImage)은 볼륨 ID 가 임의 값이 되고 태그도 기본으로는
   복사되지 않아 이 넷이 잡지 않는다 — 복사한 적이 있으면 따로 본다.
   ```bash
   aws ec2 describe-snapshots --owner-ids self \
     --filters Name=volume-id,Values=<vol-id> \
     --query 'Snapshots[].SnapshotId' --output text
   aws ec2 describe-snapshots --owner-ids self \
     --filters Name=tag:Name,Values=pokeclip-media-demo \
     --query 'Snapshots[].SnapshotId' --output text
   aws ec2 describe-images --owners self \
     --filters Name=source-instance-id,Values=<instance-id> \
     --query 'Images[].ImageId' --output text
   aws ec2 describe-images --owners self \
     --filters Name=tag:Name,Values=pokeclip-media-demo \
     --query 'Images[].ImageId' --output text
   ```
7. 상자의 비밀 파기 — `.env` · 반영 전 · T `.bak` · 끊긴 R1 · 렌더 · R3′ 가 남긴 `.env.next` ·
   `mediamtx.demo.yml.tmp.*` · `.ecr-login`(인스턴스 종료로 자동이지만 명시한다)
8. 노트북의 로컬 리허설 디렉터리(`.env` · 렌더물) 삭제 + **클립보드 비우기**
9. OBS 플러그인 — 시험 계정의 연결을 해제한다
10. 저장소 정리 PR — `infra/dev-media/` 와 그 포인터를 지울지 kty 가 정한다
11. 상자 역할의 ECR 읽기 정책(1-5) 삭제 — 1 에서 집은 `ROLE` 로 지우고
    `list-role-policies` 에 없음을 본다.
    ```bash
    aws iam delete-role-policy --role-name "${ROLE}" \
      --policy-name pokeclip-media-dev-ecr-pull
    aws iam list-role-policies --role-name "${ROLE}"
    ```

### 중간에 멈출 때 (역순 롤백)

| 어디까지 갔나 | 되돌릴 것 | 확인 |
|---|---|---|
| 운영 중 | 위 1–11 전부 | 철거 체크리스트 |
| 배포까지(4절) | A 레코드 DELETE → terminate → 볼륨 부재 → 보안그룹 삭제 → ECR 읽기 정책 삭제(11 — 역할은 terminate 전에 집는다) | 각 describe 0건 · 정책 없음 |
| DNS까지(1-3) | A 레코드 DELETE → terminate → 보안그룹 삭제 | 위와 동일 |
| 보안그룹만(1-1) | 보안그룹 삭제 | describe 0건 |

어느 경우에도 팀 dev EC2와 공용 compose 파일은 건드리지 않으므로 팀에 미치는 영향은 없다.
