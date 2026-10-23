# Nexora Bank

Production-style banking backend using Spring Boot microservices — **no self-managed service discovery**: the API gateway and Feign clients call explicit DNS URLs, so **Kubernetes (the primary deployment target) provides discovery** via kube-dns + Service objects, while **Kafka carries the asynchronous communication** (transaction events → notifications). The same images deploy to docker-compose, Kubernetes, or AWS ECS with only environment variables.

## Architecture

Client → API Gateway (JWT) → services via DNS URLs (the platform owns discovery & load balancing — kube-dns + Kubernetes Services on k8s, container DNS on compose)

- **config-server** (8888): native Spring Cloud Config (optional)
- **api-gateway** (8080): routing + JWT filter
- **user-service** (8081): users + register/login
- **account-service** (8082): accounts, balances, optimistic locking
- **transaction-service** (8083): deposit, withdrawal, transfer, idempotency
- **notification-service** (8084): Kafka consumer for transaction events
- **MySQL**: shared `nexora_bank` database
- **Kafka** (KRaft mode): `transaction-events` topic

> **Why no Eureka?** A self-hosted registry duplicates what the deployment platform already does. On Kubernetes (primary target), **kube-dns resolves service names** and **Services route only to ready pods** (readiness probes) — health-aware load balancing with zero registry to run. All inter-service addresses are plain env-driven URLs (`USER_SERVICE_URL`, `ACCOUNT_SERVICE_URL`, …) that map to Kubernetes Service names in k8s, container names in compose, and Service Connect DNS names on AWS (see the [AWS appendix](docs/AWS_ECS_DEPLOYMENT.md)).

## Communication: sync money path + async events

Two deliberately different styles — the production-standard hybrid:

- **Synchronous (OpenFeign over HTTP):** the money path — balance updates with optimistic-lock version checks and ownership validation. These are request/response operations: a deposit must return the updated balance and a conflict must come back as 409 so the caller can retry. Kafka cannot answer back, so this stays synchronous.
- **Asynchronous (Kafka):** transaction-service publishes `transaction-events`; notification-service consumes at its own pace. Money movement never blocks or fails because a notification is slow.

## Run locally (IntelliJ)

1. Create MySQL schema `nexora_bank`.
2. Start config-server (optional), then remaining services.
3. Optional: `docker compose up mysql kafka` if you want Kafka notifications.

Default local DB user is `root`. Password is read from `MYSQL_PASSWORD` (local default is already in each `application.properties`).

## Run with Docker

```bash
docker compose up --build
```

Compose wires healthchecks end to end: MySQL → Kafka → everything else, with services calling each other by container DNS name (`http://account-service:8082`, …).

Gateway: `http://localhost:8080`

## Sample API flow (through gateway)

Register:

```http
POST http://localhost:8080/auth/register
Content-Type: application/json

{
  "name": "Elena",
  "email": "elena@nexora.com",
  "mobile": "9999999999",
  "password": "secret1"
}
```

Use the returned Bearer token on later calls:

```http
Authorization: Bearer <token>
```

Create user-linked account:

```http
POST http://localhost:8080/accounts
```

Deposit / withdraw:

```http
POST http://localhost:8080/transactions
```

Include a unique `idempotencyKey` so retries do not double-post.

Transfer:

```http
POST http://localhost:8080/transactions/transfer
```

Update your own profile (name, mobile, optional new password):

```http
PUT http://localhost:8080/users/me
Content-Type: application/json

{
  "name": "Elena N.",
  "mobile": "9888888888",
  "password": "newsecret1"
}
```

Notifications (persisted by notification-service from Kafka events):

```http
GET  http://localhost:8080/notifications              # list mine
GET  http://localhost:8080/notifications/unread-count # {"unread": 3}
PATCH http://localhost:8080/notifications/read-all    # mark all read (204)
PATCH http://localhost:8080/notifications/{id}/read   # mark one read (204)
```

## Testing

Each service has an isolated test suite that runs against in-memory H2 (MySQL mode) with Config Server and Kafka disabled — no external infrastructure needed:

```bash
cd account-service  && ./mvnw test   # includes optimistic-locking concurrency tests
cd user-service     && ./mvnw test   # register/login/profile unit tests
cd transaction-service && ./mvnw test # deposit/withdraw/idempotency/conflict/compensation
```

Highlights:

- **AccountConcurrencyTest** proves a stale `version` is rejected with **409** and that two racing updates with the same version produce exactly one winner and one 409.
- **TransactionServiceTest** proves idempotent replay returns the original result without side effects, and that a failed transfer **compensates** the sender (and records a FAILED audit row via a `REQUIRES_NEW` transaction).
- **JwtAuthenticationFilterTest** proves the gateway overwrites client-supplied `X-User-Id`/`X-User-Role` headers with the token identity (spoofing is impossible).

## Kubernetes (primary deployment target)

Manifests for **all services plus MySQL and Kafka** (with a Secret for credentials and a ConfigMap carrying the URL contract) are in `k8s/core.yaml`. The gateway is exposed on NodePort 30080. Build the `nexora/<name>:latest` images from each service's Dockerfile, load them into your cluster (minikube and kind are free), then:

```bash
kubectl apply -f k8s/core.yaml
```

Discovery on Kubernetes is native: env vars point at **Service names** (`USER_SERVICE_URL=http://user-service:8081`), kube-dns resolves them, and each Service load-balances across pods that pass their readiness probe (`/actuator/health`) — no registry, no sidecars, no extra dependencies.

## AWS ECS (optional appendix)

Prefer AWS? [docs/AWS_ECS_DEPLOYMENT.md](docs/AWS_ECS_DEPLOYMENT.md) covers the full Fargate deployment path with ECS Service Connect, task definitions, secrets from AWS Secrets Manager, and the ALB setup for the gateway — same images, same env contract, only the DNS names change.
