---
name: tdd-workflow
description: 새 기능 추가 시 TDD(Test-Driven Development) 절차. 테스트 먼저 작성 → 실패 확인 → 최소 구현 → 통과 확인 → 리팩터링.
---

# TDD 워크플로우

## 원칙

```
RED   → 실패하는 테스트를 먼저 작성
GREEN → 테스트를 통과하는 최소한의 코드 구현
REFACTOR → 중복 제거, 구조 개선 (테스트는 계속 통과)
```

## Step 1. 테스트 파일 생성

```
{모듈}/src/test/java/com/riman/automation/{모듈}/
└── {테스트 대상과 동일한 패키지 구조}
    └── {클래스}Test.java
```

## Step 2. 테스트 작성 (RED)

```java
@Test
void 기대하는_동작을_설명하는_이름() {
    // Arrange — 테스트 데이터 준비
    var input = ...;

    // Act — 테스트 대상 실행
    var result = service.method(input);

    // Assert — 결과 검증
    assertEquals(expected, result);
}
```

JUnit 5 + Mockito 사용:
- 순수 로직: 직접 테스트
- 외부 의존성 (S3, DynamoDB, Slack API): Mockito로 mock

## Step 3. 테스트 실행 → 실패 확인

```bash
./gradlew :모듈:test --tests "*{클래스}Test" --no-daemon
```

**반드시 실패해야 함.** 통과하면 테스트가 잘못된 것.

## Step 4. 최소 구현 (GREEN)

테스트를 통과하는 **최소한의 코드만** 작성.
- 완벽한 코드를 작성하지 말 것
- 하드코딩이어도 테스트가 통과하면 OK

## Step 5. 테스트 실행 → 통과 확인

```bash
./gradlew :모듈:test --tests "*{클래스}Test" --no-daemon
```

## Step 6. 리팩터링 (REFACTOR)

테스트가 통과하는 상태를 유지하면서:
- 하드코딩 → 실제 로직
- 중복 제거
- 네이밍 개선
- 매 리팩터링 후 테스트 재실행

## Step 7. 반복

다음 테스트 케이스로 Step 2~6 반복:
- 정상 케이스 → 경계값 → 에러 케이스 → null 처리

## 이 프로젝트에서의 TDD 적용 범위

### 바로 테스트 가능 (외부 의존성 없음)

| 대상 | 모듈 | 예시 |
|------|------|------|
| Enum 팩토리 메서드 | common | `AbsenceTypeCode.fromLabel()`, `JiraStatusCode.fromCategoryKey()` |
| DateTimeUtil | common | KST 변환, 주간 계산, 금요일 판별 |
| SlackBlockBuilder | common | 블록 생성, JSON 직렬화 |
| CalendarTicketParser | scheduler | 이슈키 추출, 담당자 파싱, 상태 감지 |
| HttpResponse | ingest | modalError, modalResult 생성 |

### Mock 필요 (외부 의존성)

| 대상 | Mock 대상 | 예시 |
|------|----------|------|
| CalendarService | GoogleCalendarClient, JiraCalendarMappingService | processJiraEvent 분기 |
| ConfigService | S3Client | TTL 캐시 만료/갱신 |
| DedupeService | DynamoDbClient | 중복 감지 |
| SlackNotificationService | SlackClient | DM/채널 분기 |

### 테스트 불필요 (단순 위임)

| 대상 | 이유 |
|------|------|
| Handler 클래스 | 라우팅만 수행, Facade에 위임 |
| ModalBuilder 클래스 | JSON 구조 생성 (UI 테스트 영역) |

## Gradle 테스트 설정

```groovy
// 각 모듈 build.gradle에 추가
dependencies {
    testImplementation platform('org.junit:junit-bom:5.10.0')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.mockito:mockito-core:5.8.0'
    testImplementation 'org.mockito:mockito-junit-jupiter:5.8.0'
}

test {
    useJUnitPlatform()
}
```
