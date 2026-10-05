# M7. Наблюдаемость, стоимость и evals (10 ч)

**Зависит от:** M6. Порядок: T7.1 → T7.2 → T7.3 → T7.4 → T7.5.

---

## T7.1 Трассировка (2 ч)

**Приёмка:** зависимости `spring-boot-starter-opentelemetry`, `micrometer-registry-otlp`; `grafana/otel-lgtm` в compose;
`sampling.probability: 1.0` в demo/dev; span `workflow.step` на каждый шаг в движке (`Observation`), внутри него видны
span-ы модели и вызовов Gateway. В trace — `instanceId` и step; в теги метрик — нет.

**Промпт**
```text
Реализуй T7.1 из docs/tickets/M7-observability-evals.md: добавь зависимости, сервис lgtm в compose.yaml, OTLP-конфиг,
Observation вокруг step.execute в WorkflowEngine (имя workflow.step, low-cardinality: step, state, outcome;
high-cardinality: instanceId). Ручная проверка: обработай письмо, найди trace в Grafana→Tempo с вложенным span модели.
```

## T7.2 Бизнес-метрики (2 ч)

**Приёмка:** `workflow.instances` (gauge по state), `workflow.step.duration`, `llm.tokens` (model, type), `llm.cost.eur`,
`approvals.open`, `outbox.failed`, `response.fallback`. Теги — только ограниченные множества; тест проверяет отсутствие тега
`instanceId`/email.

**Промпт**
```text
Реализуй T7.2 из docs/tickets/M7-observability-evals.md: MeterBinder-ы, тест на кардинальность тегов (перебор
всех зарегистрированных meters), проверка /actuator/prometheus. Prometheus-эндпоинт откройте только если защищён
или внутренний.
```

## T7.3 CostCalculator (1 ч)

**Приёмка:** цены моделей в конфиге (`llm.pricing.<model>.input/output per 1M tokens`); `AuditAdvisor` пишет `llm_call.cost_eur` и
метрику; для локальной модели 0, но метрика есть.

**Промпт**
```text
Реализуй T7.3 из docs/tickets/M7-observability-evals.md: CostCalculator + интеграция в AuditAdvisor (из T2.7).
Тесты на расчёт, неизвестную модель (стоимость 0 + warning), округление до 6 знаков.
```

## T7.4 Дашборд Grafana (1.5 ч)

**Приёмка:** JSON в `ops/grafana/dashboards/`, provisioning в compose; панели: инстансы по состояниям, p95 длительности шагов,
токены и стоимость в день, открытые approvals, доля fallback и guard-срабатываний.

**Промпт**
```text
Реализуй T7.4 из docs/tickets/M7-observability-evals.md: dashboard JSON (PromQL по метрикам T7.2), provisioning-файлы,
монтирование в lgtm-контейнер. Проверь в браузере (Browser pane) и приложи скриншот.
```

## T7.5 Evals (3.5 ч)

**Приёмка**
- 40 вручную размеченных кейсов `src/test/resources/evals/*.yaml`: 20 обычных (разные языки), 8 неполных, 5 «не заявка», 5 injection, 2 неоднозначных клиента.
- `EvalRunnerIT` (`@Tag("eval")`, профиль `-Pevals`) прогоняет Understand → Policy (без писем и CRM), считает метрики и пороги:
  intent ≥ 0.95, поля ≥ 0.90, маршрут (auto/approval/clarify) ≥ 0.90, recall по injection = 1.0.
- Отчёт `target/evals/report.md`; `evals.yml` — раз в неделю + `workflow_dispatch`, секрет `OPENAI_API_KEY`, отчёт как artifact.
  Обычный CI evals не запускает.

**Промпт**
```text
Реализуй T7.5 из docs/tickets/M7-observability-evals.md. Датасет размечай вручную по sample-письмам и моим
правкам: сгенерируй черновик 40 кейсов в YAML и ПОМЕТЬ как draft — я проверю разметку; не придумывай эталон,
который модель сама бы выдала. EvalRunnerIT, EvalReport, пороги в конфиге, evals.yml. Проверь локально на
StubChatModel (runner работает), на реальной модели не запускай без моего ключа.
```
