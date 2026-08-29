import * as cdk from 'aws-cdk-lib';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';

export interface ServiceStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly database: rds.DatabaseInstance;
  readonly databaseSecret: secretsmanager.ISecret;
  readonly apiKeySecret: secretsmanager.ISecret;
  readonly approvalQueue: sqs.IQueue;
  readonly deadLetterQueue: sqs.IQueue;
  readonly auditBucket: s3.IBucket;
  readonly imageTag: string;
}

export class ServiceStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ServiceStackProps) {
    super(scope, id, props);

    const repository = ecr.Repository.fromRepositoryName(this, 'Repository', 'agentops-gate');
    const taskCpu = contextNumber(this, 'taskCpu', 256);
    const taskMemory = contextNumber(this, 'taskMemory', 512);
    const cluster = new ecs.Cluster(this, 'Cluster', { vpc: props.vpc });
    const logGroup = new logs.LogGroup(this, 'ApplicationLogs', {
      retention: logs.RetentionDays.ONE_WEEK,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });
    const taskDefinition = new ecs.FargateTaskDefinition(this, 'TaskDefinition', {
      cpu: taskCpu,
      memoryLimitMiB: taskMemory,
    });

    const container = taskDefinition.addContainer('Application', {
      image: ecs.ContainerImage.fromEcrRepository(repository, props.imageTag),
      logging: ecs.LogDrivers.awsLogs({
        logGroup,
        streamPrefix: 'agentops-gate',
      }),
      environment: {
        DB_URL: `jdbc:postgresql://${props.database.dbInstanceEndpointAddress}:${props.database.dbInstanceEndpointPort}/agentops_gate`,
        AWS_REGION: cdk.Stack.of(this).region,
        AGENTOPS_AWS_ENABLED: 'true',
        AGENTOPS_CLOUDWATCH_METRICS_ENABLED: 'true',
        AUDIT_EXPORT_ENABLED: 'true',
        APPROVAL_QUEUE_URL: props.approvalQueue.queueUrl,
        APPROVAL_DLQ_URL: props.deadLetterQueue.queueUrl,
        APPROVAL_WORKER_ENABLED: 'true',
        SQS_WAIT_TIME_SECONDS: '20',
        SPRING_PROFILES_ACTIVE: 'perf',
        AUDIT_BUCKET: props.auditBucket.bucketName,
      },
      secrets: {
        DB_USERNAME: ecs.Secret.fromSecretsManager(props.databaseSecret, 'username'),
        DB_PASSWORD: ecs.Secret.fromSecretsManager(props.databaseSecret, 'password'),
        AGENTOPS_API_KEY: ecs.Secret.fromSecretsManager(props.apiKeySecret, 'apiKey'),
      },
    });
    container.addPortMappings({ containerPort: 8080 });

    props.approvalQueue.grantSendMessages(taskDefinition.taskRole);
    props.approvalQueue.grantConsumeMessages(taskDefinition.taskRole);
    props.deadLetterQueue.grantConsumeMessages(taskDefinition.taskRole);
    taskDefinition.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
      actions: ['s3:PutObject'],
      resources: [props.auditBucket.arnForObjects('audit/*')],
    }));
    props.apiKeySecret.grantRead(taskDefinition.taskRole);
    taskDefinition.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
      actions: ['cloudwatch:PutMetricData'],
      resources: ['*'],
      conditions: {
        StringEquals: { 'cloudwatch:namespace': 'AgentOpsGate' },
      },
    }));

    const service = new ecs.FargateService(this, 'Service', {
      cluster,
      taskDefinition,
      desiredCount: 1,
      assignPublicIp: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      circuitBreaker: { rollback: true },
      enableExecuteCommand: false,
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
    });
    service.connections.allowFrom(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(8080),
      'Public API access without an ALB',
    );
    new ec2.CfnSecurityGroupIngress(this, 'DatabaseIngress', {
      groupId: props.database.connections.securityGroups[0].securityGroupId,
      sourceSecurityGroupId: service.connections.securityGroups[0].securityGroupId,
      ipProtocol: 'tcp',
      fromPort: 5432,
      toPort: 5432,
      description: 'AgentOps Gate database access',
    });

    const serverErrors = new cloudwatch.Metric({
      namespace: 'AgentOpsGate',
      metricName: 'gate.http.responses.count',
      dimensionsMap: { status_class: '5xx' },
      statistic: 'Sum',
      period: cdk.Duration.minutes(5),
    });
    new cloudwatch.Alarm(this, 'ServerErrorAlarm', {
      metric: serverErrors,
      threshold: 5,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      alarmDescription: 'AgentOps Gate emitted at least five HTTP 5xx responses in five minutes',
    });

    const dlqDepth = props.deadLetterQueue.metricApproximateNumberOfMessagesVisible({
      statistic: 'Maximum',
      period: cdk.Duration.minutes(1),
    });
    new cloudwatch.Alarm(this, 'DlqDepthAlarm', {
      metric: dlqDepth,
      threshold: 1,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      alarmDescription: 'At least one approval message requires DLQ inspection and replay',
    });

    const outboxBacklog = new cloudwatch.Metric({
      namespace: 'AgentOpsGate',
      metricName: 'gate.outbox.backlog.value',
      statistic: 'Maximum',
      period: cdk.Duration.minutes(1),
    });
    const requestRate = new cloudwatch.MathExpression({
      expression: 'SUM(SEARCH(\'{AgentOpsGate} MetricName="http.server.requests.count"\', \'Sum\', 60)) / 60',
      label: 'requests/second',
      period: cdk.Duration.minutes(1),
    });
    const p99Latency = new cloudwatch.MathExpression({
      expression: 'SEARCH(\'{AgentOpsGate} MetricName="http.server.requests.max"\', \'p99\', 60)',
      label: 'p99 latency',
      period: cdk.Duration.minutes(1),
    });

    const dashboard = new cloudwatch.Dashboard(this, 'OperationsDashboard', {
      dashboardName: 'AgentOpsGate',
    });
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({ title: 'HTTP latency p99', left: [p99Latency] }),
      new cloudwatch.GraphWidget({ title: 'HTTP request rate', left: [requestRate] }),
      new cloudwatch.GraphWidget({ title: 'HTTP 5xx count', left: [serverErrors] }),
      new cloudwatch.GraphWidget({ title: 'Outbox backlog', left: [outboxBacklog] }),
      new cloudwatch.GraphWidget({ title: 'Approval DLQ depth', left: [dlqDepth] }),
      new cloudwatch.GraphWidget({
        title: 'ECS CPU and memory',
        left: [
          service.metricCpuUtilization({ period: cdk.Duration.minutes(1) }),
          service.metricMemoryUtilization({ period: cdk.Duration.minutes(1) }),
        ],
      }),
    );
  }
}

function contextNumber(scope: Construct, key: string, fallback: number): number {
  const raw = scope.node.tryGetContext(key);
  const value = raw === undefined ? fallback : Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`CDK context ${key} must be a positive integer`);
  }
  return value;
}
