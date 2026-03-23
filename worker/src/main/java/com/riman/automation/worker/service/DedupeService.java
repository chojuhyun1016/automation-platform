package com.riman.automation.worker.service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 중복 이벤트 방지 서비스
 */
@Slf4j
public class DedupeService {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DedupeService() {
        this.dynamoDbClient = DynamoDbClient.builder().build();
        this.tableName = System.getenv("DYNAMODB_TABLE");

        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalStateException("DYNAMODB_TABLE 환경변수 미설정");
        }

        log.info("DedupeService initialized: table={}", tableName);
    }

    // =========================================================================
    // Jira 이벤트 중복 체크 (기존 방식: eventId + timestamp)
    // =========================================================================

    public boolean isDuplicate(String eventId, String issueKey, long timestamp) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("eventId", AttributeValue.builder().s(eventId).build());
            key.put("timestamp", AttributeValue.builder().n(String.valueOf(timestamp)).build());

            GetItemRequest request = GetItemRequest.builder()
                    .tableName(tableName)
                    .key(key)
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(request);
            boolean exists = response.hasItem();

            if (exists) {
                log.warn("Jira 중복 이벤트 감지: eventId={}, issueKey={}", eventId, issueKey);
            }

            return exists;

        } catch (Exception e) {
            log.error("Jira 중복 체크 오류: eventId={}", eventId, e);
            return false;
        }
    }

    public void saveEvent(String eventId, String issueKey, long timestamp) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("eventId", AttributeValue.builder().s(eventId).build());
            item.put("timestamp", AttributeValue.builder().n(String.valueOf(timestamp)).build());
            item.put("issueKey", AttributeValue.builder().s(issueKey).build());

            PutItemRequest request = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build();

            dynamoDbClient.putItem(request);
            log.info("Jira 이벤트 저장: eventId={}, issueKey={}", eventId, issueKey);

        } catch (Exception e) {
            log.error("Jira 이벤트 저장 실패: eventId={}", eventId, e);
        }
    }

    // =========================================================================
    // 재택 이벤트 중복 체크 (Key-only 방식: eventId만 사용)
    // =========================================================================

    /**
     * 재택신청 중복 체크
     * <p>
     * eventId만으로 중복 확인 (timestamp는 현재 시각 사용)
     */
    public boolean isDuplicateByKey(String key) {
        try {
            // Query를 사용하여 eventId로 검색
            Map<String, AttributeValue> keyCondition = new HashMap<>();
            keyCondition.put(":eventId", AttributeValue.builder().s(key).build());

            QueryRequest request = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("eventId = :eventId")
                    .expressionAttributeValues(keyCondition)
                    .limit(1)
                    .build();

            QueryResponse response = dynamoDbClient.query(request);
            boolean exists = response.count() > 0;

            if (exists) {
                log.warn("재택 중복 이벤트 감지: key={}", key);
            }

            return exists;

        } catch (Exception e) {
            log.error("재택 중복 체크 오류: key={}", key, e);
            return false; // 오류 시 중복 아님으로 처리 (진행 허용)
        }
    }

    /**
     * 재택신청 이벤트 저장
     */
    public void saveEventKey(String key) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("eventId", AttributeValue.builder().s(key).build());
            item.put("timestamp", AttributeValue.builder()
                    .n(String.valueOf(System.currentTimeMillis()))
                    .build());
            item.put("issueKey", AttributeValue.builder().s("REMOTE_WORK").build());

            PutItemRequest request = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build();

            dynamoDbClient.putItem(request);
            log.info("재택 이벤트 저장: key={}", key);

        } catch (Exception e) {
            log.error("재택 이벤트 저장 실패: key={}", key, e);
        }
    }
}
