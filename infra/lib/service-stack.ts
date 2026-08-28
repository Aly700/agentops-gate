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
  readonly auditBucket: s3.IBucket;
  readonly imageTag: string;
}

export class ServiceStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ServiceStackProps) {
    super(scope, id, props);

    const repository = ecr.Repository.fromRepositoryName(this, 'Repository', 'agentops-gate');
    const cluster = new ecs.Cluster(this, 'Cluster', { vpc: props.vpc });
    const logGroup = new logs.LogGroup(this, 'ApplicationLogs', {
      retention: logs.RetentionDays.ONE_WEEK,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });
    const taskDefinition = new ecs.FargateTaskDefinition(this, 'TaskDefinition', {
      cpu: 256,
      memoryLimitMiB: 512,
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
        AUDIT_EXPORT_ENABLED: 'true',
        APPROVAL_QUEUE_URL: props.approvalQueue.queueUrl,
        APPROVAL_WORKER_ENABLED: 'true',
        SQS_WAIT_TIME_SECONDS: '20',
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
    taskDefinition.taskRole.addToPrincipalPolicy(new iam.PolicyStatement({
      actions: ['s3:PutObject'],
      resources: [props.auditBucket.arnForObjects('audit/*')],
    }));
    props.apiKeySecret.grantRead(taskDefinition.taskRole);

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

    const serverErrors = new logs.MetricFilter(this, 'ServerErrorMetricFilter', {
      logGroup,
      metricNamespace: 'AgentOpsGate',
      metricName: 'Http5xxCount',
      filterPattern: logs.FilterPattern.literal('"event=http_request" "status=5*"'),
      metricValue: '1',
      defaultValue: 0,
    });
    new cloudwatch.Alarm(this, 'ServerErrorAlarm', {
      metric: serverErrors.metric({
        statistic: 'Sum',
        period: cdk.Duration.minutes(5),
      }),
      threshold: 5,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      alarmDescription: 'AgentOps Gate emitted at least five HTTP 5xx responses in five minutes',
    });
  }
}
