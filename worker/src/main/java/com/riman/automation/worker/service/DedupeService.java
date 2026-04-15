package com.riman.automation.worker.service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 중복 이벤트 방지 서비스.
 * Jira 이벤트는 (eventId + timestamp) composite key로, 재택/부재/일정은 prefix key(REMOTE#/ABSENCE#/SCHEDULE#)로 중복을 판정한다.
 * 모든 조회 오류는 "중복 아님"으로 처리하여 사용자 요청 진행을 우선한다.
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

  /**
   * 테스트용 생성자. Mock DynamoDbClient를 주입할 수 있다.
   */
  DedupeService(DynamoDbClient dynamoDbClient, String tableName) {
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  /**
   * Jira 이벤트 중복 여부를 확인한다. composite key (eventId, timestamp)로 조회한다.
   */
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

  /**
   * Jira 이벤트를 DynamoDB에 저장한다. 저장 실패는 로그만 남기고 전파하지 않는다.
   */
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

  /**
   * 재택/부재/일정의 prefix key 기반 중복 여부를 확인한다.
   * 호출자는 "REMOTE#", "ABSENCE#", "SCHEDULE#" 등의 prefix가 포함된 key를 전달한다.
   */
  public boolean isDuplicateByKey(String key) {
    try {
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
      return false;
    }
  }

  /**
   * prefix key 기반 이벤트를 DynamoDB에 저장한다. timestamp는 현재 시각 사용.
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
