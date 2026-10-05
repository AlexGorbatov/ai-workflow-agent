# M1. Движок workflow (14 ч)

Цель: движок ведёт инстанс по цепочке шагов, переживает рестарт, повторяет упавший шаг, ждёт сигнала
или таймера. Шаги пока фейковые, LLM нет.

Сделано: T1.1 (V1/V2 миграции), T1.2 (`QuoteState`, `QuoteContext` и records, `Step`, `StepResult`, `Signal`).
Порядок: T1.0 → T1.3 → T1.4 → T1.5 → T1.6.

---

## T1.0 Свести docs с учебником (0.5 ч)

**Зачем.** `docs/architecture.md` и `docs/GUIDE_RU.md` описывают другие состояния (UNDERSTANDING,
POLICY_CHECK, NEEDS_ATTENTION, `failed_state`, IN_FLIGHT-lease), чем код и учебник. Два источника истины
сбивают при реализации.

**Приёмка**
- Диаграмма состояний и таблица переходов в `docs/architecture.md` соответствуют `QuoteState` и таблице сигналов учебника.
- В `GUIDE_RU.md` либо обновлены состояния, либо сверху стоит пометка «устарело, см. architecture.md».
- Сознательные расширения старого дизайна (lease для outbox, `failed_state`) либо перенесены в тикеты, либо удалены явно.

**Промпт**
```text
Сверь docs/architecture.md и docs/GUIDE_RU.md с кодом в agent-app/src/main/java/.../engine и quote
(QuoteState, StepResult, Signal) и таблицей сигналов из учебника: AWAIT_REPLY+CustomerReplied→RECEIVED;
AWAIT_APPROVAL+Approved→APPROVED (токен + quote с ценой approver); AWAIT_APPROVAL+Rejected→CLOSED
(closeReason=REJECTED); AWAIT_APPROVAL+Retry(from)→UNDERSTOOD/ENRICHED (error и investigation очищены);
FOLLOW_UP+CustomerReplied→CLOSED (closeReason=CUSTOMER_REPLIED). Перепиши в architecture.md диаграмму
состояний (mermaid) и описания под QuoteState из кода. В GUIDE_RU.md исправь состояния/переходы, где
они расходятся; не переписывай остальное. Выведи список сознательно убранных идей старого дизайна в
конце PR-описания. Только документация, код не трогай.
```

---

## T1.3 Персистенция, StepRegistry, StepScope, планировщик (3 ч)

**Зависит от:** T1.2. **Ветка:** `feature/m1-engine-core`.

**Приёмка**
- В `agent-app/pom.xml` добавлены `db-scheduler-spring-boot-4-starter` (версия уже в parent) и `awaitility` (test).
- JPA-сущности `WorkflowInstance` (поля из учебника §3.3, `@Version`, `context` jsonb ↔ `QuoteContext`) и `StepExecution`
  (статусы `RUNNING, SUCCEEDED, FAILED, ABANDONED`); репозитории. Контекст читается и пишется без потерь
  (roundtrip-тест через БД). `ddl-auto=validate` проходит; если для `ABANDONED`/индексов нужна миграция — это V3.
- `StepRegistry`: `Map<QuoteState, Step>` из бинов `Step`; на старте падает, если два шага заявляют одно состояние
  (тест на это обязателен). Проверка «у каждого рабочего состояния есть шаг» включается свойством
  `workflow.registry.require-all-states` (в тестах движка false).
- `StepScope` — обёртка над `ScopedValue` с `instanceId` и `stepExecutionId`.
- Событие `EnteredWaitState(instanceId, state, ctx)`.
- `SchedulerTasks`: `advance-instance` (OneTimeTask) и `wait-timeout` (OneTimeTask<String>, id = `instanceId:STATE`,
  data = ожидаемое состояние). `SchedulerClient` внедряется в движок через `ObjectProvider`, цикла бинов нет.
  Опрос планировщика в тестах ускорен (`db-scheduler.polling-interval`).
- Контекст приложения поднимается, `ApplicationSmokeIT` и ArchitectureTest зелёные.

**Промпт**
```text
Реализуй T1.3 из docs/tickets/M1-engine.md (прочти тикет и docs/architecture.md). Работаем в ветке
feature/m1-engine-core. Сначала проверь, как в Boot 4.1 / Hibernate 7 лучше хранить QuoteContext в jsonb
(Jackson 3, tools.jackson): @JdbcTypeCode(SqlTypes.JSON) с настроенным FormatMapper либо
AttributeConverter через SpringBeanContainer — выбери рабочий вариант и докажи тестом roundtrip на реальном
PostgreSQL (все поля контекста, включая BigDecimal, LocalDate, Instant). Не меняй QuoteContext.
Сделай: pom (db-scheduler starter, awaitility), сущности WorkflowInstance/StepExecution + репозитории,
StepRegistry (+ fail-fast на дубль состояния), StepScope (ScopedValue), EnteredWaitState, SchedulerTasks
(advance-instance, wait-timeout с id instanceId:STATE; SchedulerClient через ObjectProvider).
TDD: сначала падающие тесты (StepRegistryTest, WorkflowInstanceRepositoryIT, контекст поднимается с
планировщиком), потом код. Движок и цикл НЕ делай — это T1.4. Время — через Clock. Запусти
./mvnw -B verify и покажи итог.
```

---

## T1.4 Цикл движка: start / advance / apply (4 ч)

**Зависит от:** T1.3.

**Приёмка**
- `WorkflowEngine.start(businessKey, ctx)` создаёт инстанс в `RECEIVED` и ставит `advance`; повторный `start` с тем же
  `(workflow_type, businessKey)` возвращает `Optional.empty()` и не создаёт дубль (unique-ограничение).
- `advance(id)` — цикл: короткая транзакция читает снимок → **шаг выполняется вне транзакции** (с `StepScope`) →
  короткая транзакция `apply()` пишет состояние, контекст, `step_execution`. `ObjectOptimisticLockingFailureException`
  → тихий выход (другой узел уже продвинул). Терминальное или ожидающее состояние → цикл останавливается.
- `apply()` через `switch` по sealed `StepResult`: `Next` — перевод и продолжение; `Wait` — перевод, таймер
  `wait-timeout` (кроме `AWAIT_APPROVAL` — его SLA в M5) и публикация `EnteredWaitState` в той же транзакции;
  `Fail(retryable)` — повтор через `2^attempt` с до 3 попыток, затем `EXCEPTION`; нерetryable → сразу `EXCEPTION`.
  Исключение `NonRetryableStepException` превращается в `Fail(false)`, любое другое — в `Fail(true)`.
- При старте `advance` старые `RUNNING`-записи этого инстанса помечаются `ABANDONED` (kill -9).
- Транзакции — `TransactionTemplate`, не `@Transactional` на self-call.

**Промпт**
```text
Реализуй T1.4 из docs/tickets/M1-engine.md. Прочти engine/*, StepRegistry, SchedulerTasks из T1.3 и раздел
«Цикл движка» учебника (в тикете). Создай WorkflowEngine: start, advance, apply. Требования: шаг выполняется
вне транзакции (никаких @Transactional вокруг execute — используй TransactionTemplate для двух коротких
транзакций), оптимистичный конфликт = тихий выход, apply — switch по sealed StepResult без default,
ретраи через 2^attempt секунд (Clock, SchedulerClient.reschedule/scheduleIfNotExists — не schedule с тем же
id), после 3 попыток EXCEPTION, NonRetryableStepException → сразу EXCEPTION, зависшие RUNNING → ABANDONED.
Wait публикует EnteredWaitState через ApplicationEventPublisher внутри транзакции и ставит wait-timeout
(не для AWAIT_APPROVAL). signal/onTimeout пока оставь заглушками с UnsupportedOperationException (T1.5).
TDD: напиши интеграционные тесты с фейковыми шагами (тестовые бины Step): happy path RECEIVED→…→CLOSED,
дубль start, ретрай, исчерпание ретраев. Асинхронность — Awaitility.
Запусти ./mvnw -B verify, покажи итог.
```

---

## T1.5 signal() и onTimeout() (3 ч)

**Зависит от:** T1.4.

**Приёмка**
- Таблица допустимых пар «состояние + сигнал» → новое состояние и изменение контекста реализована как данные
  (одна `Map`/switch, не россыпь if):

| Состояние | Сигнал | Куда | Контекст |
|---|---|---|---|
| AWAIT_REPLY | CustomerReplied | RECEIVED | ответ в `replies` |
| AWAIT_APPROVAL | Approved | APPROVED | `approvalToken` + quote с ценой approver |
| AWAIT_APPROVAL | Rejected | CLOSED | `closeReason=REJECTED` |
| AWAIT_APPROVAL | Retry(from) | UNDERSTOOD / ENRICHED | `error` и `investigation` очищены |
| FOLLOW_UP | CustomerReplied | CLOSED | `closeReason=CUSTOMER_REPLIED` |

- `signal()` одной транзакцией: проверка пары, смена состояния и контекста, отмена таймера `instanceId:STATE`, постановка
  `advance`. Недопустимая пара → понятное исключение (в M5 станет 409), состояние не меняется.
- `onTimeout(id, expected)`: если инстанс уже не в `expected` — тихо игнорируется; иначе стандартный переход по таймауту
  (`AWAIT_REPLY`→`CLOSED(NO_RESPONSE)`; `FOLLOW_UP`/`AWAIT_APPROVAL` — оставь точкой расширения для M5/M6).
- Гонка «сигнал и таймаут одновременно» покрыта тестом.

**Промпт**
```text
Реализуй T1.5 из docs/tickets/M1-engine.md: signal() и onTimeout() в WorkflowEngine. Таблица пар
«состояние+сигнал» в тикете — реализуй как единую таблицу правил (данные), а не россыпь условий.
signal() — одна транзакция: проверить пару, обновить state и context (QuoteContext.with*), отменить таймер
instanceId:STATE через SchedulerClient, поставить advance. onTimeout(id, expected) сначала проверяет, что
инстанс всё ещё в expected, иначе игнорирует. Недопустимая пара — исключение InvalidSignalException, состояние
не меняется. TDD: тесты на каждую строку таблицы, на недопустимую пару и на гонку сигнал/таймаут
(сигнал приходит первым → таймер ничего не меняет). Используй Clock. Запусти ./mvnw -B verify.
```

---

## T1.6 Тесты движка, ArchUnit-правила для шагов, README (3 ч)

**Зависит от:** T1.5.

**Приёмка**
- 6 тестов движка из учебника зелёные: happy path, дубль start, ретрай, исчерпание ретраев, сигнал из ожидания,
  таймер после сигнала. Фейковые шаги — только в test-scope; реальные шаги будут под
  `@ConditionalOnProperty("workflow.real-steps")`.
- ArchUnit: классы, реализующие `Step`, не зависят от `..engine..WorkflowEngine`, JPA-сущностей/репозиториев,
  `JdbcTemplate`, `EntityManager`; сущности не используются из `..quote..`; `QuoteContext` и records без аннотаций JPA/Spring.
- Ручная проверка kill -9: шаг `sleep 30s`, убить процесс, перезапустить — инстанс доходит до `CLOSED`, в
  `step_execution` одна `ABANDONED` и одна `SUCCEEDED` (описать как проверить в `docs/`).
- В README абзац «How durability works» (3 предложения), статус бейджа → M1.

**Промпт**
```text
Реализуй T1.6 из docs/tickets/M1-engine.md. 1) Доведи/собери 6 тестов движка (EngineScenariosIT) на
фейковых шагах — они не должны попасть в main-код. 2) Добавь в ArchitectureTest правила для шагов: реализации
Step не зависят от WorkflowEngine, jakarta.persistence, org.springframework.jdbc, репозиториев и сущностей
engine; record-типы пакета quote не используют аннотации JPA и Spring. Правила должны реально срабатывать —
проверь, временно нарушив одно из них. 3) Напиши docs/engine-crash-check.md: пошаговая ручная проверка kill -9
с SQL-запросом по step_execution. 4) Добавь в README абзац «How durability works» (3 предложения: состояние в
БД, шаг вне транзакции + результат одной транзакцией, at-least-once + идемпотентность) и поменяй бейдж статуса.
Запусти ./mvnw -B verify, покажи итог, затем предложи PR-описание по шаблону.
```
