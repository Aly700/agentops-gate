# CI/CD pipeline — exercised (2026-08-29)

Repository: private GitHub repository `Aly700/agentops-gate` (not yet public). Trigger: `workflow_dispatch`
(deploys are deliberately manual so a documentation push can never start billable infrastructure).

| Step (GitHub Actions, ubuntu-latest) | Result |
|---|---|
| CI: `./mvnw verify` with Testcontainers PostgreSQL + LocalStack | green on every commit since the first push |
| Deploy: `aws-actions/configure-aws-credentials` with `role-to-assume` = the CDK-created role, no static keys | assumed via OIDC |
| Deploy: Maven build, Docker build, push to ECR tagged with the commit SHA | image `8f375e7…` |
| Deploy: `npx cdk deploy` of Budget, Network, Queue, Bucket, Data, Service stacks | all six ✅ (run 33264013858, 2026-08-29T16:49:41Z) |
| Walkthrough against the pipeline-deployed task (health, 401, policy, ALLOW/DENY/REQUIRE_APPROVAL, approve, 13 audit rows, export, S3 object) | OK |
| `cdk destroy` of the app stacks afterwards | account back to budget alarm + OIDC role + bootstrap |

## Two things that broke first, and why

1. **GitHub's OIDC `sub` claim now carries numeric ids** — `repo:Aly700@112176329/agentops-gate@1350743410:ref:refs/heads/main`
   rather than the documented `repo:OWNER/REPO:ref:refs/heads/main`. An exact-match trust condition can never
   succeed. The role trust now uses `StringLike` and accepts both forms; the audience condition stays exact.
   Found by printing the token's claims from a throwaway workflow rather than guessing.
2. **TypeScript 6 ships platform-native packages** (`@typescript/typescript-linux-x64`); a lockfile generated on
   macOS leaves `npm ci` on the Linux runner without them and `tsc` fails. `infra/` pins TypeScript 5.
