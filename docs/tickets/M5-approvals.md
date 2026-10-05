# M5. Человек в контуре: approvals, роли, SLA, UI (13 ч)

**Зависит от:** M4. Порядок: T5.1 → T5.2 → T5.3 → T5.4 → T5.5 → T5.6 (UI последним).

---

## T5.1 approval_task и ApprovalTaskCreator (2 ч)

**Приёмка:** таблица `approval_task` (`unique(instance_id, kind, round)`), сущность `ApprovalTask`; синхронный слушатель
`EnteredWaitState(AWAIT_APPROVAL)` создаёт задачу: `kind` QUOTE или INVESTIGATION, `reasons` из `policy.reasons` либо
`investigation:<error>`, `dueAt = now + sla`, ставит db-scheduler-задачу `approval-sla`. Повтор события не дублирует; после `Retry`
появляется новый `round`.

**Промпт**
```text
Реализуй T5.1 из docs/tickets/M5-approvals.md: миграция approval_task, ApprovalTask (OPEN, ESCALATED, APPROVED,
REJECTED), ApprovalTaskCreator на @EventListener (не @TransactionalEventListener AFTER_COMMIT — запись должна идти в
той же транзакции, что и переход), insertIfAbsent, SLA-задача approval-sla. Тесты: создание, идемпотентность, round
после Retry. Clock везде.
```

## T5.2 Безопасность: роли из Keycloak (1.5 ч)

**Приёмка:** `JwtAuthenticationConverter` превращает `realm_access.roles` в `ROLE_operator`/`ROLE_approver`, имя из
`preferred_username`; правила: `/api/v1/approvals/**` approver, `/api/v1/instances/**` operator|approver; health публичен. Тесты со
`spring-security-test` (`jwt()`), Keycloak не нужен.

**Промпт**
```text
Реализуй T5.2 из docs/tickets/M5-approvals.md в config/SecurityConfig: конвертер ролей, method/URL-авторизация, тесты MockMvc
для 401/403/200 для каждой роли. Не ломай ApplicationSmokeIT. realm в keycloak/workflow-realm.json должен содержать роли и
двух тестовых пользователей (operator, approver) — сверь и допиши, пароли только dev-значения.
```

## T5.3 Решение approver (3 ч)

**Приёмка:** `ApprovalService.decide(taskId, Decision, jwt)`: `APPROVE` (цена = `editedPrice` или цена quote; не ниже cost →
иначе 422; выпуск токена `approvedBy`=пользователь, `maxAmount`=цена) → `Signal.Approved(token, price)`; `REJECT` →
`Signal.Rejected(comment)`; `RETRY(from)` → `Signal.Retry(from)`. Решение и сигнал — одна транзакция; повторный запрос → 409, не второй
сигнал. Эндпоинты: `GET /api/v1/approvals?status=`, `GET /{id}`, `POST /{id}/decision`.

**Промпт**
```text
Реализуй T5.3 из docs/tickets/M5-approvals.md: ApprovalController + ApprovalService + Decision DTO (@Valid), оптимистичное
обновление статуса задачи (OPEN→решено) как защита от двойного клика, цена не ниже cost, комментарий обязателен при
REJECT. Тесты MockMvc+Testcontainers: approve с правкой цены, цена ниже cost, двойной approve = 409, reject, retry,
403 для operator. Запиши .http-примеры в http/requests.http.
```

## T5.4 Timeline API (2 ч)

**Приёмка:** `GET /api/v1/instances/{id}/timeline` собирает шаги (+попытки), вызовы модели, вызовы инструментов, письма и задачи
approval; два запроса вместо N+1 (шаги → вызовы по `step_execution_id in (…)`), сборка в памяти, отсортировано по времени,
без текстов промптов (есть id/метаданные; полный текст — отдельным эндпоинтом для operator).

**Промпт**
```text
Реализуй T5.4 из docs/tickets/M5-approvals.md: TimelineService, TimelineController, DTO events. Проверь количество
SQL-запросов тестом (Hibernate Statistics или datasource-proxy). Также GET /api/v1/instances с фильтром по
состоянию и пагинацией.
```

## T5.5 SLA и резюме (1.5 ч)

**Приёмка:** задача `approval-sla`: если OPEN → `ESCALATED` и письмо руководителю через outbox. Резюме approval-задачи
(LLM, `llm`-пакет) генерируется лениво при первом открытии, кэшируется в задаче; любая ошибка → `null`, не 500.

**Промпт**
```text
Реализуй T5.5 из docs/tickets/M5-approvals.md: SlaTask (db-scheduler), эскалация, ApprovalSummarizer с
кэшем в approval_task.summary (миграция). Резюме строится из фактов, не из текста письма. Тесты: эскалация по Clock,
ничего не происходит, если задача уже решена; сбой модели → summary=null.
```

## T5.6 UI (3 ч)

**Приёмка:** статика из `agent-app` (тот же стек, что в проекте №1): экраны «Инстансы» (клиент, маршрут, сумма, состояние, возраст,
фильтр), «Timeline» (шаги, попытки, вызовы, письма, стоимость), «Approval» (резюме, поля, тарифы, breakdown, причины, правка цены,
кнопки). PKCE-логин в Keycloak. Всё через API; логика не дублируется в UI.

**Промпт**
```text
Реализуй T5.6 из docs/tickets/M5-approvals.md: тонкий UI без сборщика (vanilla JS/ESM, либо тот стек, что указан
в frontend/README.md — прочти его). Три экрана, PKCE-логин (keycloak-js или ручной), токен только в памяти. Проверь в
Browser pane: пройди сценарий approve и reject на двух письмах, приложи скриншоты в PR.
```
