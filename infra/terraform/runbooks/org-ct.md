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
| kty(콘솔) | 계정 생성 · 조직 생성 · 초대와 수락 · 역할 수동 생성 · Config · CloudTrail 정리 · CT 콘솔 작업 전부 | — |
| 오케스트레이터 | kty 가 승인한 읽기 확인(아래 「완료 확인」의 읽기 명령)과 결과 기록 | 쓰기 명령 · 콘솔 작업 |
| 에이전트 | 없음 | **계정 생성 · 초대 · CT 콘솔 작업은 에이전트가 하지 않습니다** |

*   모든 단계가 승인 지점입니다(설계서 8절 「각 단계」 · 「각 등록」). 한 단계의
    완료 확인이 끝나기 전에 다음 단계로 가지 않습니다.
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
*   **완료 확인**: 관리 계정의 Organizations 화면에 조직 ID 와 관리 계정
    `<ACCOUNT_ID_MGMT>` 가 보이고, 정책 화면에 기본 SCP 가 붙어 있음. 읽기
    명령(관리 계정에서):

    ```
    aws organizations describe-organization
    aws organizations list-policies --filter SERVICE_CONTROL_POLICY
    ```
*   **승인 지점**: 조직 생성.
*   **되돌리기**: 조직 삭제. 멤버 계정을 모두 정리(2-5 되돌리기)한 뒤의 별도
    절차이며 이 런북 범위 밖입니다. 관리 계정의 탈퇴로는 되돌릴 수 없습니다.

### 2-5. 기존 계정을 조직에 들이기

*   **누가**: kty — 관리 계정에서 초대, 기존 계정에서 수락.
*   **선행**: 2-1 판정이 「멤버 아님」 또는 「멤버」이고, 2-2 가 끝남.
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
        을 지웠습니다(새 역할은 2-6). 지우기 전에 신뢰 정책을 기록합니다.
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
        역할을 지우고 새로 만들지 정합니다.
    *   없으면 새로 만듭니다.
*   **무엇을**(최종 상태):
    *   `OrganizationAccountAccessRole` — 초대한 계정에는 자동으로 생기지
        않습니다(Organizations 초대 문서, 설계서 2-2 RC-35). 신뢰 대상은 새
        관리 계정 `<ACCOUNT_ID_MGMT>` 입니다.
    *   `AWSControlTowerExecution` — 초대한 계정을 CT 에 등록하려면
        필요합니다. CT 문서의 예시 템플릿 그대로 만듭니다: 신뢰 대상은 새 관리
        계정 `<ACCOUNT_ID_MGMT>`, 권한은 `AdministratorAccess`(설계서 2-3 의 4).
*   **완료 확인**: 두 역할 모두 신뢰 정책의 주체가 `<ACCOUNT_ID_MGMT>` 뿐이고,
    `AWSControlTowerExecution` 에 `AdministratorAccess` 가 붙어 있음. 읽기
    명령(기존 계정에서):

    ```
    aws iam get-role --role-name OrganizationAccountAccessRole
    aws iam get-role --role-name AWSControlTowerExecution
    aws iam list-attached-role-policies --role-name AWSControlTowerExecution
    ```
*   **승인 지점**: 갈래 선택 · 역할 생성 또는 변경.
*   **되돌리기**: 역할 삭제(또는 바꾸기 전 신뢰 정책으로 복원 — 바꾸기 전에
    원래 신뢰 정책을 기록해 둡니다). `AWSControlTowerExecution` 은 등록 뒤 CT
    가 관리하므로, 등록 뒤에는 지우지 않습니다. 두 역할 모두 Terraform 이
    소유하지 않습니다(설계서 2-2).

### 2-7. 기존 계정의 Config · CloudTrail 정리

*   **누가**: kty(기존 계정 콘솔). 확인은 오케스트레이터 읽기.
*   **왜**: CT 등록 대상 계정에는 기존 Config 자원이 없어야 하고, 기존
    CloudTrail 트레일은 CT 트레일과 중복 과금됩니다(설계서 2-3 의 3).
*   **완료 확인**: 거버넌스 리전(서울 · `us-east-1`)마다 Config 레코더 ·
    전달 채널 0건, 트레일 정리 결과를 기록. 읽기 명령(기존 계정에서, 리전마다):

    ```
    aws configservice describe-configuration-recorders --region <REGION>
    aws configservice describe-delivery-channels --region <REGION>
    aws cloudtrail describe-trails --region <REGION>
    ```
*   **승인 지점**: 지우기 전에 목록을 보고 kty 가 정합니다.
*   **되돌리기**: 지운 설정을 다시 만듭니다. 지운 동안의 기록 공백은 되돌릴
    수 없습니다.

### 2-8. 서울 · us-east-1 밖 자원 조사

*   **누가**: 오케스트레이터 읽기(kty 승인 뒤) 또는 kty.
*   **왜**: CT 리전 거부를 켜면(3-1) 거버넌스 리전 밖의 기존 자원은 접근을
    잃습니다(볼트 Knowledge 3절).
*   **무엇을**: 기존 계정에서 두 리전 밖에 자원이 있는지 읽기 전용으로
    조사합니다. 조사 수단은 설계서에 정해져 있지 않습니다 — 착수 때 정하고,
    수단이 놓치는 자원 종류를 함께 기록합니다.
*   **완료 확인**: 리전별 결과 기록. 자원이 있으면 옮길지 지울지 kty 가
    정하고, 그 처분이 끝난 뒤 3단계로 갑니다.
*   **승인 지점**: 읽기 실행 · 발견 자원 처분.
*   **되돌리기**: 읽기만 하므로 없습니다.

## 3단계 — CT(O3)

선행: 2단계 전부(2-8 의 발견 자원 처분 포함).

### 3-1. 랜딩 존 설정

*   **누가**: kty(관리 계정 CT 콘솔).
*   **무엇을**:
    *   LZ 판 4.0 · 홈 리전 서울 · 거버넌스 리전 서울 + `us-east-1`.
    *   리전 거부(Region deny)를 **Enabled** 로 고릅니다(정해진 것 표). 기본값은
        Not enabled 이므로 직접 골라야 합니다(근거 절).
    *   통합: Logging · SecurityRoles · Config · IdC 를 켭니다. IdC 통합은
        Config · SecurityRoles 통합에 의존합니다.
    *   Log Archive · Audit 계정을 **새로** 만듭니다(이메일
        `<EMAIL_LOG_ARCHIVE>` · `<EMAIL_AUDIT>`). 두 계정은 Security OU
        아래에 둡니다.
*   **화면에서 확인하고 kty 가 고를 것**(승인 지점):
    *   Audit 계정이 SecurityRoles · Config 통합을 겸할 수 있는지. 화면이
        허용하지 않으면 **여기서 멈추고** kty 에게 되묻습니다. 통합 계정
        지정은 되돌릴 수 없으므로 임의로 계정을 하나 더 만들지 않습니다.
    *   로그 보존 기간.
    *   KMS 키로 암호화할지, 한다면 어느 키를 쓸지.
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
*   **완료 확인**: Account Factory 설정 화면에서 고른 방법의 값(서브넷 끔 ·
    프라이빗 서브넷 0, 또는 선택 리전 없음)이 저장돼 있음.
*   **승인 지점**: 설정 변경. **3-4 전에 끝나야 합니다.**
*   **되돌리기**: 바꾸기 전 값으로 되돌려 저장합니다(바꾸기 전 값을
    기록해 둡니다).

### 3-4. prod 계정 만들기(Account Factory)

*   **누가**: kty(CT 콘솔, 3-2 의 비루트 주체로).
*   **무엇을**: 새 이메일 `<EMAIL_PROD>` 로 prod 계정을 Prod OU 에 만듭니다.
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
    등록 · `AWSControlTowerBaseline` 활성).
*   **알 것**: 등록하면 CT 가 StackSet 을 배포하고 OU 의 SCP 를 적용하며
    Config 로 모든 자원을 기록합니다. 기존 계정의 VPC 는 만들거나 지우지
    않습니다.
*   **완료 확인**: CT 계정 화면에 `<ACCOUNT_ID_NONPROD>` 가 등록됨. 등록 뒤에도
    dev 상자 · 옛 존이 그대로인지 확인합니다.
*   **승인 지점**: 등록.
*   **되돌리기**: CT 등록 해제(설계서 9-1). 남는 자원은 **미확인**.

## 다음 단계

*   4단계(O4): 관리 계정에 `mgmt/bootstrap` → `mgmt/org-extras` apply(계획
    M3 c2 · c3 · c4). 3단계에서 기록한 계정 ID 를 입력 변수로 씁니다.
*   Terraform 실행 주체는 지금 IAM 사용자이고, IdC 로의 전환은 3단계 뒤에
    합니다(kty 27차 O1 결정 ②).

## 되돌리기 요약

설계서 9-1 을 기본 SCP(2-4)에 맞춰 고쳐 적은 것입니다.

| 대상 | 방법 | 한계 |
|---|---|---|
| 기존 계정 초대 | CT 등록 해제 → kty 가 관리 계정 콘솔에서 멤버 제거(멤버 쪽 탈퇴는 기본 SCP 가 막음) | 제거 전 독립 계정 필수 정보 · 위임 관리자 확인. 남는 자원 **미확인** |
| 조직 생성 | 멤버 계정을 모두 정리한 뒤 조직 삭제 — 별도 절차 | 이 런북 범위 밖 |
| CT | LZ 해제 | 통합 계정 · 로그 자원이 남음. 관리 계정은 되돌릴 수 없는 선택 |

## 확인하지 못한 것

*   기존 계정의 현재 조직 소속(2-1 에서 확인 — 설계서 13절).
*   2-1 콘솔 화면의 실제 문구(멤버 · 비소속일 때 각각 무엇이 보이는지).
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
*   2-5 되돌리기의 「독립 계정 운영에 필요한 정보」의 세부 항목.
*   3-1 Audit 계정의 SecurityRoles · Config 겸용 허용 여부(설계서는 AWS
    manifest 예시를 근거로 가능하다고 봄, `15_rc` 는 미확인).
*   계정 해지 조건 · OU 등록 해제 · CT 해제 뒤 남는 자원(설계서 13절).
*   2-8 의 조사 수단.
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
*   AWS 문서(리뷰어 제시 — 각 걸음 착수 때 원문 확인):
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
    *   VPC 없이 설정(3-3):
        [Configure without a VPC](https://docs.aws.amazon.com/controltower/latest/userguide/configure-without-vpc.html)
    *   기본 SCP(2-4):
        [Default controls](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_security_default_controls.html)
    *   멤버 계정 제거 조건(2-5 되돌리기):
        [Remove a member account](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_manage_accounts_remove.html)
