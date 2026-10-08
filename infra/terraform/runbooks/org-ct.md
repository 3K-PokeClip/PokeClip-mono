# 조직 · CT 도입 런북

새 관리 계정으로 AWS Organizations 조직을 만들고, 기존 계정을 초대한 뒤
Control Tower(CT) 랜딩 존을 설정하는 절차입니다. 설계서 8절의 2단계(조직)와
3단계(CT), 계획의 운영 단계 O2 · O3 에 해당합니다.

용어:

*   **관리 계정**: 조직을 만든 계정입니다. 조직 · CT 를 다스리며 워크로드를
    두지 않습니다. SCP 는 관리 계정에 적용되지 않습니다.
*   **랜딩 존(LZ)**: CT 가 Organizations · IAM Identity Center(IdC) ·
    CloudTrail · Config 를 표준 구성으로 설치한 결과입니다.
*   **통합 계정**: LZ 가 로그 보관(Logging) · 보안 역할(SecurityRoles) ·
    Config 집계를 맡기는 계정입니다. LZ 최초 설정 때에만 지정할 수 있습니다.
*   **베이스라인**: CT 가 OU 나 계정에 까는 표준 자원 묶음입니다. OU 를 CT 에
    등록하면 `AWSControlTowerBaseline` 이 켜집니다.

## 누가 무엇을 하나

| 주체 | 하는 일 | 하지 않는 일 |
|---|---|---|
| kty(콘솔 · CloudShell) | 계정 생성 · 조직 생성 · 관리 계정 이메일 검증 · 초대와 수락 · 역할 수동 생성 · Config · CloudTrail 정리 · CT 콘솔 작업 전부. 콘솔로 할 수 없는 쓰기(2-7 의 Config 레코더 · 전달 채널 삭제)는 CloudShell 에서 CLI 로 직접 실행 | — |
| 오케스트레이터 | kty 가 승인한 읽기 확인(아래 「완료 확인」의 읽기 명령)과 결과 기록 | 쓰기 명령 · 콘솔 작업 |
| 에이전트 | 없음 | **계정 생성 · 초대 · CT 콘솔 작업은 에이전트가 하지 않습니다** |

*   콘솔 · CloudShell 쓰기는 모두 kty 가 합니다. CloudShell 의 CLI 쓰기는
    콘솔로 할 수 없는 작업(2-7)에 한한 예외입니다. 오케스트레이터는 그때도
    읽기 확인만 합니다.
*   모든 단계가 승인 지점입니다(설계서 8절 「각 단계」 · 「각 등록」). 한 단계의
    완료 확인이 끝나기 전에 다음 단계로 가지 않습니다. 예외는 하나, 2-7 의
    「삭제 시점」입니다.
*   갈래가 나뉘는 걸음(2-5 · 2-6 · 3-1)에서는 그 걸음에서 kty 가 갈래를
    고릅니다. 고르는 것 자체가 승인 지점입니다.
*   이 런북의 결과물(조직 · OU · 계정 · CT 가 만든 자원)은 Terraform 이
    소유하지 않습니다. 계정 ID 는 4단계부터 Terraform 입력 변수로 넘깁니다
    (설계서 2-2).
*   계정 ID · 이메일 · 계정 이름은 이 문서와 저장소에 적지 않습니다. 아래의
    `<ACCOUNT_ID_MGMT>` 같은 자리표시자로만 씁니다.

## 정해진 것(kty 결정)

| 항목 | 값 | 출처 |
|---|---|---|
| 관리 계정 | **새로 만듭니다**(워크로드 0) | kty 3차 · 볼트 Knowledge `aws-multi-account-landing-zone` 2절 |
| 기존 계정 | NonProd OU 멤버로 초대 → CT 등록 | kty 3차 |
| prod 계정 | 새로 만듭니다(Account Factory) | kty 3차 |
| 계정 수 | 다섯 개 — 관리 · Log Archive · Audit · prod · 기존(NonProd) | 설계서 2-1 |
| CT 도입 시점 | 지금(강의안 방식) | kty 3차 |
| CT Config 통합 | **켬**(LZ 전체 리전 거부 · Account Factory · CT 관리 IdC) | kty 4차 D5 |
| 리전 거부 | **켬** — 서울 + `us-east-1` 밖 거부 | kty 4차 D5 · 설계서 5-2 |
| 홈 리전 | 서울(`ap-northeast-2`) — **바꿀 수 없습니다** | 설계서 2-1 |
| 거버넌스 리전 | 서울 + `us-east-1` | kty 3차 |
| LZ 판 | 4.0 | 설계서 8절 3단계 |

목표 구조(설계서 2-1):

```
Root
├── (관리 계정 — 새로 만듦, 워크로드 0)
├── Security OU
│   ├── Log Archive 계정   (Logging 통합)                    ← LZ 최초 설정 때 새로 만듦
│   └── Audit 계정         (SecurityRoles · Config 통합 겸용) ← LZ 최초 설정 때 새로 만듦
└── Workloads OU
    ├── Prod OU            → prod 계정(Account Factory · CT VPC 생성 끔)
    └── NonProd OU         → 기존 계정(초대 → 등록)
```

*   서비스 통합 계정은 모두 같은 부모 OU(Security OU) 아래에 둡니다.
*   기존 계정은 dev 워크로드가 있는 NonProd 라 통합 계정으로 쓰지 않습니다.
*   Audit 계정의 SecurityRoles · Config 겸용은 3-1 화면에서 허용되는지
    확인합니다(아래 「확인하지 못한 것」).

## 사전 조건(설계서 2-3)

1번부터 5번은 2단계 안의 한 걸음으로, 6번은 3단계 안의 한 걸음(3-3)으로
처리합니다.

| 번호 | 사전 조건 | 처리하는 걸음 |
|---|---|---|
| 1 | 기존 계정의 현재 조직 소속 확인(읽기 전용) | 2-1 |
| 2 | 과거 비용 이력 받아 두기(설계서는 「CUR 로 내보내기」) | 2-2 |
| 3 | 기존 계정에 Config 자원 0 · CloudTrail 트레일 정리 | 2-7 |
| 4 | 기존 계정에 `AWSControlTowerExecution` 수동 생성 | 2-6 |
| 5 | 서울 · `us-east-1` 밖 자원 읽기 전용 조사 | 2-8 |
| 6 | Account Factory 설정에서 VPC 생성 끄기 | 3-3 |

선행: 1단계 ⓔ(nonprod/bootstrap · dev · dns-legacy import)가 끝나야 합니다.

## 2단계 — 조직(O2)

### 2-1. 기존 계정의 조직 소속 확인

*   **누가**: kty(콘솔). 오케스트레이터는 kty 가 알려 준 결과를 기록합니다.
*   **왜**: `.github/workflows/services-deploy.yml` 11행 주석에 이 계정이 다른
    조직의 멤버였다는 기록이 있고, 지금 소속은 **미확인**입니다. 소속에 따라
    2-5 의 갈래(비소속 초대 · 직접 이전 · 독립 전환)가 달라집니다.
*   **지금 아는 것**:
    *   O1 읽기 조사(2026-10-08)에서 IAM 사용자로
        `aws organizations describe-organization` 을 부르면 `AccessDenied` 가
        났습니다.
    *   같은 파일 8 · 12행 주석: GitHub OIDC 역할 위임이 모든 설정이 맞는데도
        거부됐고, 상위 조직 SCP 가 막는 것으로 보이나 확증은 없습니다. 멤버일
        수 있다는 정황일 뿐 판정 근거는 아닙니다.
*   **`AccessDenied` 로는 소속을 판단하지 않습니다.** 거부는 호출 주체의 IAM
    정책 · 권한 경계 · 세션 정책 · SCP 어느 것에서도 나올 수 있고, 오류만으로
    이를 가릴 수 없습니다. 관리자 권한 주체에서 나와도 마찬가지입니다.
*   **보는 방법**(둘 중 하나):
    1.  루트 사용자 또는 관리자 권한 주체로 기존 계정 콘솔에 들어가
        AWS Organizations 화면을 엽니다. 화면에 소속 조직이 보이는지, 조직을
        새로 만드는 화면이 나오는지 봅니다. 이 화면도 권한 평가를 받으므로
        거부 화면이면 판정하지 않습니다.
    1.  관리자 권한 주체로 같은 읽기 명령을 부릅니다.

        ```
        aws organizations describe-organization
        ```

*   **판정 규칙**:
    *   조직 정보가 나오면 응답의 관리 계정 ID 와 이 계정 ID 를 비교합니다.
        다르면 「멤버」, 같으면 「이 계정이 관리 계정」입니다.
    *   `AWSOrganizationsNotInUseException` 이면 「멤버 아님」입니다(공식 API
        문서와 일치 — 근거 절).
    *   다른 주체로도 거부가 계속되면 「**판정 불가**」로 기록합니다. 그때는
        그 주체에 붙은 제한 정책(권한 경계 · 세션 정책)을 확인하거나, 상위
        조직이 있다면 그 조직 관리자에게 소속을 확인받습니다.
*   **함께 기록할 것**: O1 조사의 `AccessDenied` 오류 전문(계정 ID 는
    `<ACCOUNT_ID_NONPROD>` 로 가림)과 이번 결과를 나란히 적어 대조합니다.
*   **완료 확인**: 「멤버 아님」 · 「멤버(조직 ID 는 `<ORG_ID_OLD>` 로만
    기록)」 · 「이 계정이 관리 계정(조직 ID 는 `<ORG_ID_OLD>` 로만 기록)」 ·
    「판정 불가」 중 하나로 판정이 기록됨. 「판정 불가」나 「이 계정이 관리
    계정」이면 2-5 로 가지 않고 여기서 멈춥니다.
*   **「이 계정이 관리 계정」일 때**: 관리 계정은 기존 조직의 멤버를 모두
    제거하고 조직을 삭제한 뒤에만 다른 조직으로 옮길 수 있습니다(근거 절).
    기존 조직 삭제가 필요한 전환은 이 런북 범위 밖이며, 별도 kty 결정으로
    정한 뒤에 2-5 로 돌아옵니다.
*   **승인 지점**: 판정 결과 확인. 갈래 선택은 2-5 에서 합니다.
*   **되돌리기**: 읽기만 하므로 없습니다.

### 2-2. 과거 비용 이력 받아 두기

*   **누가**: kty(기존 계정 Billing · Cost Explorer 콘솔).
*   **왜**: 계정이 조직에 들어가거나 조직을 옮기면 그 전 비용 이력의 일부
    (특히 Cost Explorer 조회)에 접근하지 못할 수 있습니다(AWS 전환 가이드 —
    볼트 Knowledge 6절). 무엇이 남고 무엇을 잃는지는 확인하지 못했으므로
    (「확인하지 못한 것」), 잃는다고 보고 전환 전에 받아 둡니다.
*   **주의**: CUR(비용 · 사용 보고서)이나 Data Exports 는 만든 시점부터
    쌓이므로, 지금 새로 만들면 과거 달이 비어 있을 수 있습니다. 「내보내기를
    만들었다」로 끝내지 않고 과거 이력을 **실제로 받은 파일**로 확인합니다.
*   **무엇을**: 보관할 과거 기간(N개월)을 kty 가 정하고, 아래 중 하나 이상으로
    받습니다.
    *   Cost Explorer 화면의 CSV 내려받기(Cost Explorer 보관 기간 안의 달).
    *   월별 청구서 PDF.
    *   CUR · Data Exports 의 과거분 채우기(백필)를 AWS Support 에 요청.
*   **어느 갈래든 먼저**: 2-5 의 어느 갈래(비소속 초대 · 직접 이전 · 독립
    전환)로 가든 이 걸음이 먼저 끝나야 합니다.
*   **완료 확인**: 받은 파일이 기존 계정 밖(kty 보관 위치)에 있고, 다음 둘을
    모두 덮는지 kty 가 파일을 열어 확인.
    *   kty 가 고른 과거 기간 **전체**(첫 달부터 지난달까지).
    *   **당월**: 2-5 의 전환(초대 수락 · 직접 이전 · 탈퇴) 직전까지 조회
        가능한 당월 자료.
*   **전환 직전 갱신**: 받은 뒤 2-5 의 전환까지 시간이 지났으면, 전환 직전에
    당월 자료(달이 바뀌었으면 지난달 포함)를 다시 받습니다.
*   **승인 지점**: 다음 걸음으로 넘어가도 되는지.
*   **되돌리기**: 필요 없습니다.

### 2-3. 새 관리 계정 만들기

*   **누가**: kty(콘솔). 새 이메일 `<EMAIL_MGMT>` 로 새 계정을 만듭니다.
*   **왜**: 기존 계정으로 조직을 만들지 않습니다 — AWS 전환 가이드는 새 계정으로
    시작하라고 하고, CT 는 워크로드 없는 계정만 관리 계정으로 허용합니다
    (볼트 Knowledge 2절).
*   **완료 확인**: 새 계정 로그인 · 워크로드 0.
*   **승인 지점**: 계정 생성.
*   **되돌리기**: 계정 해지. 해지 조건은 이 런북에서 확인하지 않았습니다.

### 2-4. 조직 만들기(콘솔)

*   **누가**: kty(새 관리 계정 콘솔).
*   **왜 콘솔인가**: 2026-07-10 이후 **콘솔로** 만든 조직에만 기본 SCP
    (`DenyLeaveAndCloseAccount`)가 자동으로 붙습니다. CLI · SDK 로 만들면
    손으로 붙여야 합니다(볼트 Knowledge 2절).
*   **알 것**: 기본 SCP 는 멤버 계정의 조직 탈퇴(`LeaveOrganization`)를
    거부합니다. 그래서 이 조직에 들어온 계정을 되돌릴 때는 멤버 쪽 탈퇴가
    아니라 관리 계정에서의 제거를 씁니다(2-5 되돌리기).
*   **관리 계정 이메일 검증**(kty): 기존 계정을 조직에 초대하려면 먼저 관리
    계정 이메일의 소유를 검증해야 합니다. 검증 메일은 24시간 안에 처리해야
    하고, 지나면 검증 메일을 다시 보내 처리합니다(근거 절).
    *   이미 검증된 계정이면 검증 상태만 확인하고 끝냅니다. 검증 상태를 보는
        화면 위치는 착수 때 화면에서 확인합니다(「확인하지 못한 것」).
    *   검증 메일이 만료됐으면 다시 보낸 뒤 24시간 안에 처리합니다.
    *   검증 없이 초대하면 실패하며, 오류 이름은
        `AccountOwnerNotVerifiedException` 이라고 리뷰어가 제시했습니다(리뷰어
        제시 · 미대조).
*   **완료 확인**: 관리 계정의 Organizations 화면에 조직 ID 와 관리 계정
    `<ACCOUNT_ID_MGMT>` 가 보이고, 정책 화면에 기본 SCP 가 붙어 있고, 관리
    계정 이메일의 검증 완료가 확인됨. 읽기 명령(관리 계정에서):

    ```
    aws organizations describe-organization
    aws organizations list-policies --filter SERVICE_CONTROL_POLICY
    ```
*   **일정 메모**: 2-5 의 나(직접 이전)는 새 조직을 만든 지 7일이 지나야
    합니다(2-5). 2-1 판정이 「멤버」이면 2-4 를 일찍 끝내 두고, 7일 뒤에
    2-5 나를 진행합니다. 조직을 만든 날짜를 기록해 둡니다. 7일을 기다리는
    동안 검증 메일의 24시간 기한이 지나므로, 검증은 메일을 받은 날 끝내거나
    2-5 직전에 다시 보내 처리합니다.
*   **승인 지점**: 조직 생성 · 이메일 검증.
*   **되돌리기**: 조직 삭제. 멤버 계정을 모두 정리(2-5 되돌리기)한 뒤의 별도
    절차이며 이 런북 범위 밖입니다. 관리 계정의 탈퇴로는 되돌릴 수 없습니다.

### 2-5. 기존 계정을 조직에 들이기

*   **누가**: kty — 관리 계정에서 초대, 기존 계정에서 수락.
*   **선행**: 2-1 판정이 「멤버 아님」 또는 「멤버」이고, 2-2 가 끝나고, 2-4
    의 관리 계정 이메일 검증 완료가 확인됨(초대 발송 직전에 다시 봅니다).
*   **이전 조직 CT 흔적 확인**(「멤버」일 때, 초대 수락 전에 — 기존 계정에서
    읽기): `AWSControlTowerExecution` · 이름이 `aws-controltower-` 로 시작하는
    역할 같은 이전 조직 CT 관리 흔적이 있는지 봅니다.

    ```
    aws iam get-role --role-name AWSControlTowerExecution
    aws iam list-roles --query "Roles[].RoleName"
    ```
*   **흔적이 있으면 — 수락 전 선행 조건**: kty 가 원본 조직에서 이 계정의 CT
    등록 해제를 **끝내고 상태를 확인**한 뒤에만 초대를 수락합니다. 등록 해제
    전후로 루트 사용자 또는 이 계정 자체의 관리자 주체로 들어갈 수 있는지
    확인합니다(원본 조직의 역할에 기대지 않는 접근). 새 조직용 역할은 2-6 에서
    준비합니다. 등록 해제가 정확히 무엇을 지우고 남기는지는 착수 때 원문을
    확인합니다(「확인하지 못한 것」).
*   **원본 조직 관리자가 협조하지 않으면**(등록 해제 · 정책 확인 등): 여기서
    멈추고 kty 에게 되묻습니다. 2-1 의 「판정 불가」와 같이 처리합니다.
*   **갈래**(2-1 판정에 따라 이 걸음에서 kty 가 고릅니다 — 승인 지점):

    | 갈래 | 언제 | 어떻게 |
    |---|---|---|
    | 가. 비소속 초대 | 「멤버 아님」 | 관리 계정에서 초대 → 기존 계정에서 수락 |
    | 나. 직접 이전 | 「멤버」 | 2025-11-19 부터 다른 조직의 멤버도 먼저 탈퇴하지 않고 새 조직의 초대를 받아 수락할 수 있습니다(근거 절). 초대 · 수락 방식은 가와 같습니다 |
    | 다. 독립 전환 | 「멤버」이고 나를 쓰지 않을 때 | 기존 조직에서 나와 독립 계정이 된 뒤 가로 갑니다 |

    *   다(독립 전환)의 사전 조건(탈퇴 권한 · 탈퇴 조건)은 확인하지
        못했습니다 — 고르기 전에 확인합니다(「확인하지 못한 것」).
*   **나(직접 이전)를 고르면 수락 전에 모두 확인할 것**(AWS 이전 사전 조건 —
    근거 절):
    *   새 조직(2-4)을 만든 지 **7일 이상** 지났습니다.
    *   기존 계정이 기존 조직 안에서 **만들어진** 계정이면, 만든 지 4일 이상
        지났습니다(초대로 들어간 계정은 해당 없음).
    *   기존 계정과 새 조직의 Seller of Record(판매 주체)가 같습니다.
    *   기존 계정이 어떤 서비스의 위임 관리자로도 지정돼 있지 않습니다.
    *   기존 조직 쪽 IAM 정책 · SCP 가 이전을 막지 않습니다.
    *   기존 조직의 관리 계정을 신뢰하는 옛 `OrganizationAccountAccessRole`
        을 지웠습니다(새 역할은 2-6). 지우기 전에 신뢰 정책을 기록하고, 이
        역할에 기대지 않는 접근(루트 사용자 또는 이 계정 자체의 관리자
        주체)으로 들어갈 수 있는지 확인합니다. 이 확인은 이전 조직 CT 흔적이
        있든 없든 합니다.
    *   2-2 의 비용 보고서 백업이 끝났습니다(전환 직전 갱신 포함).
    *   기존 계정이 기존 조직의 관리 계정이 아닙니다(관리 계정이면 2-1 에서
        멈춤).
*   **완료 확인**: 관리 계정의 계정 목록에 `<ACCOUNT_ID_NONPROD>` 가 활성으로
    보임. 읽기 명령(관리 계정에서):

    ```
    aws organizations list-accounts
    ```
*   **승인 지점**: 원본 조직 등록 해제 확인(흔적이 있을 때) · 갈래 선택 ·
    초대 발송 · 수락.
*   **되돌리기**: CT 등록 해제(등록한 뒤라면) → kty 가 **관리 계정 콘솔에서**
    기존 계정을 조직에서 제거. 2-4 의 기본 SCP 가 멤버 쪽 탈퇴를 막으므로
    기존 계정에서 탈퇴하지 않습니다. 제거 전에 확인할 것:
    *   기존 계정이 독립 계정으로 운영되는 데 필요한 정보를 갖췄는지(제거 조건
        문서 — 근거 절).
    *   기존 계정이 어떤 서비스의 위임 관리자로 지정돼 있지 않은지.
*   **나(직접 이전)로 들어온 뒤 되돌리면**: 제거된 계정은 옛 조직으로
    돌아가지 않고 독립 계정이 됩니다.

### 2-6. 기존 계정의 역할 두 개 준비

*   **누가**: kty(기존 계정 IAM 콘솔). 확인은 오케스트레이터 읽기.
*   **먼저 볼 것**(기존 계정에서, 읽기):
    *   `OrganizationAccountAccessRole` 이 이미 있는지, 있으면 누구를
        신뢰하는지. 조직을 떠나도 이 역할은 자동으로 지워지지 않으므로, 이전
        조직의 관리 계정을 신뢰하는 역할이 남아 있을 수 있습니다.
    *   이전 조직 CT 관리 흔적이 2-5 의 원본 조직 등록 해제 뒤에도 남았는지
        (흔적 확인 자체는 2-5 에서 수락 전에 합니다).

    ```
    aws iam get-role --role-name OrganizationAccountAccessRole
    aws iam get-role --role-name AWSControlTowerExecution
    aws iam list-roles --query "Roles[].RoleName"
    ```
*   **갈래**(먼저 본 결과로 이 걸음에서 kty 가 고릅니다 — 승인 지점):
    *   이전 조직 CT 흔적이 남았으면 여기서 멈추고 kty 에게 되묻습니다.
    *   기존 역할이 있으면 옛 신뢰를 지우고 신뢰를 새 관리 계정으로 바꿀지,
        역할을 지우고 새로 만들지 정합니다. 어느 쪽이든 손대기 전에 이
        역할에 기대지 않는 접근(루트 사용자 또는 이 계정 자체의 관리자
        주체)을 확인합니다(이전 조직 CT 흔적 유무와 무관).
    *   없으면 새로 만듭니다.
    *   2-5 에서 나(직접 이전)를 골랐다면 옛 `OrganizationAccountAccessRole`
        은 2-5 에서 이미 지웠으므로, 위의 「옛 신뢰를 바꿀지 · 지우고 새로
        만들지」 선택은 해당 없습니다. 새로 만듭니다.
*   **무엇을**(최종 상태):
    *   `OrganizationAccountAccessRole` — 초대한 계정에는 자동으로 생기지
        않습니다(Organizations 초대 문서, 설계서 2-2 RC-35). 신뢰 대상은 새
        관리 계정 `<ACCOUNT_ID_MGMT>` 이고, 관리자 권한 정책을 붙입니다.
        정책 이름(리뷰어 제시는 `AdministratorAccess`)과 만드는 절차는 착수
        때 원문을 확인합니다(「확인하지 못한 것」).
    *   `AWSControlTowerExecution` — 초대한 계정을 CT 에 등록하려면
        필요합니다. CT 문서의 예시 템플릿 그대로 만듭니다: 신뢰 대상은 새 관리
        계정 `<ACCOUNT_ID_MGMT>`, 권한은 `AdministratorAccess`(설계서 2-3 의 4).
*   **완료 확인**: 다음을 모두 만족함.
    *   두 역할 모두 신뢰 정책의 주체가 `<ACCOUNT_ID_MGMT>` 뿐임.
    *   `AWSControlTowerExecution` 에 `AdministratorAccess` 가 붙어 있음.
    *   `OrganizationAccountAccessRole` 에 관리자 권한 정책이 붙어 있음.
    *   관리 계정에서 이 역할을 맡을 호출자(`<CALLER_ARN>`)의 정책이
        `sts:AssumeRole` 을 허용함.

    읽기 명령(마지막 줄만 관리 계정에서, 나머지는 기존 계정에서):

    ```
    aws iam get-role --role-name OrganizationAccountAccessRole
    aws iam get-role --role-name AWSControlTowerExecution
    aws iam list-attached-role-policies --role-name AWSControlTowerExecution
    aws iam list-attached-role-policies --role-name OrganizationAccountAccessRole
    aws iam simulate-principal-policy --policy-source-arn <CALLER_ARN> --action-names sts:AssumeRole --resource-arns arn:aws:iam::<ACCOUNT_ID_NONPROD>:role/OrganizationAccountAccessRole
    ```

    `simulate-principal-policy` 는 호출자 쪽 정책만 평가하고 역할의 신뢰
    정책은 평가하지 않는다고 리뷰어가 제시했습니다(원문 미대조). 통과해도
    위임 성공의 보장이 아니며, 신뢰 정책은 첫 항목(`get-role` 출력)으로 따로
    봅니다.
*   **승인 지점**: 갈래 선택 · 역할 생성 또는 변경.
*   **되돌리기**: 역할 삭제(또는 바꾸기 전 신뢰 정책으로 복원 — 바꾸기 전에
    원래 신뢰 정책을 기록해 둡니다). `AWSControlTowerExecution` 은 등록 뒤 CT
    가 관리하므로, 등록 뒤에는 지우지 않습니다. 두 역할 모두 Terraform 이
    소유하지 않습니다(설계서 2-2).

### 2-7. 기존 계정의 Config · CloudTrail 정리

*   **누가**: kty(기존 계정 콘솔 · CloudShell). 확인은 오케스트레이터 읽기.
*   **왜**: CT 등록 대상 계정에는 기존 Config 자원이 없어야 하고, 기존
    CloudTrail 트레일은 CT 트레일과 중복 과금됩니다(설계서 2-3 의 3).
*   **레코더는 두 종류입니다**: 고객 관리 레코더(계정이 직접 만든 것)와
    서비스 연결 레코더(다른 AWS 서비스가 만든 것)입니다.
*   **콘솔로 끝나지 않는 것**: 고객 관리 Config 레코더는 콘솔에서 지울 수
    없고 AWS CLI 로 지워야 합니다. 서비스 연결 레코더는 콘솔에서 지울 수
    있습니다(근거 절). 리뷰어는 전달 채널도 콘솔에서 지울 수 없다고
    제시했으나 원문과 대조하지 않았습니다 — 착수 때 원문을 확인합니다
    (「확인하지 못한 것」).
*   **레코더 전체 목록 보기**(거버넌스 리전마다, 읽기): 두 종류를 **각각**
    확인합니다.
    *   `describe-configuration-recorders` 를 레코더 지정 없이 부르면 고객
        관리 레코더만 돌려준다는 서술(CLI · API 명세)과 전체를 돌려준다는
        서술(개발자 가이드)이 엇갈린다고 리뷰어가 제시했습니다. 원문과
        대조하지 않았으므로, 이 명령 하나의 결과로 「레코더 0건」을 판정하지
        않습니다.
    *   전체 목록을 보는 수단으로 리뷰어는 `list-configuration-recorders`
        명령과 콘솔의 두 레코더 탭(고객 관리 · 서비스 연결)을 제시했습니다.
        어느 수단이 두 종류를 모두 보여 주는지는 착수 때 원문으로 확인한 뒤
        고릅니다(「확인하지 못한 것」).
    *   두 종류를 모두 보여 주는 수단을 확인하지 못하면 여기서 멈추고 kty
        에게 되묻습니다.
*   **레코더에 기대는 서비스 확인**(착수 때, 지우기 전에): Security Hub 처럼
    Config 레코더에 기대는 서비스를 기존 계정에서 쓰는지 확인하고, 지웠을 때
    그 서비스에 생기는 영향을 kty 에게 알린 뒤 지웁니다. 어떤 서비스가
    기대는지는 원문과 대조하지 않았습니다(「확인하지 못한 것」).
*   **서비스 연결 레코더가 있으면**: 지우지 않고 여기서 멈춥니다. 그 레코더를
    만든 연결 서비스, 그 서비스에서 먼저 할 조치, CT 등록을 위해 지워야
    하는지를 확인한 뒤 kty 가 정합니다. 리뷰어는 연결 서비스가 사용 중이면
    삭제가 거부된다고 제시했습니다(원문 미대조). 이 셋 중 하나라도 확인하지
    못하면 진행하지 않습니다.
*   **고객 관리 레코더 삭제 순서**(고객 관리 레코더가 있을 때, 거버넌스
    리전마다 — **kty 가 기존 계정 CloudShell 에서 직접 실행**,
    오케스트레이터는 실행하지 않습니다):
    1.  설정 보관: 아래 「완료 확인」의 읽기 명령 둘(레코더 · 전달 채널)과
        레코더 전체 목록의 출력을 기존 계정 밖(kty 보관 위치)에 저장합니다.
    1.  레코더 중지.
    1.  전달 채널 삭제.
    1.  레코더 삭제.
    1.  읽기 재확인: 「완료 확인」의 읽기와 레코더 전체 목록으로 두 종류를
        다시 봅니다.

    명령 예시(자리표시자만 — 실제 이름은 1번의 보관 출력에서 읽습니다):

    ```
    aws configservice stop-configuration-recorder --configuration-recorder-name <RECORDER_NAME> --region <REGION>
    aws configservice delete-delivery-channel --delivery-channel-name <DELIVERY_CHANNEL_NAME> --region <REGION>
    aws configservice delete-configuration-recorder --configuration-recorder-name <RECORDER_NAME> --region <REGION>
    ```
*   **레코더 없이 전달 채널만 남았으면**: 레코더 삭제 순서의 1번(설정
    보관)을 한 뒤 전달 채널만 지우고, 5번(읽기 재확인)으로 레코더 0건 ·
    전달 채널 0건을 다시 봅니다. 지우는 수단은 위 순서와 같습니다(콘솔
    삭제 가능 여부는 「확인하지 못한 것」).
*   **트레일 정리 — 기본은 남김**(트레일이 있을 때, 거버넌스 리전마다):
    기존 트레일은 지우지 않고 남깁니다. CT 트레일과 중복 과금되지만 기존
    기록 범위가 끊기지 않습니다. 트레일마다 설정(`describe-trails` 출력)과
    로그를 쌓는 S3 버킷 · 접두사 위치를 기존 계정 밖(kty 보관 위치)에
    기록합니다. 지우는 것은 kty 가 지우기로 정했을 때만이며, 아래 「트레일을
    지울 때」를 따릅니다.
*   **트레일을 지울 때**(kty 결정일 때만):
    1.  보관: 트레일마다 다음을 기존 계정 밖(kty 보관 위치)에 저장합니다.
        *   `describe-trails` 출력과 로그 S3 버킷 · 접두사 위치.
        *   `get-event-selectors` 출력 — 데이터 이벤트 등 실제 기록 범위.
            `describe-trails` 는 선택기가 있는지만 알려 준다고 리뷰어가
            제시했습니다(리뷰어 제시 · 미대조, 착수 때 원문 확인).
        *   Insights 를 쓰면 그 설정(보는 수단은 착수 때 원문 확인).
    1.  비교: 보관한 기존 기록 범위와 CT 트레일이 대체하는 범위를 나란히
        적습니다. CT 트레일이 대체하지 않는 범위(데이터 이벤트 등)는 지우면
        CT 기록이 시작된 뒤에도 계속 빕니다.
    1.  kty 결정(승인 지점): 비교를 보고 지울지 정합니다. 지우지 않으면 위
        「기본은 남김」으로 돌아갑니다.
    1.  지울 수 있는지 확인: 리뷰어는 다중 리전 트레일은 홈 리전에서만 지울
        수 있고, 이전 조직의 조직 트레일은 멤버 계정이 지울 수 없을 수
        있다고 제시했습니다(리뷰어 제시 · 미대조). 지울 수 없으면 여기서
        멈추고 kty 에게 되묻습니다.
    1.  삭제: kty 가 콘솔에서 지웁니다. 시점은 아래 「삭제 시점」을 따릅니다.

    보관 명령 예시(자리표시자만 — 실제 이름은 `describe-trails` 출력에서
    읽습니다):

    ```
    aws cloudtrail get-event-selectors --trail-name <TRAIL_NAME> --region <REGION>
    ```
*   **삭제 시점**(Config 삭제와 트레일 삭제 공통, kty 선택 — 승인 지점):
    *   2-7 에서 바로 지웁니다(기본).
    *   또는 공백을 줄이려고 삭제만 3-5 착수 직전(1번 OU 이동 전)으로
        미룹니다. 보관 · kty 결정 · 지울 수 있는지 확인은 2-7 에서 끝내고,
        삭제와 삭제 뒤 읽기 재확인 · 완료 확인을 3-5 직전에 합니다. 3-5 의
        선행(2-7 의 Config 0)은 그대로이므로, 미뤄도 3-5 의 1번(OU 이동)은
        2-7 완료 확인 뒤에만 합니다. 3-1 부터 3-4 까지가 이 계정의 Config
        자원 유무에 영향을 받지 않는지는 착수 때 확인합니다(「확인하지 못한
        것」).
*   **완료 확인**: 거버넌스 리전(서울 · `us-east-1`)마다 다음을 모두 기록함.
    *   레코더: 고객 관리 · 서비스 연결 **두 종류를 각각** 확인한 결과. 고객
        관리 레코더 0건, 서비스 연결 레코더는 0건이거나 kty 가 남기기로
        정한 것만 남음. 종류별로 둘 다 확인하기 전에는 완료로 보지 않습니다.
    *   전달 채널 0건.
    *   트레일 정리 결과(트레일마다 남김 · 지움)와 기록한 설정 · S3 위치의
        보관 위치. 지웠으면 선택기 · Insights 설정의 보관 위치와 기록 범위
        비교 · kty 결정도 함께 적습니다.
    *   레코더에 기대는 서비스 확인 결과.

    읽기 명령(기존 계정에서, 리전마다). 레코더는 이 명령에 더해 위 「레코더
    전체 목록 보기」에서 고른 수단으로 봅니다:

    ```
    aws configservice describe-configuration-recorders --region <REGION>
    aws configservice describe-delivery-channels --region <REGION>
    aws cloudtrail describe-trails --region <REGION>
    ```
*   **승인 지점**: 지우기 전에 목록을 보고 kty 가 정합니다(트레일 삭제
    여부 · 삭제 시점 포함). CloudShell 의 삭제 명령도 kty 가 실행합니다.
*   **되돌리기**: 보관한 설정으로 지운 설정(레코더 · 전달 채널 · 트레일)을
    다시 만듭니다. 트레일은 보관한 이벤트 선택기(쓰면 Insights 설정까지)를
    복원하고 기록이 다시 시작됐는지 확인해야 되돌리기 완료입니다. 복원
    수단은 착수 때 원문을 확인합니다. 지운 동안의 기록 공백은 되돌릴 수
    없습니다.

### 2-8. 서울 · us-east-1 밖 자원 조사

*   **누가**: 오케스트레이터 읽기(kty 승인 뒤) 또는 kty.
*   **왜**: CT 리전 거부를 켜면(3-1) 거버넌스 리전 밖의 기존 자원은 접근을
    잃습니다(볼트 Knowledge 3절).
*   **무엇을**: 기존 계정에서 두 리전 밖에 자원이 있는지 읽기 전용으로
    조사합니다. 조사 수단은 설계서에 정해져 있지 않습니다 — 착수 때 정하고,
    수단이 놓치는 자원 종류를 함께 기록합니다.
*   **조사 대상에 꼭 넣을 것**: 두 리전 밖의 Config 레코더 · 전달 채널(2-7
    은 거버넌스 리전만 정리합니다). 레코더는 2-7 처럼 고객 관리 · 서비스 연결
    두 종류를 각각 봅니다.
*   **완료 확인**: 리전별 결과 기록. 자원이 있으면 옮길지 지울지 kty 가
    정하고, 그 처분이 끝난 뒤 3단계로 갑니다.
*   **승인 지점**: 읽기 실행 · 발견 자원 처분.
*   **되돌리기**: 읽기만 하므로 없습니다.

## 3단계 — CT(O3)

선행: 2단계 전부(2-8 의 발견 자원 처분 포함). 예외는 2-7 에서 kty 가
삭제 시점을 3-5 직전으로 미룬 경우의 삭제와 그 뒤 확인뿐이며, 이것은 3-5
착수 직전에 끝냅니다.

### 3-1. 랜딩 존 설정

*   **누가**: kty(관리 계정 CT 콘솔).
*   **무엇을**:
    *   LZ 판 4.0 · 홈 리전 서울 · 거버넌스 리전 서울 + `us-east-1`.
    *   리전 거부(Region deny)를 **Enabled** 로 고릅니다(정해진 것 표). 기본값은
        Not enabled 이므로 직접 골라야 합니다(근거 절).
    *   통합: Logging · SecurityRoles · Config · IdC 를 켭니다. 설계 조사(15_rc)
        기준으로 IdC 통합은 SecurityRoles 통합에, SecurityRoles 통합은 Config
        통합에 기댑니다(LZ 4.0 변경 문서). 실제 의존은 착수 때 화면에서
        확인합니다.
    *   Log Archive · Audit 계정을 **새로** 만듭니다(이메일
        `<EMAIL_LOG_ARCHIVE>` · `<EMAIL_AUDIT>`). 두 계정은 Security OU
        아래에 둡니다.
*   **화면에서 확인하고 kty 가 고를 것**(승인 지점):
    *   Audit 계정이 SecurityRoles · Config 통합을 겸할 수 있는지. 화면이
        허용하지 않으면 **여기서 멈추고** kty 에게 되묻습니다. 통합 계정
        지정은 되돌릴 수 없으므로 임의로 계정을 하나 더 만들지 않습니다.
    *   로그 보존 기간.
    *   KMS 키로 암호화할지, 한다면 어느 키를 쓸지. KMS 를 고르면 키 조건과
        정책 준비를 kty 가 착수 때 확인합니다 — 관리 계정에 있는 활성 · 대칭 ·
        단일 리전 키인지, 키 정책이 Config · CloudTrail 의 사용을 허용하는지
        (리뷰어 제시 · 미대조, 「확인하지 못한 것」).
*   **되돌릴 수 없는 것**: 홈 리전, 통합 계정 지정(LZ 최초 설정 때만 가능),
    관리 계정 선택.
*   **완료 확인**: CT 대시보드의 LZ 상태가 사용 가능이고, LZ 설정 화면에서
    리전 거부가 활성. 읽기 명령(관리 계정에서):

    ```
    aws controltower list-landing-zones
    aws controltower get-landing-zone --landing-zone-identifier <LZ_ARN>
    ```
*   **승인 지점**: LZ 설정(설계서 8절 3단계).
*   **되돌리기**: LZ 해제(설계서 9-1). 통합 계정 · 로그 자원은 남습니다.
    관리 계정은 되돌릴 수 없는 선택입니다.

### 3-2. OU 만들기 · 등록

*   **누가**: kty(CT 콘솔).
*   **착수 전 준비**(3-2 · 3-4 · 3-5 공통):
    *   CT 작업을 할 **비루트** 관리자 주체를 정합니다. 관리 계정의 루트
        사용자로는 계정 생성 · 등록이 실패합니다.
    *   그 주체에 Service Catalog 권한이 있고, Account Factory Portfolio 에
        접근할 수 있는지 확인합니다.
    *   이 준비는 Terraform 실행 주체의 IdC 전환(다음 단계 절)과 별개입니다.
*   **무엇을**: Workloads OU 와 그 아래 Prod OU · NonProd OU 를 만들어 CT 에
    등록합니다. OU 를 등록하면 `AWSControlTowerBaseline` 이 켜지고, 이
    베이스라인이 그 OU 멤버 계정의 Config 를 포함합니다.
*   **하지 말 것**: 별도 Config 베이스라인(`ConfigBaseline`)을 켜지 않습니다.
    `AWSControlTowerBaseline` 과 같은 OU 에 함께 켤 수 없습니다(근거 절).
*   **완료 확인**: CT 조직 화면에서 각 OU 가 등록됨 · OU 마다
    `AWSControlTowerBaseline` 이 활성으로 표시됨. 읽기 명령(관리 계정에서):

    ```
    aws organizations list-organizational-units-for-parent --parent-id <ROOT_ID>
    aws controltower list-enabled-baselines
    ```
*   **승인 지점**: OU 등록마다.
*   **되돌리기**: OU 등록 해제. 남는 자원은 **미확인**.

### 3-3. Account Factory 의 VPC 생성 끄기

*   **누가**: kty(CT 콘솔 Account Factory 설정).
*   **왜**: Account Factory 로 만든 계정은 기본 VPC 를 지우고 CT VPC 를
    만듭니다. prod 는 Terraform(`prod/network`)의 VPC 를 쓰므로 끕니다
    (설계서 2-3 의 6).
*   **무엇을**(AWS 절차의 두 방법 중 하나 — 근거 절):
    *   인터넷 접근 서브넷을 끄고, 프라이빗 서브넷 수를 0 으로 둔 뒤 저장.
    *   또는 VPC 를 만들 리전을 모두 해제한 뒤 저장.
*   **CIDR 주의**: VPC 없이 계정을 만들 때 기본 CIDR 을 그대로 남기면
    「the CIDR is not valid」 오류로 계정 생성 요청이 실패합니다(근거 절).
    무엇으로 바꾸거나 어떻게 처리해야 하는지는 원문에 없어 확인하지
    못했습니다 — 착수 때 화면과 원문으로 확인하고 kty 가 정합니다
    (「확인하지 못한 것」).
*   **완료 확인**: Account Factory 설정 화면에서 고른 방법의 값(서브넷 끔 ·
    프라이빗 서브넷 0, 또는 선택 리전 없음)이 저장돼 있고, CIDR 칸을 어떻게
    처리했는지(바꾼 경우 그 값)가 기록돼 있음. 토글 · 리전 선택만으로는
    완료로 보지 않습니다.
*   **승인 지점**: 설정 변경. **3-4 전에 끝나야 합니다.**
*   **되돌리기**: 바꾸기 전 값으로 되돌려 저장합니다(바꾸기 전 값을
    기록해 둡니다).

### 3-4. prod 계정 만들기(Account Factory)

*   **누가**: kty(CT 콘솔, 3-2 의 비루트 주체로).
*   **선행**: 3-3 완료(CIDR 처리 기록 포함).
*   **무엇을**: 새 이메일 `<EMAIL_PROD>` 로 prod 계정을 Prod OU 에 만듭니다.
*   **멈출 때**: 계정 생성이 CIDR 오류로 실패하면 여기서 멈추고 kty 에게
    되묻습니다.
*   **착수 때 확인**: Account Factory 화면이 요구하는 필수 입력(IdC 사용자
    정보 등)을 화면에서 확인하고, 그 값은 kty 가 정합니다(「확인하지 못한
    것」).
*   **완료 확인**: CT 계정 화면에 `<ACCOUNT_ID_PROD>` 가 등록됨 · prod 계정에
    CT VPC 없음.
*   **승인 지점**: 계정 생성(등록).
*   **되돌리기**: 계정 관리 해제 · 해지. 조건은 **미확인**.

### 3-5. 기존 계정을 NonProd OU 에 등록

*   **누가**: kty(CT 콘솔, 3-2 의 비루트 주체로).
*   **선행**: 2-6(`AWSControlTowerExecution`) · 2-7(Config 0) · 3-2(NonProd OU
    등록 · `AWSControlTowerBaseline` 활성). 2-7 의 삭제를 미뤘다면 여기서
    1번(OU 이동) 전에 삭제하고 2-7 완료 확인까지 끝냅니다.
*   **알 것**: 등록하면 CT 가 StackSet 을 배포하고 OU 의 SCP 를 적용하며
    Config 로 모든 자원을 기록합니다. 설계 조사(15_rc) 기준으로 기존 계정을
    등록할 때 CT 는 VPC 를 만들거나 지우지 않습니다(Enroll an existing
    account 문서). 착수 때 원문을 다시 확인하고, 등록 뒤 VPC 가 그대로인지는
    아래 완료 확인에서 봅니다.
*   **지금 계정의 위치**: 리뷰어는 초대로 들어온 계정이 조직 Root 에
    놓인다고 제시했습니다(원문 미대조). 그래서 등록 전에 NonProd OU 로
    옮기는 걸음을 둡니다.
*   **무엇을**:
    1.  kty 가 관리 계정 Organizations 콘솔에서 기존 계정을 NonProd OU 로
        옮깁니다.
    1.  kty 가 CT 콘솔에서 기존 계정을 등록(Enroll)합니다.

    이 순서(OU 로 먼저 옮긴 뒤 등록할지, 등록 화면에서 대상 OU 를 고르면
    되는지)는 확인하지 못했습니다 — 착수 때 원문을 확인하고 kty 가
    정합니다(「확인하지 못한 것」).
*   **완료 확인**: 기존 계정의 부모가 NonProd OU 이고, CT 계정 화면에
    `<ACCOUNT_ID_NONPROD>` 가 등록됨. 등록 뒤에도 기존 VPC · dev 상자 · 옛
    존이 그대로인지 확인합니다. 읽기 명령(관리 계정에서):

    ```
    aws organizations list-parents --child-id <ACCOUNT_ID_NONPROD>
    ```
*   **승인 지점**: OU 이동 · 등록.
*   **되돌리기**: CT 등록 해제(설계서 9-1). 남는 자원은 **미확인**. OU
    이동은 계정을 Root 로 다시 옮깁니다.

## 다음 단계

*   4단계(O4): 관리 계정에 `mgmt/bootstrap` → `mgmt/org-extras` apply(계획
    M3 c2 · c3 · c4). 3단계에서 기록한 계정 ID 를 입력 변수로 씁니다.
*   Terraform 실행 주체는 지금 IAM 사용자이고, IdC 로의 전환은 3단계 뒤에
    합니다(kty 27차 O1 결정 ②).

## 되돌리기 요약

설계서 9-1 을 기본 SCP(2-4)에 맞춰 고쳐 적은 것입니다.

| 대상 | 방법 | 한계 |
|---|---|---|
| 기존 계정 초대 | CT 등록 해제 → kty 가 관리 계정 콘솔에서 멤버 제거(멤버 쪽 탈퇴는 기본 SCP 가 막음) | 제거 전 독립 계정 필수 정보 · 위임 관리자 확인. 나(직접 이전)로 들어왔어도 제거 뒤에는 옛 조직이 아니라 독립 계정. 남는 자원 **미확인** |
| 조직 생성 | 멤버 계정을 모두 정리한 뒤 조직 삭제 — 별도 절차 | 이 런북 범위 밖 |
| CT | LZ 해제 | 통합 계정 · 로그 자원이 남음. 관리 계정은 되돌릴 수 없는 선택 |

## 확인하지 못한 것

*   기존 계정의 현재 조직 소속(2-1 에서 확인 — 설계서 13절).
*   2-1 콘솔 화면의 실제 문구(멤버 · 비소속일 때 각각 무엇이 보이는지).
*   2-4 이메일 검증 없이 초대했을 때의 오류 이름
    (`AccountOwnerNotVerifiedException`) — 리뷰어 제시(초대 API 문서)이며
    원문과 대조하지 않았습니다. 검증 상태를 보는 화면 위치도 착수 때 화면에서
    확인합니다.
*   2-2 과거 이력 수단의 범위: Cost Explorer 보관 기간, CUR · Data Exports
    백필 요청 절차와 가능 기간. AWS 문서 원문과 대조하지 않았습니다.
*   2-2 조직 전환 뒤 남는 비용 자료와 잃는 자료의 구분 — 리뷰어는 Bills ·
    Invoice 는 보존되고 Cost Explorer 접근은 현재 소속에 따르며 이전 조직에
    다시 들어가면 복원된다고 제시했습니다(Leave as member · Cost Explorer
    접근 문서). 원문과 대조하지 않았습니다.
*   2-5 나(직접 이전)의 사전 조건 목록은 원문과 대조했습니다. 남은 것은
    기존 계정이 각 조건을 **충족하는지**(착수 때 확인)와 이전에 필요한 권한의
    세부입니다.
*   2-5 원본 조직 CT 등록 해제의 세부 동작 — 계정에 직접 적용된 베이스라인 ·
    컨트롤만 해제하면 되는지(상속된 예방 컨트롤은 남아도 되는지), 등록 해제가
    `AWSControlTowerExecution` 등 실행 역할을 지우는지, 원본 조직 쪽 절차.
    리뷰어 제시(Account transfer · Unmanage an account 문서)이며 원문과
    대조하지 않았습니다 — 착수 때 원문을 확인합니다.
*   2-5 다(독립 전환)의 탈퇴 조건과 탈퇴 뒤 남는 것(설계서 13절).
*   2-6 `OrganizationAccountAccessRole` 에 붙일 관리형 정책 이름과 수동 생성
    절차 — 리뷰어 제시(역할 생성 문서)이며 원문과 대조하지 않았습니다.
*   2-5 되돌리기의 「독립 계정 운영에 필요한 정보」의 세부 항목.
*   3-1 Audit 계정의 SecurityRoles · Config 겸용 허용 여부(설계서는 AWS
    manifest 예시를 근거로 가능하다고 봄, `15_rc` 는 미확인).
*   3-1 KMS 키 조건(관리 계정의 활성 · 대칭 · 단일 리전 키)과 키 정책의
    Config · CloudTrail 권한 — 리뷰어 제시(KMS 설정 문서)이며 원문과 대조하지
    않았습니다. KMS 를 고를 때 착수 때 확인합니다.
*   3-1 통합 사이의 의존(IdC → SecurityRoles → Config) — 설계 조사(15_rc,
    2026-10-06)의 LZ 4.0 변경 문서 대조이며, 이번 리뷰에서 다시 대조하지
    않았습니다. 착수 때 화면에서 확인합니다.
*   3-5 기존 계정 등록 때 CT 가 VPC 를 만들거나 지우지 않는다는 것 — 설계
    조사(15_rc, 2026-10-06)의 등록 문서 대조이며, 이번 리뷰에서 다시 대조하지
    않았습니다. 착수 때 원문을 확인합니다.
*   계정 해지 조건 · OU 등록 해제 · CT 해제 뒤 남는 자원(설계서 13절).
*   2-7 전달 채널을 콘솔에서 지울 수 있는지 — 리뷰어는 불가로
    제시했습니다(전달 채널 문서). 원문과 대조하지 않았습니다. 콘솔에서
    안 되면 2-7 의 CloudShell 순서로 지웁니다.
*   2-6 `simulate-principal-policy` 의 평가 범위(신뢰 정책을 평가하지
    않는다는 것) — 리뷰어 제시이며 원문과 대조하지 않았습니다.
*   2-7 레코더 전체 목록을 보는 수단 — `describe-configuration-recorders` 를
    레코더 지정 없이 불렀을 때 고객 관리 레코더만 돌려주는지 전체를
    돌려주는지(리뷰어 제시: CLI · API 명세와 개발자 가이드가 엇갈림),
    `list-configuration-recorders` 명령의 존재와 반환 범위, 콘솔 두 레코더
    탭. 원문과 대조하지 않았습니다. 확인 전에는 2-7 을 완료로 보지 않습니다.
*   2-7 서비스 연결 레코더 — 연결 서비스가 사용 중이면 삭제가 거부되는지,
    CT 등록 전에 지워야 하는지(리뷰어 제시, 원문 미대조).
*   2-7 Config 레코더에 기대는 서비스(Security Hub 등)의 목록과 영향.
*   2-7 트레일 — `describe-trails` 가 선택기 유무만 주고 실제 기록 범위는
    `get-event-selectors` 가 주는지, Insights 설정을 보는 수단, 다중 리전
    트레일은 홈 리전에서만 지울 수 있는지, 이전 조직의 조직 트레일을 멤버
    계정이 지울 수 없는지, 지운 트레일의 선택기를 복원하는 수단. 리뷰어
    제시이며 원문과 대조하지 않았습니다.
*   2-7 CT 트레일이 대체하는 기록 범위(기존 트레일과 비교할 기준).
*   2-7 삭제를 3-5 직전으로 미룰 때 3-1 부터 3-4 까지가 이 계정의 Config
    자원 유무에 영향을 받지 않는지.
*   2-8 의 조사 수단.
*   3-5 초대로 들어온 계정이 Root 에 놓이는지, OU 이동과 등록(Enroll)의
    순서 — 리뷰어 제시이며 원문과 대조하지 않았습니다.
*   3-3 기본 CIDR 의 처리 방법(무엇으로 바꾸는지) — 원문은 기본값을 남기면
    실패한다고만 적습니다.
*   3-4 Account Factory 화면의 필수 입력 항목(IdC 사용자 정보 등).

## 근거

*   설계서 「HTTPS · 엣지 인프라 설계서」 2-1 · 2-2 · 2-3 · 5-2 · 8절 · 9-1 ·
    13절.
*   계획서 M3 c1 · 5절 대응표(2 · 3단계 = O2 · O3).
*   볼트 Knowledge `aws-multi-account-landing-zone`(2026-10-06 취득 AWS 1차
    문서 정리) 2 · 3 · 6절.
*   [services-deploy.yml](../../../.github/workflows/services-deploy.yml) 8 ·
    11 · 12행 주석(다른 조직 멤버 기록 · OIDC 거부 정황).
*   [infra/terraform README](../README.md).
*   AWS 문서(2026-10-08 리뷰 1라운드에서 대조):
    *   베이스라인 공존 불가(3-2):
        [Types of baselines](https://docs.aws.amazon.com/controltower/latest/userguide/types-of-baselines.html)
    *   직접 이전 출시 2025-11-19(2-5 나):
        [AWS Organizations direct account transfers](https://aws.amazon.com/about-aws/whats-new/2025/11/aws-organizations-direct-account-transfers/)
    *   `AWSOrganizationsNotInUseException` = 조직에 속하지 않음(2-1):
        [DescribeOrganization](https://docs.aws.amazon.com/organizations/latest/APIReference/API_DescribeOrganization.html)
*   AWS 문서(2026-10-08 리뷰 2라운드에서 대조):
    *   직접 이전 사전 조건 — 대상 조직 생성 후 7일 · 조직 안에서 만든 계정은
        생성 후 4일 · Seller of Record · 위임 관리자 · IAM/SCP · 옛
        `OrganizationAccountAccessRole` 제거 · 보고서 백업 · 관리 계정이면
        멤버 제거와 조직 삭제 뒤(2-1 · 2-5 나):
        [Account migration](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_account_migration.html)
    *   리전 거부 기본값 Not enabled(3-1):
        [Pricing and regions](https://docs.aws.amazon.com/controltower/latest/userguide/pricing-and-regions.html)
*   AWS 문서(2026-10-08 리뷰 3라운드에서 대조):
    *   고객 관리 레코더 삭제는 CLI 필요 · 서비스 연결 레코더는 콘솔 가능
        (2-7):
        [Deleting the configuration recorder](https://docs.aws.amazon.com/config/latest/developerguide/managing-recorder_console-delete.html)
    *   VPC 없이 만들 때 기본 CIDR 을 남기면 「the CIDR is not valid」 로
        실패(3-3 · 3-4, Possible Errors):
        [Configure without a VPC](https://docs.aws.amazon.com/controltower/latest/userguide/configure-without-vpc.html)
*   AWS 문서(2026-10-09 리뷰 5라운드에서 대조):
    *   초대 전 관리 계정 이메일 검증 필요 · 검증 메일 24시간 안 처리 · 지나면
        재발송(2-4 · 2-5):
        [Email address verification](https://docs.aws.amazon.com/organizations/latest/userguide/about-email-verification.html)
*   설계 조사(15_rc, 2026-10-06 대조 — 착수 때 원문 재확인):
    *   통합 사이의 의존 IdC → SecurityRoles → Config(3-1):
        [Key changes in LZ 4.0](https://docs.aws.amazon.com/controltower/latest/userguide/key-changes-lz-v4.html)
    *   기존 계정 등록 때 VPC 를 만들거나 지우지 않음(3-5):
        [Enroll an existing account](https://docs.aws.amazon.com/controltower/latest/userguide/enroll-account.html)
*   AWS 문서(리뷰어 제시 — 각 걸음 착수 때 원문 확인):
    *   검증 없이 초대할 때의 오류 이름(2-4):
        [InviteAccountToOrganization](https://docs.aws.amazon.com/organizations/latest/APIReference/API_InviteAccountToOrganization.html)
    *   레코더 지정 없는 조회의 반환 범위 · 전체 목록 · 서비스 연결 레코더
        삭제 제약(2-7):
        [describe-configuration-recorders CLI](https://docs.aws.amazon.com/cli/latest/reference/configservice/describe-configuration-recorders.html) ·
        [DescribeConfigurationRecorders](https://docs.aws.amazon.com/config/latest/APIReference/API_DescribeConfigurationRecorders.html) ·
        [Viewing the configuration recorder](https://docs.aws.amazon.com/config/latest/developerguide/configuration-recorder-view.html) ·
        [ListConfigurationRecorders](https://docs.aws.amazon.com/config/latest/APIReference/API_ListConfigurationRecorders.html) ·
        [DeleteServiceLinkedConfigurationRecorder](https://docs.aws.amazon.com/config/latest/APIReference/API_DeleteServiceLinkedConfigurationRecorder.html)
    *   트레일 설정 · 이벤트 선택기 보관과 복원(2-7):
        [DescribeTrails](https://docs.aws.amazon.com/awscloudtrail/latest/APIReference/API_DescribeTrails.html) ·
        [GetEventSelectors](https://docs.aws.amazon.com/awscloudtrail/latest/APIReference/API_GetEventSelectors.html) ·
        [PutEventSelectors](https://docs.aws.amazon.com/awscloudtrail/latest/APIReference/API_PutEventSelectors.html)
    *   KMS 키 조건 · 정책(3-1):
        [Configure KMS keys](https://docs.aws.amazon.com/controltower/latest/userguide/configure-kms-keys.html)
    *   전달 채널 콘솔 삭제 불가(2-7):
        [Delivery channel](https://docs.aws.amazon.com/config/latest/developerguide/update-dc-rename.html)
    *   `OrganizationAccountAccessRole` 권한 정책 · 생성 절차(2-6):
        [Create the cross-account role](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_manage_accounts_create-cross-account-role.html)
    *   거부 원인(2-1):
        [IAM troubleshoot access denied](https://docs.aws.amazon.com/IAM/latest/UserGuide/troubleshoot_access-denied.html)
    *   탈퇴 뒤 역할 잔존(2-6) · 비용 자료 보존 구분(2-2):
        [Leave as member](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_manage_accounts_leave-as-member.html)
    *   조직 변경 뒤 Cost Explorer 조회 범위 · 당월 자료(2-2):
        [Cost Explorer access](https://docs.aws.amazon.com/cost-management/latest/userguide/ce-access.html) ·
        [What is Cost Explorer](https://docs.aws.amazon.com/cost-management/latest/userguide/ce-what-is.html)
    *   CT 계정 이전 — 원본 조직 등록 해제 순서(2-5):
        [Account transfer](https://docs.aws.amazon.com/controltower/latest/userguide/account-transfer.html)
    *   등록 해제 동작(2-5):
        [Unmanage an account](https://docs.aws.amazon.com/controltower/latest/userguide/unmanage-account.html)
    *   `AWSControlTowerExecution` 예시(2-6):
        [Enroll an existing account](https://docs.aws.amazon.com/controltower/latest/userguide/enroll-account.html)
    *   OU 등록 권한(3-2):
        [Register an existing OU](https://docs.aws.amazon.com/controltower/latest/userguide/importing-existing.html)
    *   콘솔 계정 생성 조건(3-2 · 3-4):
        [Quick account provisioning](https://docs.aws.amazon.com/controltower/latest/userguide/quick-account-provisioning.html)
    *   VPC 없이 설정 — 두 방법(3-3):
        [Configure without a VPC](https://docs.aws.amazon.com/controltower/latest/userguide/configure-without-vpc.html)
    *   기본 SCP(2-4):
        [Default controls](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_security_default_controls.html)
    *   멤버 계정 제거 조건(2-5 되돌리기):
        [Remove a member account](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_manage_accounts_remove.html)
