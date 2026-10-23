# Nexora Bank — Interview Preparation Guide

Everything here is based on YOUR actual code. Quote it confidently — every answer below maps to a real file in this repo.

---

## 1. The 60-Second Elevator Pitch (memorize this)

> "I built **Nexora Bank**, a production-style banking backend using **Spring Boot microservices**. It has six services — Config Server, an API Gateway, and four business services for users, accounts, transactions, and notifications — with **no self-managed service discovery**: the gateway and Feign clients call explicit env-driven DNS URLs, and **Kubernetes owns discovery and health-aware load balancing** (kube-dns + Services + readiness probes; docker-compose DNS locally, ECS Service Connect on AWS). Clients hit a single gateway that validates **JWT tokens** and forwards identity headers downstream. The interesting engineering problems I solved were **concurrency on balances** using **optimistic locking with version checks**, **idempotent transactions** so network retries never double-charge, and **distributed transfers** — since sender and receiver live behind separate service calls, I implemented a **compensating-transaction pattern** with retries to roll back the debit if the credit fails. Transaction events are published to **Kafka** and consumed asynchronously by the notification service, so notifications never block the money flow. Everything runs locally via **Docker Compose** with MySQL and Kafka in **KRaft mode**, with sample **Kubernetes manifests** and an **AWS ECS Fargate deployment guide**.

---

## 2. Architecture (draw this on the whiteboard)

```
                        ┌────────────────┐
   Client ──HTTP──────► │  API Gateway   │ :8080  (validates JWT, injects
                        │  (Spring Cloud │         X-User-Id / X-User-Role,
                        │   Gateway)     │         blocks /internal/**)
                        └───────┬────────┘
     explicit DNS URLs      │   (USER_SERVICE_URL, ACCOUNT_SERVICE_URL, ...)
        ┌───────────┬──────────┼───────────┬──────────────┐
        ▼           ▼          ▼           ▼              ▼
   USER-SERVICE  ACCOUNT   TRANSACTION  NOTIFICATION
      :8081      -SERVICE    -SERVICE      -SERVICE      (no registry —
                  :8082       :8083         :8084       infra owns discovery)
        │           │  ▲         │  ▲          │
        │  Feign    │  └─Feign───┘  │          │
        └──/internal│──/internal────┘          │
                    │                          │
                    ▼                          ▼
                 MySQL ◄────── all services   Kafka topic
              (nexora_bank)                   "transaction-events"
```

**Discovery:** none inside the apps — inter-service targets are plain URLs from env vars; **Kubernetes resolves them natively** (kube-dns + Services load-balancing across pods that pass the readiness probe `/actuator/health`). **Communication:** synchronous Feign for the money path, Kafka events for notifications. **Start-up order:** Config Server (8888, optional) → business services.

### The request flow for "create a transaction"
1. Client sends `POST /transactions` with `Authorization: Bearer <jwt>` + an `idempotencyKey`.
2. Gateway's `JwtAuthenticationFilter` (a `GlobalFilter`, order -1) parses and verifies the JWT signature, then **mutates the request** adding `X-User-Id` and `X-User-Role` headers. It also **blocks any `/internal` path from outside** and lets `/auth/**` through without a token.
3. Gateway routes to `http://transaction-service:8083` (a plain URL from `TRANSACTION_SERVICE_URL`; on ECS, Service Connect resolves and load-balances it with health checks).
4. `TransactionController` reads the actor from headers via the `RequestActor` utility.
5. `TransactionService` (in `@Transactional`): checks the idempotency key → calls account-service over **OpenFeign** with the shared `X-Internal-Key` header → validates ownership & balance → updates balance with the **version** it read → saves the transaction → publishes a Kafka event.
6. Notification-service's `@KafkaListener` consumes the event and persists notifications for sender and receiver.

---

## 3. Service-by-Service Cheat Sheet

| Service | Port | Responsibility | Key tech |
|---|---|---|---|
| config-server | 8888 | Centralized config (native/classpath profile, `optional:` import so services boot without it) | Spring Cloud Config |
| api-gateway | 8080 | Single entry point, JWT verification, header injection, internal-API shield, CORS, SPA route | Spring Cloud Gateway (reactive), jjwt |
| user-service | 8081 | Register/login, issues JWTs | Spring Security, BCrypt, jjwt 0.12.6 |
| account-service | 8082 | Accounts, balances, **optimistic locking** (`@Version`) | JPA, Feign → user-service |
| transaction-service | 8083 | Deposit/withdraw/transfer, **idempotency**, **compensation**, Kafka publish | JPA, Feign → account-service, spring-kafka |
| notification-service | 8084 | Consumes transaction events, stores notifications | spring-kafka `@KafkaListener` |

**Infra:** MySQL 8 (`nexora_bank` schema, shared), Kafka 3.8 (Bitnami image, KRaft mode — no ZooKeeper), Docker Compose with healthchecks, **Kubernetes manifests in `k8s/core.yaml` (primary deployment target)**, optional AWS ECS guide in `docs/AWS_ECS_DEPLOYMENT.md`.

**Stack versions (say them precisely):** Java 17, Spring Boot **3.4.10**, Spring Cloud **2024.0.1**, jjwt **0.12.6**, jakarta.persistence (not javax — shows you're on the modern stack).

---

## 4. The Core Technical Answers (what interviewers dig into)

### Q: How did you secure the microservices? Walk me through the auth flow.
**A:** Two-layer security:
1. **Perimeter (gateway):** A `GlobalFilter` verifies the JWT (HS256, shared secret ≥32 bytes via `Keys.hmacShaKeyFor`). Tokens are issued by user-service at register/login with `subject = email` and claims `userId`, `role`, 24h expiry. After verification the gateway **strips trust from the client** — it *removes* any client-supplied `X-User-Id`/`X-User-Role` headers first, then injects the token-derived values — so identity spoofing is impossible (there's a test proving it: `JwtAuthenticationFilterTest.clientSuppliedIdentityHeadersAreOverwrittenNotTrusted`). Invalid/missing tokens get 401 before routing. `/auth/**`, `/actuator`, and the SPA root are public.
2. **East-west (service-to-service):** `/internal/**` endpoints (used by Feign) are blocked at the gateway, and each service has an `InternalApiFilter` (extends `OncePerRequestFilter`) that requires a matching `X-Internal-Key` header. Feign clients attach it automatically via a `RequestInterceptor` in `InternalFeignConfig`.
3. **Authorization:** services enforce ownership — e.g., a non-admin can only open/transact on their own accounts (`ForbiddenException` → 403).

**Bonus hardening in the current code (great to mention):** failed transfers now write a **FAILED audit row in a `REQUIRES_NEW` transaction** (`TransactionFailureRecorder`), so the audit trail survives the rollback of the failed transfer — and it records whether compensation itself succeeded or failed. The gateway filter also strips client-supplied identity headers before injecting token identity, closing a classic header-spoofing hole.

### Q: Why put JWT validation in the gateway instead of each service?
**A:** Single responsibility — services stay focused on business logic, there's one enforcement point, and the secret only needs to live in the gateway (user-service signs, gateway verifies). It also lets me convert the token to simple trusted headers once, instead of every service parsing JWTs. I'd mention the trade-off honestly: downstream services **trust** the `X-User-Id` header, so they must never be network-reachable except via the gateway — that's exactly why I also added the internal-API key as defense-in-depth.

### Q: Two clients withdraw at the same time — how do you prevent corruption? (THE money question)
**A:** **Optimistic locking.** The `Account` entity has a `@Version Long version` field. The flow:
1. Transaction-service reads the account *including its version*.
2. It computes the new balance and calls `PUT /internal/accounts/{id}/balance?balance=..&version=..`.
3. Account-service compares the submitted version with the current entity version; if they differ, it throws `ObjectOptimisticLockingFailureException`, which the exception handler maps to **409 Conflict**.
4. Transaction-service catches `FeignException.Conflict` and converts it to a `ConcurrencyException` with a retry-friendly message — no silent lost updates.

I chose optimistic over pessimistic locking because banking disputes are rare relative to reads — pessimistic `SELECT ... FOR UPDATE` would serialize all concurrent access and hurt throughput, while optimistic locking only pays the retry cost on genuine conflicts. JPA bumps the version and does the `WHERE version = ?` check automatically on `save()`.

### Q: What happens if a client retries a payment because of a network timeout?
**A:** **Idempotency.** Every transaction request must carry a client-generated `idempotencyKey`, which is a **unique column** in the `transactions` table. Before processing, I look it up — if present, I return the original result instead of re-executing (exactly-once *effect* despite at-least-once *attempts*). If two requests race past the pre-check, the DB unique constraint rejects the second insert; I catch `DataIntegrityViolationException` in `saveIdempotent()` and fall back to fetching the existing row. So the protection is layered: **check-then-act + database constraint as the last line of defense.**

### Q: A transfer touches two accounts across two services — no distributed transaction. How is it safe?
**A:** I'm honest that there's no 2PC, and that's deliberate. Instead:
1. Debit the sender with a version-checked update.
2. Re-read and credit the receiver with a version-checked update.
3. If the credit fails (exception of any kind), I run a **compensating transaction**: re-read the sender and re-credit the amount, with **3 retries and linear backoff** (150ms × attempt), re-reading the latest version each retry so the compensation itself is concurrency-safe. If compensation also fails, the request fails loudly with `IllegalStateException` — never silently.
This is essentially the **Saga pattern with a compensating action**. I know the next-level hardening: the **transactional outbox** pattern (write the credit intent in the same DB transaction as the debit, relay it via a poller/Debezium), which I'd add to guarantee compensation is never lost — that's my first improvement item.

### Q: Why Kafka? Why not call notification-service directly?
**A:** Decoupling + resilience. The money movement shouldn't fail or wait because a notification is slow or notification-service is down. With Kafka:
- transaction-service **publishes and forgets** (fire-and-forget, failures logged not thrown);
- notification-service consumes at its own pace with a consumer group `notification-service`;
- the event carries everything needed (reference, userId, receiverUserId, amount, type, status) as a JSON string keyed by transaction reference;
- the listener is conditional on `kafka.enabled`, and the publisher uses `ObjectProvider<KafkaTemplate>` so the whole transaction-service **boots and works even without Kafka configured** — graceful degradation.
It also gives natural replay (the topic persists events) and makes it trivial to add new consumers later (fraud detection, analytics, audit) with zero changes to the producer.

### Q: Why BigDecimal for money? Why `precision = 19, scale = 2`?
**A:** `double`/`float` can't represent decimal fractions exactly (0.1 + 0.2 ≠ 0.3) — unacceptable for money. `BigDecimal` gives exact decimal arithmetic; I map it to MySQL `DECIMAL(19,2)` via `@Column(precision = 19, scale = 2)`.

### Q: How do services find and call each other?
**A:** I deliberately removed **Eureka** — in a Kubernetes deployment a self-hosted registry duplicates what the platform already does, and worse: I'd host, patch, and pay for it, while its heartbeats lag behind pod readiness. Instead, every inter-service target is a plain env-driven URL (`ACCOUNT_SERVICE_URL=http://account-service:8082`); **Kubernetes resolves it natively** — a Service object per deployment, kube-dns for the name, endpoints restricted to pods passing the readiness probe (`/actuator/health`), giving health-aware load balancing with zero registry infrastructure. I kept **OpenFeign** for its declarative ergonomics but pointed it at explicit URLs (`@FeignClient(name = "account-service", url = "${service-urls.account-service}")`) instead of discovery lookup, and added **connect/read timeouts (3s/5s)** because Feign's defaults are infinite — a hung downstream call must fail fast, not exhaust the thread pool. The payoff: the **same images** deploy to docker-compose, Kubernetes, and ECS with only env changes. If they push on trade-offs: with explicit URLs I lose client-side zone awareness, but Kubernetes Services give health-checked LB at the platform layer — ready endpoints only — and topology-aware routing handles zones. If I needed canary weights later, that's a Service mesh or Argo Rollouts on the same Deployments, without touching app code.

### Q: What does the Config Server give you? Why is it "optional"?
**A:** It centralizes configuration (native profile serving from `classpath:/config`). Each service declares `spring.config.import=optional:configserver:...` — the `optional:` prefix means a developer can run services locally without the config server and env vars still override everything. In production the same properties would be served from a Git-backed config repo, enabling per-environment config without rebuilds.

### Q: How do you handle errors across services?
**A:** Each service has a `@RestControllerAdvice` `GlobalExceptionHandler` mapping domain exceptions to proper status codes: `UserNotFoundException`/`AccountNotFoundException` → 404, `DuplicateEmailException`/`DuplicateAccountException` → 409, `ForbiddenException` → 403, validation (`MethodArgumentNotValidException`) → 400 with a field→message map, optimistic-lock failures → 409 "retry". A consistent `{status, message}` JSON shape comes back everywhere.

### Q: Tell me about account numbers.
**A:** Auto-generated when the client doesn't supply one: `"NX"` + a 10-digit number from **SecureRandom**, regenerating in a loop until `existsByAccountNumber` is false. The column is also uniquely constrained, so even a race can't create a duplicate — the DB is the final authority. (Honest improvement: wrap that in a retry-on-constraint-violation loop.)

---

## 5. Behavioral / HR Questions — answer with YOUR project

**"Walk me through a challenge you faced."**
> "The transfer feature. My first version could leave a sender debited with no credit if the receiver call failed. I researched distributed transactions, rejected 2PC as impractical for microservices, and implemented the Saga compensating pattern — a rollback path with retries and backoff that re-reads the account version each attempt so the compensation itself survives concurrent updates. It taught me to design for failure as the default, not the exception."

**"What are you most proud of?"**
> "The transaction core: optimistic locking + idempotency together. Each solves a different failure mode — concurrency and retries — and they compose: the version check makes concurrent writes safe, the idempotency key makes duplicate requests safe. A client can retry as many times as it wants and money moves exactly once."

**"What would you improve?"** (shows maturity — say these are *known* trade-offs)
1. **Transactional outbox** for guaranteed compensation/event delivery (currently best-effort with retries).
2. **Database-per-service** — currently all services share one `nexora_bank` schema for simplicity; per-service schemas would remove hidden coupling and let each service scale its own storage.
3. **Resilience4j circuit breakers + timeouts** on Feign calls, and a load-shedding fallback.
4. **Refresh tokens / token revocation** (JWTs are stateless — a stolen token is valid for 24h).
5. **Observability:** Micrometer Tracing + Zipkin for distributed trace IDs across the Feign chain; correlation IDs on logs.
6. **Secrets management** — compose/k8s currently carry plaintext secrets; in production: Vault or cloud secret managers, rotated keys, per-service DB users with least privilege.
7. Real tests now exist and run with zero infra (H2 in MySQL mode): **two-thread optimistic-locking races with exactly-one-winner** (`AccountConcurrencyTest`), idempotent replay without side effects, transfer compensation including compensation-failure audit (`TransactionServiceTest`), and gateway header-spoofing prevention (`JwtAuthenticationFilterTest`). Next: Testcontainers for MySQL/Kafka and contract tests for Feign clients.

**"Why microservices for this? Isn't it overkill?"**
> "It's a learning-scale project, so honestly a modular monolith would also work — but I wanted to solve the real problems microservices introduce: service discovery, distributed auth, inter-service contracts, eventual consistency, and independent deployability. For a bank, the operational wins are real: transactions and notifications scale independently, a failure in notifications can't take down payments, and teams can deploy the transaction service without touching accounts."

**"Explain KRaft mode."**
> "Kafka's own consensus protocol replacing ZooKeeper — metadata lives in the brokers via a Raft quorum. My compose file runs a single combined controller+broker node (`KAFKA_CFG_PROCESS_ROLES: controller,broker`), which is simpler to operate and starts faster."

---

## 6. Quick-Fire Definitions (in one breath each)

- **Kubernetes Service + kube-dns** — the discovery layer in this project: a Service gives each deployment a stable virtual IP/DNS name, kube-dns resolves it, and endpoints only include ready pods. Replaced Eureka with zero app-side discovery code.
- **Service Connect / AWS Cloud Map** — the same story on AWS (optional appendix): Service Connect injects a sidecar proxy with health-aware LB, retries and metrics; Cloud Map exposes services as DNS names.
- **OpenFeign** — declarative HTTP client from an annotated interface; here it targets explicit env-driven URLs with bounded timeouts instead of registry lookups.
- **Spring Cloud Gateway** — reactive (Netty/Project Reactor) reverse proxy; routes via predicates to `lb://` URIs; filters mutate requests (mine adds identity headers).
- **Optimistic locking** — no DB locks; a version column is checked on write; conflict → exception → retry. Best for low-contention, high-read workloads.
- **Idempotency** — same request executed twice produces the same result as once; implemented with a unique client-supplied key.
- **Saga** — a sequence of local transactions; failures trigger compensating actions instead of a global rollback.
- **Kafka** — durable, partitioned, append-only event log with consumer groups; producer = transaction-service, consumer = notification-service.
- **BCrypt** — adaptive password hashing with built-in salt and configurable cost factor; used by `BCryptPasswordEncoder`.
- **JPA/Hibernate** — ORM mapping entities to tables; Hibernate generates the schema here via `ddl-auto=update`.
- **DTO pattern** — requests/responses are separate classes (`RegisterRequest`, `AccountResponse`…) so entities (and the `@JsonIgnore` password) never leak through the API.

---

## 7. Numbers to keep in your head

- 6 services · 5 runtime ports (8080–8084) + 8888 · 1 MySQL schema · 1 Kafka topic · **0 self-hosted discovery**
- JWT TTL: **86,400,000 ms = 24h** (`jwt.expiration-ms`)
- Balance precision: **DECIMAL(19,2)** · Compensation: **3 retries, 150ms×attempt backoff**
- Token claims: `sub` = email, `userId`, `role` · Header contract: `X-User-Id`, `X-User-Role`, `X-Internal-Key`

## 8. Explaining the Hardening Pass — your best stories

The strongest interview material is the security/quality pass on the finished project. "How would I break my own bank?" found two real bugs, closed feature gaps, and added 37 tests. Frame it as self-critique — the #1 trait interviewers screen for.

> "The project worked in the happy path, so I did a hardening pass and asked: *how would I break my own bank?* I found two real bugs, closed several feature gaps, and backed everything with tests. That process taught me more than building the features did."

### Story 1 — The header-spoofing bug (lead with this)
- **Situation:** gateway verifies JWTs, then forwards identity via `X-User-Id` / `X-User-Role` headers.
- **Discovery:** wrote a test where a client with a *valid* token also sends his own fake `X-User-Id: 999` — and it worked. The filter used `.header()` which **appends**, and downstream reads the **first** header — the attacker's. Any user could impersonate anyone, or fake ADMIN.
- **Fix:** strip client-supplied identity headers **before** injecting token-derived values.
- **Proof:** `JwtAuthenticationFilterTest.clientSuppliedIdentityHeadersAreOverwrittenNotTrusted` — regression test that fails if the fix is ever reverted.
- **Punchline:** "Never trust input you didn't create — even your own header names."

### Story 2 — Proving concurrency safety
- "I claimed optimistic locking protected balances — but had no proof. I wrote a test that starts two threads with the **same stale version** and asserts **exactly one wins**, the other gets 409. Now the claim is a passing test, not a promise."
- Test: `AccountConcurrencyTest.concurrentUpdatesWithSameVersionYieldExactlyOneWinner` (plus the 409-on-stale-version and version-bump tests).

### Story 3 — The Docker environment bug (debugging skill)
- "Notification-service worked locally but couldn't reach MySQL in Docker. Compose passed DB env vars to three services but **forgot this one**, so it fell back to its `localhost` default — which inside a container is *itself*. One-line fix, but the lesson: environment-parity bugs fail silently, which is why I added healthchecks and start-up ordering everywhere."

### Story 4 — The audit trail / REQUIRES_NEW (transaction depth)
- "Failed transfers left **no record** — the rollback erased everything. Fix: write the failure record in a **`REQUIRES_NEW`** transaction — Spring suspends the current transaction and runs the method in a fresh one that commits independently. Now every failed transfer leaves a FAILED row recording whether compensation succeeded."
- **If they drill:** "It costs a second connection from the pool, and — key point — the inner write survives even if the outer transaction rolls back. That's exactly the property I wanted, and it's the same idea behind the transactional outbox."
- Test: `TransactionServiceTest.transferCompensatesSenderWhenReceiverCreditFails`.

### Feature gaps I closed (say it in one breath)
"Mark-as-read endpoints for notifications (the read-flag existed but was unreachable), a profile-update endpoint with proper password re-hashing, and a retry loop on account-number generation backed by the unique constraint — the DB stays the final authority."

### Delivery rules
1. **Problem → action → proof.** Never open with the tech; open with what could go wrong.
2. **Every claim points to an artifact** — say the test names out loud.
3. **Numbers:** 6 services · 37 tests · 409 on stale version · 3 compensation retries, 150ms backoff · Feign 3s/5s timeouts · 24h token TTL · DECIMAL(19,2).
4. **Volunteer weaknesses first:** no 2PC (Saga + compensation), best-effort Kafka publish (outbox next), shared DB schema, plaintext dev secrets.
5. If they go deep on one thing, that's a good sign — it means you sound authentic.

## 9. The Eureka-to-Kubernetes migration story (architecture judgment)

> "The project originally used Eureka. When I planned real deployments I removed it: on Kubernetes you'd host, scale, patch and pay for a registry that duplicates what the platform already does — and its heartbeats lag behind pod readiness, so routing decisions trail reality. **Kubernetes is my discovery layer now**: a Service object per deployment gives every service a stable DNS name, kube-dns resolves it, and the Service load-balances only across pods passing their readiness probe (`/actuator/health`). Zero registry to operate, zero platform code in the app.
>
> The interesting part is what I did to the app: Feign clients switched from registry lookup (`@FeignClient(name=...)`) to **explicit URLs from env vars** (`url = "${service-urls.account-service}"`), and gateway `lb://` routes became plain `http://` URLs from the same contract. I kept OpenFeign — the ergonomics are still right — but added **3s/5s timeouts** because Feign defaults to infinite, and infinite waits plus thread pools equals outage. Now the same image deploys to docker-compose, Kubernetes, or ECS with only environment variables."

**If they push:** "What do you lose without client-side discovery?" → "Zone-aware routing and per-instance load metrics in the app. Kubernetes Services give health-checked LB at the platform layer — ready endpoints only — which covers the real risk, routing to dead pods, and topology-aware routing handles zones. If I ever needed canary weights, that's a Service mesh (Istio) or Argo Rollouts on the same Deployments, again without app changes."

**Why not Spring Cloud Kubernetes discovery (DiscoveryClient)?** → "It would let Feign use `@FeignClient(name)` and the gateway use `lb://` again — but it couples every service to the k8s API server and an extra library for behavior I already get from DNS + Service endpoints. Plain URLs keep the images portable across docker-compose, Kubernetes, and ECS. If I needed per-instance metadata filtering, I'd add it gated behind a `k8s` profile."

**Why is service-to-service communication not all Kafka?** → "Kafka is fire-and-forget — it can't answer back. The money path is request/response: a deposit must return the updated balance, and optimistic-lock conflicts must reach the caller as 409 so it can retry safely. So I use the production-standard hybrid: **synchronous Feign for the money path, Kafka for events** — transaction events flow to notification-service asynchronously by design, so money movement never waits on a notification."

## 10. Live-demo script (if they say "show me")

1. `docker compose up --build` → gateway on :8080 — or `kubectl apply -f k8s/core.yaml` on minikube and use NodePort 30080; same images on both, which is the portability point.
2. `POST /auth/register` → get token.
3. `POST /accounts` (with Bearer) → account with generated `NX…` number.
4. `POST /transactions` deposit 1000 → show SUCCESS + reference `TXN-…`.
5. Retry the exact same body (same idempotencyKey) → **same response, no double credit**.
6. Withdraw 5000 from a 1000 balance → clean 400-ish insufficient-balance error.
7. Transfer to a second account → both balances correct; open notification-service (`GET /notifications` with Bearer) → both users have notifications from Kafka.
