#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { BucketStack } from '../lib/bucket-stack';
import { BudgetStack } from '../lib/budget-stack';
import { DataStack } from '../lib/data-stack';
import { GithubOidcStack } from '../lib/github-oidc-stack';
import { NetworkStack } from '../lib/network-stack';
import { QueueStack } from '../lib/queue-stack';
import { ServiceStack } from '../lib/service-stack';

const app = new cdk.App();
const environment = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'us-east-1',
};

new GithubOidcStack(app, 'AgentOpsGithubOidcStack', { env: environment });

const network = new NetworkStack(app, 'AgentOpsNetworkStack', { env: environment });
const data = new DataStack(app, 'AgentOpsDataStack', {
  env: environment,
  vpc: network.vpc,
});
const queue = new QueueStack(app, 'AgentOpsQueueStack', { env: environment });
const bucket = new BucketStack(app, 'AgentOpsBucketStack', { env: environment });

new ServiceStack(app, 'AgentOpsServiceStack', {
  env: environment,
  vpc: network.vpc,
  database: data.database,
  databaseSecret: data.databaseSecret,
  apiKeySecret: data.apiKeySecret,
  approvalQueue: queue.approvalQueue,
  auditBucket: bucket.auditBucket,
  imageTag: app.node.tryGetContext('imageTag') ?? 'latest',
});

new BudgetStack(app, 'AgentOpsBudgetStack', {
  env: environment,
  notificationEmail: app.node.tryGetContext('budgetEmail') ?? 'replace-me@example.invalid',
});
