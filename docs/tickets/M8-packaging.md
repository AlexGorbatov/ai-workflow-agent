# M8. Упаковка: README, ADR, видео (6 ч)

**Зависит от:** M7. Порядок: T8.1 → T8.2 → T8.3 → T8.4.

---

## T8.1 Demo-профиль и скрипт (1.5 ч)

**Приёмка:** профиль `demo` ускоряет таймеры (follow-up, SLA, wait-timeout) до секунд; `scripts/demo.sh` — `docker compose up`, отправка
писем из `samples/`, подсказка, где смотреть UI/Grafana/Mailpit. «Клонировал → compose up → работает» без ключей (stub/LM Studio).

**Промпт**
```text
Реализуй T8.1 из docs/tickets/M8-packaging.md: application-demo.yml (сверь с существующим), scripts/demo.sh,
проверка на чистом клоне (git clone во временную папку, ./scripts/demo.sh, убедись, что сценарий проходит).
```

## T8.2 Четыре ADR (1.5 ч)

**Приёмка:** `docs/adr/0001…0004` (контекст, решение, альтернативы, последствия): 0001 свой движок + db-scheduler вместо Temporal;
0002 неизменяемый контекст в jsonb; 0003 LLM без инструментов + код считает деньги; 0004 outbox + approval token для внешних записей.

**Промпт**
```text
Реализуй T8.2 из docs/tickets/M8-packaging.md: четыре ADR по MADR-шаблону на русском/английском (как README — на английском),
по факту кода в репозитории, со ссылками на классы и тесты, которые обеспечивают инвариант.
```

## T8.3 README-воронка (1.5 ч)

**Приёмка:** сверху ценность и GIF (TODO(M8) убрать), быстрый старт, архитектура, таблица результатов evals (TODO(M7)),
«How durability works», ссылки на ADR, бейдж статуса → v1.0.

**Промпт**
```text
Реализуй T8.3 из docs/tickets/M8-packaging.md: перепиши README как воронку (заказчик → пробующий → инженер),
подставь результаты evals из target/evals/report.md, снимки Timeline/Approval/Grafana (сделай скриншоты через
Browser pane при запущенном demo). GIF запиши отдельно вручную — оставь плейсхолдер, если не получилось.
```

## T8.4 Релиз v1.0.0 (1.5 ч)

**Приёмка:** CHANGELOG, тег `v1.0.0`, GitHub Release с артефактами, защита `main`, зелёный CI; текст карточки Upwork в `docs/portfolio.md`.

**Промпт**
```text
Реализуй T8.4 из docs/tickets/M8-packaging.md: подготовь CHANGELOG и черновик Release notes. Не создавай тег и
релиз и не пушь без моего подтверждения — покажи команды и жди ответа. Черновик карточки Upwork — docs/portfolio.md.
```
