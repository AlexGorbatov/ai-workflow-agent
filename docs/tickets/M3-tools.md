# M3. Инструменты: MCP, Tool Gateway, approval token (12 ч)

Цель: единственные ворота наружу. Порядок: T3.1 ∥ T3.3 → T3.2 → T3.4 → T3.5 → T3.6. **Зависит от:** M1 (StepScope), M2 (аудит).

---

## T3.1 mock-rates (1.5 ч)

**Приёмка:** `GET /rates?origin=&destination=&weightKg=` → 2–3 перевозчика, `cost = base + perKg × weightKg`,
`transitDays`; заголовок `X-Simulate: slow` (8 с) / `error` (503). Порт 8092. `MockRatesApplicationIT` проверяет оба режима.

**Промпт**
```text
Реализуй T3.1 из docs/tickets/M3-tools.md в модуле mock-rates (Spring Boot, без БД): контроллер /rates,
детерминированные тарифы по лейну (таблица в YAML), заголовок X-Simulate=slow|error, DTO CarrierRateDto
(carrier, cost BigDecimal, currency, transitDays). Тесты в существующем MockRatesApplicationIT.
```

## T3.3 Модуль approval-token (2 ч)

**Приёмка:** `ApprovalClaims(instanceId, action, maxAmount, approvedBy, expiresAt)`; `ApprovalTokens.issue/verify`
(HMAC-SHA256, формат `base64url(payload).base64url(sig)`); `verify` сравнивает подпись через `MessageDigest.isEqual`,
проверяет срок (Clock), действие и `amount ≤ maxAmount`. Секрет из конфига, без дефолта. Код общий для agent-app и
mock-crm-mcp → отдельный Maven-модуль `approval-token`.

**Промпт**
```text
Реализуй T3.3 из docs/tickets/M3-tools.md: новый maven-модуль approval-token (без Spring-зависимостей),
подключи в root pom и в agent-app/mock-crm-mcp. ApprovalClaims, ApprovalTokens(secret, Clock) с issue/verify.
Тесты: подпись подделана, истёк срок, не то действие, amount>maxAmount, constant-time сравнение
(MessageDigest.isEqual), roundtrip. Секрет — свойство approval.token.secret без значения по умолчанию.
```

## T3.2 MCP-сервер CRM (3 ч)

**Приёмка:** `mock-crm-mcp` (порт 8091, MCP Streamable HTTP) с `@Tool`-методами: `findCustomersByEmail`,
`getCreditStatus`, `createOpportunity(customerId, amount, currency, approvalToken, idempotencyKey)` — токен проверяется
(`verify(token,"createOpportunity",amount)`), повтор с тем же ключом возвращает ту же сделку. Сид-данные под сценарии:
GOLD, STANDARD, клиент с просрочкой, два клиента на одном домене.

**Промпт**
```text
Реализуй T3.2 из docs/tickets/M3-tools.md в модуле mock-crm-mcp: Spring AI MCP server (streamable HTTP),
CrmTools с @Tool-методами, ToolCallbackProvider-бин, in-memory данные (сид из YAML) под сценарии samples/emails,
createOpportunity проверяет approval-токен и идемпотентна по idempotencyKey. Тесты в MockCrmApplicationIT
через MCP-клиент: поиск, просрочка, два клиента на домене, createOpportunity без/с токеном, повтор ключа.
```

## T3.4 MCP-клиент и ToolRegistry (1.5 ч)

**Приёмка:** клиент подключается к `http://localhost:8091`, `ToolRegistry` собирает инструменты CRM и `getRates`
(`FunctionToolCallback` поверх RestClient), снаружи пакета `tools` реестр не виден (ArchUnit).

**Промпт**
```text
Реализуй T3.4 из docs/tickets/M3-tools.md в пакете tools: конфигурация MCP-клиента Spring AI, обёртка getRates,
ToolRegistry (name → ToolCallback), package-private. Тест с Testcontainers/реальными mock-модулями или
WireMock-аналогом: registry содержит все 4 инструмента. Проверь/дополни ArchUnit: io.modelcontextprotocol и
RestClient — только в tools.
```

## T3.5 ToolGateway (3 ч)

**Приёмка:** `call(tool, args, CallContext)` делает 5 проверок: (1) инструмент разрешён в состоянии
(`ToolPolicy.allowedIn`), (2) WRITE только с approval token, (3) валидация аргументов по JSON-схеме, (4) таймаут по
политике, (5) аудит каждого вызова, включая отказы, в `tool_call` (миграция). Ключ идемпотентности: для WRITE —
аргумент `idempotencyKey`, для READ — `instanceId:tool`. `readOnlyCallbacks(cc, maxCalls)` — только READ, каждый вызов
идёт через `call()`, после бюджета возвращается строка «budget exceeded», а не исключение. Отказы — `ToolDenied`.

**Промпт**
```text
Реализуй T3.5 из docs/tickets/M3-tools.md: ToolGateway, ToolPolicy(kind READ|WRITE, allowedIn, timeout) из
YAML, CallContext(instanceId, state, approvalToken), ToolDenied, audit.record → tool_call (миграция, сущность).
Пять проверок строго в порядке из тикета; таймаут через виртуальные потоки/StructuredTaskScope или Future с
отменой. readOnlyCallbacks(cc, maxCalls) для Investigator. TDD-таблица: tool в запрещённом состоянии, WRITE
без токена, невалидные аргументы, таймаут, успешный READ, отказ тоже в tool_call, бюджет 5 из 7 вызовов.
```

## T3.6 Шаг Enrich (1 ч)

**Приёмка:** `findCustomersByEmail(ctx.email().from())`: 0 → флаг `NEW_CUSTOMER`; 1 → `Customer`; >1 →
`Next(INVESTIGATING, ctx.withError("AMBIGUOUS_CUSTOMER"))`; найденному — `getCreditStatus`, при просрочке флаг
`CREDIT_HOLD`. Шаг ходит только через ToolGateway.

**Промпт**
```text
Реализуй T3.6 из docs/tickets/M3-tools.md: EnrichStep (handles()=UNDERSTOOD → Next(ENRICHED)), строго через
ToolGateway. Тесты на 4 исхода (новый, найден, двое на домене, просрочка) против реального mock-crm-mcp
(Testcontainers GenericContainer либо запуск модуля в тесте).
```
