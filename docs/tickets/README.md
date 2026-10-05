# Тикеты и промпты реализации

Источник: учебник «AI Workflow Agent с нуля» (Claude Docs, 9 глав, ~85 ч). Здесь он разбит на тикеты
по главам; у каждого тикета есть критерии приёмки и готовый промпт для Claude Code.

| Файл | Глава | Статус |
|---|---|---|
| [M0](M0-skeleton.md) | Каркас проекта | сделано (T0.1–T0.5), в репозитории |
| [M1](M1-engine.md) | Движок workflow | T1.1–T1.2 сделаны; **следующий: T1.0, T1.3** |
| [M2](M2-intake-understand.md) | Intake и Understand | не начато |
| [M3](M3-tools.md) | MCP, Tool Gateway, approval token | сделано (T3.1–T3.6) |
| [M4](M4-pricing-policy.md) | Цена, правила, Investigator | сделано (T4.1–T4.4) |
| [M5](M5-approvals.md) | Approvals, роли, SLA, UI | сделано (T5.1–T5.6) |
| [M6](M6-respond-record.md) | Ответ, CRM, follow-up | сделано (T6.1–T6.5) |
| [M7](M7-observability-evals.md) | Наблюдаемость и evals | не начато |
| [M8](M8-packaging.md) | README, ADR, видео | не начато |

## Как пользоваться

1. Бери тикет строго по порядку внутри главы; `Зависит от` указывает, что должно быть смержено.
2. Одна ветка и один PR на тикет (или на 2–3 мелких подряд): `feature/m1-engine-core`, `feature/m2-outbox`…
3. В новой сессии Claude Code вставь **общий контекст** (ниже) и затем **промпт** тикета.
4. Тикет закрыт, когда выполнены критерии приёмки и `./mvnw -B verify` зелёный (DoD из PR-шаблона).

## Общий контекст (вставлять перед каждым промптом)

```text
Проект: ai-workflow-agent — агент превращает письма с запросом на перевозку в КП (Java 25, Spring Boot 4.1,
Spring AI 2.0, PostgreSQL 17, db-scheduler, Maven multi-module: agent-app, mock-crm-mcp, mock-rates).
Источник истины по дизайну: учебник «AI Workflow Agent с нуля» (машина состояний QuoteState:
RECEIVED, AWAIT_REPLY, UNDERSTOOD, ENRICHED, PRICED, AWAIT_APPROVAL, APPROVED, RESPONDED, FOLLOW_UP,
INVESTIGATING, CLOSED, EXCEPTION). docs/architecture.md и docs/GUIDE_RU.md частично устарели — при
расхождении прав учебник и код в quote/ и engine/.

Инварианты (проверяются тестами):
1. Поток задан кодом; LLM вызывается только в шагах Understand, Respond, Investigator и без инструментов.
2. Деньги, цены и политика — детерминированный код и YAML, никогда LLM.
3. Текст письма — недоверенные данные; он не меняет набор инструментов и правила.
4. Шаг возвращает StepResult и сам не пишет состояние и не вызывает внешние системы.
5. Любая внешняя запись идёт через ToolGateway с approval token и ключом идемпотентности.
6. Бизнес-код зависит только от абстракций Spring AI; провайдер модели — профиль.

Конвенции кода: пакет com.altronixsoft.workflow.<модуль>; только constructor injection; логи через SLF4J;
время только через инъецируемый java.time.Clock (Instant.now() запрещён ArchUnit-правилом);
JSON — Jackson 3 (tools.jackson.*); схема БД только через Flyway (следующий свободный номер V<N>),
ddl-auto=validate; форматирование — `./mvnw spotless:apply`; unit-тесты *Test, интеграционные *IT с
@IntegrationTest (Testcontainers PostgreSQL + StubChatModel; реальных вызовов модели в тестах нет);
ожидание асинхронщины — Awaitility, не Thread.sleep. Не логируй тексты писем, промпты и ответы модели.
Коммиты: conventional commits (feat(engine): …). Перед завершением запусти `./mvnw -B verify`
и покажи результат.
```

## Сводка тикетов

| Тикет | Название | Оценка |
|---|---|---|
| T1.0 | Свести docs с учебником | 0.5 ч |
| T1.3 | Персистенция, StepRegistry, StepScope, планировщик | 3 ч |
| T1.4 | Цикл движка: start/advance/apply, ретраи | 4 ч |
| T1.5 | signal() и onTimeout() | 3 ч |
| T1.6 | Тесты движка, ArchUnit для шагов, README | 3 ч |
| T2.1–T2.8 | Intake, Outbox, Understand, guards, аудит LLM | 10 ч |
| T3.1–T3.6 | mock-rates, MCP CRM, approval-token, Gateway, Enrich | 12 ч |
| T4.1–T4.4 | Pricing, Policy, Investigator, тесты | 10 ч |
| T5.1–T5.6 | Approvals, Keycloak-роли, Timeline, SLA, UI | 13 ч |
| T6.1–T6.5 | Outbox-ретраи, Respond+NumericGuard, Record, Follow-up, ScenariosIT | 8 ч |
| T7.1–T7.5 | Трейсы, метрики, стоимость, Grafana, evals | 10 ч |
| T8.1–T8.4 | README, ADR, demo-профиль, релиз | 6 ч |
