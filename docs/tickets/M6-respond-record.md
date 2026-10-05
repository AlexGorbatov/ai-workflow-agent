# M6. Ответ клиенту, запись в CRM, follow-up (8 ч)

**Зависит от:** M5. Порядок: T6.1 → T6.2 → T6.3 → T6.4 → T6.5.

---

## T6.1 Outbox: ретраи и backoff (1 ч)

**Приёмка:** миграция (счётчик попыток, `next_attempt_at`, `last_error`); relay берёт пачку `for update skip locked`, при ошибке
`attempts++` и сдвиг `next_attempt_at` по backoff; после 5 попыток `FAILED` + метрика `outbox.failed`. Тесты на сбой SMTP.

**Промпт**
```text
Реализуй T6.1 из docs/tickets/M6-respond-record.md: доработай OutboxRelay из M2 — экспоненциальный backoff через
Clock, 5 попыток, FAILED, метрика (Micrometer counter outbox.failed). IT: остановленный Mailpit → ретраи, затем
восстановление → письмо ушло ровно один раз.
```

## T6.2 RespondStep и NumericGuard (3 ч)

**Приёмка**
- `QuoteFacts.from(ctx)` — только то, что можно сказать клиенту (никаких `cost`, `marginPct`).
- Промпты `respond-v1` и `respond-strict`; ответ — `ReplyDraft(subject, body)`.
- `NumericGuard.check(body, facts)`: суммы рядом с €/EUR/евро/євро нормализуются (`1 210,00` → `1210.00`), каждая обязана быть `price`
  (`compareTo==0`); каждая дата — `validUntil` или `pickupDate`; возвращает список нарушений.
- Лестница: v1 → guard → strict → guard → шаблон `templates/quote_{lang}.txt` + флаг `RESPONSE_FALLBACK_TEMPLATE`.
  Message-ID генерируется, письмо в outbox, `Next(RESPONDED, withOutboundMessageId)`; шаг письма не отправляет.

**Промпт**
```text
Реализуй T6.2 из docs/tickets/M6-respond-record.md: QuoteFacts, NumericGuard (табличные тесты: чужая сумма, скидка в
процентах, формат 1 210,00 €, чужая дата, нет сумм, разные языки), RespondStep с лестницей v1 → strict → шаблон,
шаблоны на en/de/pl/ru/uk. Модель никогда не получает cost/margin. Тест «модель выдумала цену» на StubChatModel:
итоговое письмо — шаблон с флагом.
```

## T6.3 RecordStep (1 ч)

**Приёмка:** `customer == null` → флаг `CRM_SKIPPED_NEW_CUSTOMER` и дальше; иначе `createOpportunity` через Gateway
(WRITE, approval token, ключ `instanceId:opportunity`); затем `Wait(FOLLOW_UP, 72h)`. Повтор шага не создаёт вторую сделку.

**Промпт**
```text
Реализуй T6.3 из docs/tickets/M6-respond-record.md: RecordStep (handles()=RESPONDED). Тест на повтор шага (ключ
идемпотентности) против mock-crm: ровно одна сделка.
```

## T6.4 FollowUpHandler (1 ч)

**Приёмка:** первый таймаут `FOLLOW_UP` → одно напоминание через outbox (шаблон, без LLM) и ещё одно ожидание; второй → `CLOSED`
(`NO_RESPONSE`); ответ клиента → `CLOSED(CUSTOMER_REPLIED)` (уже в T1.5), разбирает человек. Расширь `onTimeout` из M1.

**Промпт**
```text
Реализуй T6.4 из docs/tickets/M6-respond-record.md: счётчик напоминаний в контексте (флаг FOLLOW_UP_SENT), обработка
таймаута в WorkflowEngine.onTimeout для FOLLOW_UP. Тесты с управляемым Clock: напоминание, закрытие, ответ до таймаута.
```

## T6.5 ScenariosIT — 5 сквозных сценариев (2 ч)

**Приёмка:** Testcontainers (Postgres, Mailpit) + mock-модули + StubChatModel + Awaitility. Сценарии: (1) GOLD с полными данными
→ автоцена, письмо в Mailpit, сделка в CRM; (2) новый клиент без веса → уточнение → ответ → approval → письмо,
`CRM_SKIPPED_NEW_CUSTOMER`; (3) prompt injection → `SUSPICIOUS_INSTRUCTIONS`, approval, без скидок в ответе; (4) модель выдумала
цену → guard → strict → шаблон; (5) клиент молчит → follow-up → `CLOSED/NO_RESPONSE`.

**Промпт**
```text
Реализуй T6.5 из docs/tickets/M6-respond-record.md: ScenariosIT с 5 сценариями из тикета. Включи
workflow.real-steps=true. Используй sample-письма. Для approval дергай ApprovalService напрямую или REST с jwt().
Время — управляемый Clock/ускоренные таймеры. Тест должен быть стабильным: прогони 5 раз подряд.
```
