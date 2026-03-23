package com.riman.automation.groupware.service;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.ContainerOverride;
import software.amazon.awssdk.services.ecs.model.Failure;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.TaskOverride;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ECS Fargate Task 실행 서비스.
 *
 * <pre>
 * - Public Subnet + EIP 방식 (NAT Gateway 불필요, 비용 절감)
 * - ID/PW는 환경변수로 전달하지 않으며, Task 내부에서 Secrets Manager 직접 조회
 * - 컨테이너 이름: "groupware-bot"
 * </pre>
 */
@Slf4j
public class EcsTaskService {

    private static final EcsClient ECS = EcsClient.builder().build();

    /**
     * 컨테이너 이름 — Task Definition containerDefinitions[0].name 과 일치해야 함
     */
    private static final String CONTAINER_NAME = "groupware-bot";

    private final String clusterArn;
    private final String taskDefinitionArn;
    private final String subnetId;
    private final String securityGroupId;

    public EcsTaskService() {
        this.clusterArn = System.getenv("ECS_CLUSTER_ARN");
        this.taskDefinitionArn = System.getenv("ECS_TASK_DEFINITION_ARN");
        this.subnetId = System.getenv("ECS_SUBNET_ID");
        this.securityGroupId = System.getenv("ECS_SECURITY_GROUP_ID");
        log.info("[EcsTaskService] initialized: cluster={}, taskDef={}",
                clusterArn, taskDefinitionArn);
    }

    /**
     * Fargate Task 실행.
     *
     * @param taskEnv Task에 주입할 환경변수 맵 (ID/PW 절대 포함 금지)
     * @return 실행된 Task ARN
     */
    public String runAbsenceTask(Map<String, String> taskEnv) {
        List<KeyValuePair> envPairs = taskEnv.entrySet().stream()
                .map(e -> KeyValuePair.builder()
                        .name(e.getKey())
                        .value(e.getValue())
                        .build())
                .collect(Collectors.toList());

        RunTaskRequest request = RunTaskRequest.builder()
                .cluster(clusterArn)
                .taskDefinition(taskDefinitionArn)
                .launchType(LaunchType.FARGATE)
                .networkConfiguration(NetworkConfiguration.builder()
                        .awsvpcConfiguration(AwsVpcConfiguration.builder()
                                .subnets(subnetId)
                                .securityGroups(securityGroupId)
                                .assignPublicIp(AssignPublicIp.ENABLED) // NAT Gateway 불필요
                                .build())
                        .build())
                .overrides(TaskOverride.builder()
                        .containerOverrides(ContainerOverride.builder()
                                .name(CONTAINER_NAME)
                                .environment(envPairs)
                                .build())
                        .build())
                .build();

        RunTaskResponse response = ECS.runTask(request);

        if (!response.failures().isEmpty()) {
            Failure f = response.failures().get(0);
            String msg = String.format("ECS RunTask 실패: reason=%s, arn=%s",
                    f.reason(), f.arn());
            log.error("[EcsTaskService] {}", msg);
            throw new RuntimeException(msg);
        }

        String taskArn = response.tasks().get(0).taskArn();
        log.info("[EcsTaskService] Task 실행: {}", taskArn);
        return taskArn;
    }
}
