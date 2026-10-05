# M0. Каркас проекта — сделано

Уже в `main`: multi-module Maven (`agent-app`, `mock-crm-mcp`, `mock-rates`), пустые пакеты
(`intake, engine, quote, llm, tools, policy, approval, outbox, audit`), `compose.yaml` (Postgres,
Mailpit, Keycloak, …), realm Keycloak, профили `azure`/`lmstudio`/`demo`, `StubChatModel`,
Testcontainers (`@IntegrationTest`), CI (`ci.yml`), `ArchitectureTest`, `.env.example`, PR-шаблон.

Остаточные хвосты, если захочется закрыть: защита ветки `main` (T0.5) — настройка в GitHub, не код.
