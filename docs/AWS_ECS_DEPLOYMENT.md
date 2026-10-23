# Nexora Bank on AWS ECS (Fargate) — Deployment Guide

> **Optional appendix.** Kubernetes (`k8s/core.yaml`) is the primary deployment target for this project — open source, no vendor lock-in, and free to run locally with minikube/kind. This guide exists because the same images also deploy to AWS unchanged: only the env-var URLs differ.

This project uses **no in-app service discovery** (Eureka was removed deliberately). On AWS, the platform itself — **ECS Service Connect** (recommended) or **AWS Cloud Map** — owns discovery, health-aware load balancing, and scaling. The apps only need environment variables pointing at DNS names.

---

## 1. Why Eureka was removed (say this in an interview)

| Eureka on ECS | Service Connect / Cloud Map |
|---|---|
| You host, scale, patch, and pay for the Eureka tasks | Fully managed — zero infrastructure |
| Registry lag: heartbeats + caching delay routing decisions after scale events | ECS updates endpoints the moment task health changes |
| Custom health integration, self-preservation quirks | Health checks are native to task lifecycle |
| Extra moving part that can fail | Nothing extra to operate |

Eureka shines for self-managed VMs/Kubernetes across clouds. On a single managed platform like ECS it is redundant complexity and cost.

---

## 2. What changed in the code

- **Removed:** `spring-cloud-starter-netflix-eureka-client` from every service; the `eureka-server` module was deleted; all `lb://` gateway routes and `@FeignClient(name=...)` discovery lookups are gone.
- **Kept:** OpenFeign — declarative clients are still the right ergonomics. They now target explicit URLs:
  - account-service → `@FeignClient(name = "user-service", url = "${service-urls.user-service}")`
  - transaction-service → `@FeignClient(name = "account-service", url = "${service-urls.account-service}")`
- **Added (production hardening):**
  - Feign connect/read timeouts (3s/5s, tunable via `FEIGN_CONNECT_TIMEOUT_MS` / `FEIGN_READ_TIMEOUT_MS`) — no more infinite-hang risk under load.
  - `spring-boot-starter-actuator` on every service; `GET /actuator/health` is now the ECS readiness probe target.
- **URL contract (env-driven, same everywhere):**

| Variable | Consumed by | Points to |
|---|---|---|
| `USER_SERVICE_URL` | api-gateway, account-service | user-service |
| `ACCOUNT_SERVICE_URL` | api-gateway, transaction-service | account-service |
| `TRANSACTION_SERVICE_URL` | api-gateway | transaction-service |
| `NOTIFICATION_SERVICE_URL` | api-gateway | notification-service |

Because the contract is just DNS + env vars, the **same images** run on docker-compose, Kubernetes (`k8s/core.yaml`), and ECS.

---

## 3. Deployment topology (Fargate + Service Connect)

```
                        Internet
                            │
                     Application Load Balancer
                     (HTTPS :443, public subnets)
                            │ target group → api-gateway :8080
                            ▼
        ┌─────────────────── ECS Cluster (Fargate) ───────────────────┐
        │  Service Connect namespace: nexora.local                    │
        │                                                             │
        │  api-gateway ──http://user-service:8081────────► user-service
        │       │         ──http://account-service:8082──► account-service
        │       │         ──http://transaction-service:8083 ► transaction-service
        │       │         ──http://notification-service:8084► notification-service
        │                                                               │
        │  transaction ──Kafka──► MSK  │  all services ──► RDS MySQL   │
        └───────────────────────────────────────────────────────────────┘
```

Managed infrastructure used in production: **RDS MySQL 8** (Multi-AZ) instead of the container MySQL, and **MSK** (or Amazon MQ for Kafka) instead of the container Kafka. The container versions in compose are for local dev only.

---

## 4. Step-by-step (Fargate, Service Connect)

### Step 1 — Push images to ECR
```bash
aws ecr create-repository --repository-name nexora/user-service
aws ecr create-repository --repository-name nexora/account-service
aws ecr create-repository --repository-name nexora/transaction-service
aws ecr create-repository --repository-name nexora/notification-service
aws ecr create-repository --repository-name nexora/api-gateway
aws ecr create-repository --repository-name nexora/config-server

# per service, from the repo root:
docker build -t nexora/user-service:latest ./user-service
docker tag nexora/user-service:latest <account>.dkr.ecr.<region>.amazonaws.com/nexora/user-service:latest
aws ecr get-login-password | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
docker push <account>.dkr.ecr.<region>.amazonaws.com/nexora/user-service:latest
```

### Step 2 — Cloud Map namespace for Service Connect
```bash
aws servicediscovery create-private-dns-namespace \
  --name nexora.local --vpc vpc-xxxxxxxx
```

### Step 3 — Task definitions (env contract)

Example `user-service` task definition (Fargate, 1 vCPU / 2 GB):

```json
{
  "family": "nexora-user-service",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "executionRoleArn": "arn:aws:iam::<account>:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::<account>:role/nexora-task-role",
  "containerDefinitions": [
    {
      "name": "user-service",
      "image": "<account>.dkr.ecr.<region>.amazonaws.com/nexora/user-service:latest",
      "portMappings": [
        { "containerPort": 8081, "protocol": "tcp", "appProtocol": "http",
          "name": "user-service" }
      ],
      "environment": [
        { "name": "MYSQL_URL", "value": "jdbc:mysql://<rds-endpoint>:3306/nexora_bank" },
        { "name": "MYSQL_USER", "value": "nexora_app" },
        { "name": "JWT_SECRET", "value": "resolved-from-secrets-manager" }
      ],
      "secrets": [
        { "name": "MYSQL_PASSWORD",
          "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:nexora/mysql-password" },
        { "name": "JWT_SECRET",
          "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:nexora/jwt-secret" },
        { "name": "INTERNAL_API_KEY",
          "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:nexora/internal-api-key" }
      ],
      "healthCheck": {
        "command": ["CMD-SHELL", "curl -f http://localhost:8081/actuator/health || exit 1"],
        "interval": 15, "timeout": 5, "retries": 5, "startPeriod": 60
      }
    }
  ]
}
```

Repeat the pattern for the other services. Key env values per service:

| Service | Extra environment |
|---|---|
| api-gateway | `USER_SERVICE_URL=http://user-service:8081`, `ACCOUNT_SERVICE_URL=http://account-service:8082`, `TRANSACTION_SERVICE_URL=http://transaction-service:8083`, `NOTIFICATION_SERVICE_URL=http://notification-service:8084`, `JWT_SECRET` (secret) |
| account-service | `USER_SERVICE_URL=http://user-service:8081`, `MYSQL_URL`, `INTERNAL_API_KEY` (secret) |
| transaction-service | `ACCOUNT_SERVICE_URL=http://account-service:8082`, `MYSQL_URL`, `KAFKA_BOOTSTRAP_SERVERS` (MSK brokers), `INTERNAL_API_KEY` (secret) |
| notification-service | `MYSQL_URL`, `KAFKA_BOOTSTRAP_SERVERS` (MSK brokers) |
| config-server | nothing special |

> Optionally drop config-server entirely on AWS and serve config from SSM Parameter Store / Secrets Manager — one less service. The `optional:` config import already tolerates its absence.

### Step 4 — Create the ECS services **with Service Connect**

```bash
aws ecs create-service --cluster nexora-cluster --service-name user-service \
  --task-definition nexora-user-service --desired-count 2 --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-a,subnet-b],securityGroups=[sg-app]}" \
  --service-connect-configuration '{
    "enabled": true,
    "namespace": "nexora.local",
    "services": [{
      "portName": "user-service",
      "clientAliases": [{"port": 8081, "dnsName": "user-service"}]
    }]
  }'
```

Do this for each service. The `clientAliases[].dnsName` is exactly the hostname the env vars already point at (`http://user-service:8081`). Service Connect injects a sidecar proxy that load-balances across tasks, retries idempotent failures, and exports traffic metrics to CloudWatch — **no code changes**.

### Step 5 — Expose the gateway through an ALB
- Target group: `ip` targets, port 8080, health check path `/actuator/health`.
- ECS service `api-gateway` registered against that target group (no Service Connect needed for it; it is the ingress).
- Listeners: 443 (ACM certificate), redirect 80 → 443.

### Step 6 — Security groups (defense in depth)
- `sg-app`: all app tasks; inbound only from `sg-alb` (port 8080-8084) and from itself (inter-service).
- RDS SG: inbound 3306 only from `sg-app`.
- MSK SG: inbound 9092 only from `sg-app`.
- Internal API key (`INTERNAL_API_KEY`) still protects `/internal/**` even inside the VPC.

---

## 5. If you prefer AWS Cloud Map API-based discovery

Cloud Map works too: register each ECS service with `aws servicediscovery` and resolve `user-service.nexora.local` via DNS — the env contract stays identical. Prefer plain DNS lookups (what this project does) over the Cloud Map *API* (`DiscoverInstances`) unless you need instance-level metadata, because DNS keeps the code AWS-agnostic.

---

## 6. Rollout order

1. RDS + MSK + Secrets (with real values) first.
2. config-server (or skip it, see above).
3. user-service → account-service → transaction-service → notification-service.
4. api-gateway last; point the ALB at it.
5. Smoke test through the ALB: register → token → account → deposit → transfer → notifications.

---

## 7. Interview sound bites from this migration

- "I removed Eureka because on ECS it duplicates what the platform already does — Service Connect tracks task health natively and scales without me running a registry. I kept OpenFeign but moved it from discovery-based lookup to explicit env-driven URLs, which keeps the images portable across compose, k8s, and ECS."
- "I added Feign timeouts because the default is infinite — under load a hung downstream call exhausts the thread pool. Bounded latency plus idempotency keys means fail-fast and safe retry."
- "The gateway's `lb://` routes became plain `http://` URLs from env vars — one contract, three deployment targets."
