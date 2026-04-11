package com.riman.automation.worker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DedupeServiceTest {

    private static final String TABLE_NAME = "test-dedupe-table";

    @Mock
    private DynamoDbClient dynamoDbClient;

    private DedupeService dedupeService;

    @BeforeEach
    void setUp() {
        dedupeService = new DedupeService(dynamoDbClient, TABLE_NAME);
    }

    // =========================================================================
    // isDuplicate (Jira 이벤트: eventId + timestamp)
    // =========================================================================

    @Nested
    @DisplayName("isDuplicate — Jira 이벤트 중복 체크")
    class IsDuplicateTest {

        @Test
        @DisplayName("아이템이 존재하면 true 반환")
        void isDuplicate_itemExists_returnsTrue() {
            GetItemResponse response = GetItemResponse.builder()
                    .item(Map.of("eventId", AttributeValue.builder().s("evt-1").build()))
                    .build();
            when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(response);

            boolean result = dedupeService.isDuplicate("evt-1", "CCE-100", 1000L);

            assertThat(result).isTrue();
            verify(dynamoDbClient).getItem(any(GetItemRequest.class));
        }

        @Test
        @DisplayName("아이템이 없으면 false 반환")
        void isDuplicate_itemNotExists_returnsFalse() {
            GetItemResponse response = GetItemResponse.builder().build();
            when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(response);

            boolean result = dedupeService.isDuplicate("evt-2", "CCE-200", 2000L);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("DynamoDB 예외 시 false 반환 (진행 허용)")
        void isDuplicate_exception_returnsFalse() {
            when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                    .thenThrow(DynamoDbException.builder().message("timeout").build());

            boolean result = dedupeService.isDuplicate("evt-3", "CCE-300", 3000L);

            assertThat(result).isFalse();
        }
    }

    // =========================================================================
    // isDuplicateByKey (prefix key: REMOTE#, ABSENCE#, SCHEDULE#)
    // =========================================================================

    @Nested
    @DisplayName("isDuplicateByKey — prefix 키 중복 체크")
    class IsDuplicateByKeyTest {

        @Test
        @DisplayName("쿼리 결과가 있으면 true 반환")
        void isDuplicateByKey_exists_returnsTrue() {
            QueryResponse response = QueryResponse.builder()
                    .count(1)
                    .items(Map.of("eventId", AttributeValue.builder().s("REMOTE#abc").build()))
                    .build();
            when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(response);

            boolean result = dedupeService.isDuplicateByKey("REMOTE#abc");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("쿼리 결과가 없으면 false 반환")
        void isDuplicateByKey_notExists_returnsFalse() {
            QueryResponse response = QueryResponse.builder().count(0).build();
            when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(response);

            boolean result = dedupeService.isDuplicateByKey("ABSENCE#xyz");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("DynamoDB 예외 시 false 반환")
        void isDuplicateByKey_exception_returnsFalse() {
            when(dynamoDbClient.query(any(QueryRequest.class)))
                    .thenThrow(DynamoDbException.builder().message("error").build());

            boolean result = dedupeService.isDuplicateByKey("SCHEDULE#err");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("keyConditionExpression에 올바른 prefix 키가 전달된다")
        void isDuplicateByKey_correctKeyPassed() {
            QueryResponse response = QueryResponse.builder().count(0).build();
            when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(response);

            dedupeService.isDuplicateByKey("ABSENCE#event-123");

            ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(dynamoDbClient).query(captor.capture());

            QueryRequest captured = captor.getValue();
            assertThat(captured.tableName()).isEqualTo(TABLE_NAME);
            assertThat(captured.keyConditionExpression()).isEqualTo("eventId = :eventId");
            assertThat(captured.expressionAttributeValues().get(":eventId").s())
                    .isEqualTo("ABSENCE#event-123");
        }
    }

    // =========================================================================
    // saveEvent / saveEventKey
    // =========================================================================

    @Nested
    @DisplayName("saveEvent — Jira 이벤트 저장")
    class SaveEventTest {

        @Test
        @DisplayName("eventId, issueKey, timestamp를 DynamoDB에 저장한다")
        void saveEvent_putItemCalled() {
            dedupeService.saveEvent("evt-1", "CCE-100", 1000L);

            ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
            verify(dynamoDbClient).putItem(captor.capture());

            Map<String, AttributeValue> item = captor.getValue().item();
            assertThat(item.get("eventId").s()).isEqualTo("evt-1");
            assertThat(item.get("issueKey").s()).isEqualTo("CCE-100");
            assertThat(item.get("timestamp").n()).isEqualTo("1000");
        }

        @Test
        @DisplayName("저장 실패 시 예외를 삼킨다 (로그만)")
        void saveEvent_exception_swallowed() {
            when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                    .thenThrow(DynamoDbException.builder().message("write error").build());

            // 예외가 발생하지 않아야 한다
            dedupeService.saveEvent("evt-2", "CCE-200", 2000L);
        }
    }

    @Nested
    @DisplayName("saveEventKey — prefix 키 저장")
    class SaveEventKeyTest {

        @Test
        @DisplayName("key를 eventId로, REMOTE_WORK를 issueKey로 저장한다")
        void saveEventKey_putItemCalled() {
            dedupeService.saveEventKey("REMOTE#2024-01-15");

            ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
            verify(dynamoDbClient).putItem(captor.capture());

            Map<String, AttributeValue> item = captor.getValue().item();
            assertThat(item.get("eventId").s()).isEqualTo("REMOTE#2024-01-15");
            assertThat(item.get("issueKey").s()).isEqualTo("REMOTE_WORK");
            assertThat(item.get("timestamp").n()).isNotNull();
        }

        @Test
        @DisplayName("저장 실패 시 예외를 삼킨다")
        void saveEventKey_exception_swallowed() {
            when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                    .thenThrow(DynamoDbException.builder().message("write error").build());

            dedupeService.saveEventKey("ABSENCE#fail");
        }
    }
}
