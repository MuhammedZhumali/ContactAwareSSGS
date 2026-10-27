# PC2: допустимый исходный план и сохранение продолжения

PC2 меняет выбор ограниченного контроллера, сохраняя пять критериев, допустимые действия, costs и constraints. Итоговые восемь практических конфигураций состоят из четырёх offline v2 и четырёх corrected measured v4. Сохранены все 23 фактических попытки: исходные16, отдельная техническая ошибка checkpoint и новая зарегистрированная measured-серия. Автоматических повторов и повышения бюджетов нет; один технический повтор описан отдельно.

## Причины и алгоритмическое исправление

PC1 принимал найденный терминальный incumbent, используя bootstrap только при отсутствии такого плана; плохой WAIT мог вытеснить доступное обслуживание. Сохранение ограниченного продолжения заканчивалось после служебного действия, поэтому новый поиск мог потерять найденный более дешёвый вариант. Для post-PREPARE есть сохранённое сравнение двух допустимых продолжений из одного корня. Для ранних WAIT новые альтернативы проверены отдельно в PC2; их оценки не приписываются задним числом PC1. См. [фикстуры и границы свидетельств](fixtures-note.md).

`FeasibleSeedPlanBuilder` создаёт общий допустимый исходный план. `PracticalPlanPortfolio` сравнивает seed, заново исполненный остаток, его дополнение до расширенного горизонта и терминальный результат DFS из одного корня до одной evaluationEnd. DFS не получает новых отсечений или иной целевой функции. Старые score/costs не переносятся; проверяются время, состояние, counters, progress, pending work, uncertainty, IDs и видимый прогноз. [Контракт](contract.md) описывает допуск и инвалидацию.

Гарантия локальная: завершённое решение выбирает лучший полностью оценённый допустимый кандидат по пяти критериям и полному tie-break. Это не доказательство оптимальности ограниченного поиска или превосходства Multi на всей миссии. SAFE_FALLBACK, прекращённая работа и сертифицированный точный suffix выделяются отдельно.

## Три сохранённых корня: абляция

Порядок метрик: контакты / полезные секунды / CPU операций в секундах / management bytes / новые directional keys.

| Корень | PC1 | + seed | + seed и retained |
|---|---|---|---|
| post-prepare-known-retained-winner | 2 / 100 / 1.93 / 3240 / 4 | 2 / 100 / 1.22 / 2160 / 2 | 2 / 100 / 1.22 / 2160 / 2 (RETAINED) |
| iss46-earliest-skipped-contact | 1 / 20 / 1.31 / 1080 / 8 | 3 / 60 / 2.13 / 3240 / 6 | 3 / 60 / 2.13 / 3240 / 6 (SEED) |
| demand-none-disadvantageous-wait | 2 / 40 / 1.42 / 2160 / 4 | 3 / 410 / 2.13 / 3240 / 6 | 3 / 410 / 2.13 / 3240 / 6 (SEED) |

По сохранённым оценкам seed улучшает лексикографический результат по пяти метрикам относительно PC1 в 3/3 корнях; retention дополнительно улучшает его относительно seed в 0/3. Полный tie-break может выбирать другой план при равных метриках. Во всех вариантах использован один сохранённый терминальный результат PC1, чтобы отделить seed/retention от wall-time вариаций нового DFS. Планы заново исполнены до endpoint; это отдельное свидетельство, а не сертификат оптимальности.

## Исправление measured: просроченный WAIT и новое решение

В исходном measured v2 подтверждён дефект временной проверки: расчёт WAIT мог закончиться после события, до которого он был выбран. Проверка `sameIntent` игнорировала эффективную точку пробуждения и сопоставляла такой WAIT с более поздним WAIT до LOS. Это теряло оставшуюся часть окна после уже завершённого SWITCH. Причина установлена по сохранённым observation/action/availableAt, а не только по числу пропусков.

Конкретный пример: корень `2026-10-10T04:42:14.021522789Z`, ожидаемое пробуждение `2026-10-10T04:42:15.021522789Z`, результат доступен `2026-10-10T04:42:15.038813189Z`, исполненный WAIT до `2026-10-10T04:45:16.919403076Z`. Источники: [WAIT-свидетельства](../../../target/practical-controller-pc2/iss46-v1/runs/07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION/elapsed-wait-targets.json) и [измеренные решения](../../../target/practical-controller-pc2/iss46-v1/runs/07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION/latency-decisions.json).

Узкая коррекция v4: прошедшее или исчезнувшее событие WAIT не заменяется более поздним WAIT. Исчезновение прежнего wake, например после прерывания INSTALL, требует WAIT_EVENT_CHANGED_REPLAN; наступившее событие — WAIT_EVENT_REACHED_REPLAN. Контроллер заново наблюдает состояние и принимает решение; другие устаревшие намерения после сдвига физического времени также возвращаются к проверке через новое решение. Начатая работа сохраняется. Повторные observation/generation/selection оплачиваются заново в том же измеренном режиме; это не бесплатный поиск и не увеличение бюджета. Модель, objective, comparator и пространство кандидатов не меняются.

[Регистрация коррекции](latency-correction-registration.json) заранее ограничивает повторную серию четырьмя measured Next/Multi и двумя measured controls. Исходные measured результаты остаются ниже. Offline не перезапускался: исправляется measured temporal-admission path. Поэтому сравнение offline v2 с measured v4 включает физическую задержку и явную версию этой проверки; это два разных JAR.

## Версии и единые лимиты

| Назначение | JAR | SHA-256 | Регистрация |
|---|---|---|---|
| Исходные16, включая итоговые offline | [candidate-v2.jar](../../../target/practical-controller-pc2/candidate-v2.jar) | `2b5540d6144abb02b18a9bef26015dab56e5edaf7ad48405550012fd30c9c71d` | [iss46-v1](../../../target/practical-controller-pc2/iss46-v1/protocol.json) |
| Исправленные measured6 | [candidate-v4.jar](../../../target/practical-controller-pc2/candidate-v4.jar) | `0c6f2d7de5b87a6c7e43d5565a5f76d7f41630ae57b6b0452301bf1d07d4f6f9` | [measured-v4b](../../../target/practical-controller-pc2/measured-v4b/protocol.json) |

Model hash `eacfa95e4b90c9416c38f8b16006481743e56ff8bcdfcab404b6bd50bab44a08`; контактов в расписании: 46. Лимиты: 5000 узлов / 1000 мс на поиск; 2000 мс на решение; seed/replay по 350/350 мс внутри deadline; 600 с process watchdog. Controller limit 0 означает отсутствие накопительного лимита. Fixed=0, host-scale=1 в measured. Process watchdog не расширяет бюджет решения.

## Итоговые восемь практических исполнений: PC1 → PC2

| Версия / режим | Правило / H или контроль | Статус | PC1 контакты | Контакты | Полезные с | CPU операций с | Management B | Ключи | Процесс с | Misses | Попытка |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| v2 / offline | CANONICAL / H1 | FINISHED | 40 | 46 | 920 | 32.66 | 49680 | 92 | 282.015 | 0 | [01-offline-practical-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/01-offline-practical-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v2 / offline | CANONICAL / H2 | FINISHED | 1 | 46 | 920 | 32.66 | 49680 | 92 | 560.782 | 0 | [02-offline-practical-H2-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/02-offline-practical-H2-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v2 / offline | CONTINUE_WAIT / H1 | FINISHED | 1 | 46 | 920 | 32.66 | 49680 | 92 | 113.782 | 0 | [03-offline-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/03-offline-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) |
| v2 / offline | CONTINUE_WAIT / H2 | FINISHED | 1 | 46 | 920 | 32.66 | 49680 | 92 | 384.625 | 0 | [04-offline-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/04-offline-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) |
| v4 / measured | CANONICAL / H1 | FINISHED | 30 | 46 | 920 | 32.66 | 49680 | 92 | 336.610 | 0 | [07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v4 / measured | CANONICAL / H2 | FINISHED | 1 | 46 | 920 | 32.66 | 49680 | 92 | 593.250 | 0 | [08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v4 / measured | CONTINUE_WAIT / H1 | FINISHED | 1 | 46 | 920 | 32.66 | 49680 | 92 | 129.875 | 0 | [09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) |
| v4 / measured | CONTINUE_WAIT / H2 | FINISHED | 1 | 46 | 920 | 32.66 | 49680 | 92 | 417.407 | 0 | [10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) |

Фактический итог этих строк: FINISHED 8/8; все 46 контактов обслужены в 8/8 завершённых исполнений. FINISHED означает конец физического расписания, а не COMPLETE каждого поиска. Строки PARTIAL показывают только накопленные значения сохранённого префикса, если доступны; им не назначаются финальные показатели или миссионный проигрыш.

- offline v2, CANONICAL: H1 и H2 совпали по всем пяти миссионным метрикам в этих зарегистрированных исполнениях.
- offline v2, CONTINUE_WAIT: H1 и H2 совпали по всем пяти миссионным метрикам в этих зарегистрированных исполнениях.
- measured v4, CANONICAL: H1 и H2 совпали по всем пяти миссионным метрикам в этих зарегистрированных исполнениях.
- measured v4, CONTINUE_WAIT: H1 и H2 совпали по всем пяти миссионным метрикам в этих зарегистрированных исполнениях.

Это единичные исполнения детерминированной физической модели на данном хосте. Wall-time остановки поиска и физические decision points зависят от времени вычислений; результаты не являются статистической выборкой или бортовой калибровкой.

## Контроли: offline v2 и corrected measured v4

FixedPeriod: T=3600 с от успешного SWITCH; PerContact: JUST_IN_TIME. Measured controls оплачивают наблюдение, генерацию и собственное правило выбора, без фиктивной стоимости DFS.

| Версия / режим | Правило / H или контроль | Статус | PC1 контакты | Контакты | Полезные с | CPU операций с | Management B | Ключи | Процесс с | Misses | Попытка |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| v2 / offline | fixed | FINISHED | — | 46 | 920 | 35.66 | 49680 | 122 | 22.640 | 0 | [05-offline-fixed-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/05-offline-fixed-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v2 / offline | percontact | FINISHED | — | 46 | 920 | 32.66 | 49680 | 92 | 18.609 | 0 | [06-offline-percontact-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/06-offline-percontact-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v4 / measured | fixed | FINISHED | — | 46 | 920 | 35.66 | 49680 | 122 | 15.593 | 0 | [11-measured-fixed-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/11-measured-fixed-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v4 / measured | percontact | FINISHED | — | 46 | 920 | 32.66 | 49680 | 92 | 12.094 | 0 | [12-measured-percontact-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/12-measured-percontact-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |

## Четыре исходные offline-диагностики только seed

| Версия / режим | Правило / H или контроль | Статус | PC1 контакты | Контакты | Полезные с | CPU операций с | Management B | Ключи | Процесс с | Misses | Попытка |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| v2 / offline | CANONICAL / H1 | FINISHED | — | 46 | 920 | 32.66 | 49680 | 92 | 19.563 | 0 | [13-offline-practical-H1-CANONICAL-SEED_WITHOUT_SEARCH](../../../target/practical-controller-pc2/iss46-v1/runs/13-offline-practical-H1-CANONICAL-SEED_WITHOUT_SEARCH/invocation.json) |
| v2 / offline | CANONICAL / H2 | FINISHED | — | 46 | 920 | 32.66 | 49680 | 92 | 27.281 | 0 | [14-offline-practical-H2-CANONICAL-SEED_WITHOUT_SEARCH](../../../target/practical-controller-pc2/iss46-v1/runs/14-offline-practical-H2-CANONICAL-SEED_WITHOUT_SEARCH/invocation.json) |
| v2 / offline | CONTINUE_WAIT / H1 | FINISHED | — | 46 | 920 | 32.66 | 49680 | 92 | 19.547 | 0 | [15-offline-practical-H1-CONTINUE_WAIT-SEED_WITHOUT_SEARCH](../../../target/practical-controller-pc2/iss46-v1/runs/15-offline-practical-H1-CONTINUE_WAIT-SEED_WITHOUT_SEARCH/invocation.json) |
| v2 / offline | CONTINUE_WAIT / H2 | FINISHED | — | 46 | 920 | 32.66 | 49680 | 92 | 31.250 | 0 | [16-offline-practical-H2-CONTINUE_WAIT-SEED_WITHOUT_SEARCH](../../../target/practical-controller-pc2/iss46-v1/runs/16-offline-practical-H2-CONTINUE_WAIT-SEED_WITHOUT_SEARCH/invocation.json) |

Это заранее зарегистрированная SEED_WITHOUT_SEARCH-серия v2. Она не подменяет абляцию одного корня и не расширяет сетку спутников, атак или параметров.

## Все шесть исходных measured v2: сохранены отдельно

Следующие результаты входят в исходные16. Они не удалены и не смешаны с corrected measured v4 в итоговой восьмёрке. Коррекция не превращает исходные результаты в другие значения.

| Версия / режим | Правило / H или контроль | Статус | PC1 контакты | Контакты | Полезные с | CPU операций с | Management B | Ключи | Процесс с | Misses | Попытка |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| v2 / measured | CANONICAL / H1 | FINISHED | — | 15 | 300 | 25.47 | 38360 | 78 | 230.656 | 0 | [07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v2 / measured | CANONICAL / H2 | FINISHED | — | 3 | 60 | 28.1 | 42800 | 86 | 375.266 | 0 | [08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v2 / measured | CONTINUE_WAIT / H1 | FINISHED | — | 45 | 900 | 32.6 | 49600 | 92 | 114.906 | 0 | [09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) |
| v2 / measured | CONTINUE_WAIT / H2 | FINISHED | — | 33 | 670 | 30.25 | 45800 | 88 | 206.265 | 0 | [10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) |
| v2 / measured | fixed | FINISHED | — | 46 | 920 | 35.66 | 49680 | 122 | 12.906 | 0 | [11-measured-fixed-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/11-measured-fixed-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |
| v2 / measured | percontact | FINISHED | — | 23 | 460 | 17.93 | 24840 | 62 | 7.000 | 0 | [12-measured-percontact-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/12-measured-percontact-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |

## Отдельный PROCESS_ERROR и ограниченный технический повтор

Первый запуск candidate-v4 остановился при atomic rename checkpoint с AccessDeniedException и ACCESS_DENIED_TIME_EXHAUSTED. Последний корректный JSON читается, снимок и диагностический журнал сохранены. Причина блокировки не установлена. Пять следующих конфигураций той регистрации не запускались из-за stop-on-PROCESS_ERROR.

[Отдельная регистрация](technical-repeat-registration.json) разрешила ровно один повтор этой технически прерванной конфигурации и первые исполнения пяти оставшихся с тем же JAR, входами и бюджетами. Живой checkpoint во второй серии не читался; это ограничение процедуры, а не доказательство причины прежней блокировки. Retry policy и production-код checkpoint не менялись. При повторном PROCESS_ERROR дальнейших повторов нет.

| Версия / режим | Правило / H или контроль | Статус | PC1 контакты | Контакты | Полезные с | CPU операций с | Management B | Ключи | Процесс с | Misses | Попытка |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| v4 / measured | CANONICAL / H1 | PARTIAL (PROCESS_ERROR) | — | 11 | 220 | 8.72 | 12960 | 24 | 36.516 | — | [07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4/runs/07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) |

## Повторные measured-решения v4 и оплаченная задержка

| Попытка | WAIT_EVENT_REACHED_REPLAN | WAIT_EVENT_CHANGED_REPLAN | STALE_INTENT_REPLAN | Решений всего | Host time отклонённых решений, с | Их modeled delay, с |
|---|---:|---:|---:|---:|---:|---:|
| [07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) | 50 | 0 | 0 | 639 | 50.072 | 50.0722121 |
| [08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION/invocation.json) | 125 | 0 | 0 | 637 | 127.517 | 127.5170919 |
| [09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) | 4 | 0 | 0 | 639 | 4.096 | 4.0959669 |
| [10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) | 73 | 0 | 0 | 639 | 76.654 | 76.6544013 |
| [11-measured-fixed-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/11-measured-fixed-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) | 2 | 0 | 0 | 713 | 0.005 | 0.0048464 |
| [12-measured-percontact-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/12-measured-percontact-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) | 26 | 0 | 0 | 639 | 0.042 | 0.0417364 |

Суммированы уже оплаченные решения с replan по `latency-decisions.json`. Следующие решения имеют собственные записи и задержку; все они входят в физическое исполнение. Host time/modelled delay — время, не operation CPU или thread CPU. Отсутствующий журнал не считается нулевым расходом.

## Работа поиска, источники выбора и CPU

| Версия / правило / H | Seed | Retained | Search | Fallback | Точные suffix | Поиски | Узлы | Seed с | Replay с | Search с | Portfolio thread CPU с | Search thread CPU с | Переходы |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| v2 / CANONICAL / H1 (FINISHED) | 99 | 52 | 421 | 0 | 52 | 572 | 1061159 | 9.276 | 8.686 | 253.764 | 265.625 | 247.641 | 1080815 |
| v2 / CANONICAL / H2 (FINISHED) | 180 | 174 | 261 | 0 | 3 | 615 | 2319832 | 16.925 | 16.673 | 516.916 | 537.328 | 504.078 | 2358707 |
| v2 / CONTINUE_WAIT / H1 (FINISHED) | 25 | 0 | 524 | 0 | 88 | 549 | 389043 | 8.749 | 7.865 | 86.689 | 101.500 | 84.672 | 407184 |
| v2 / CONTINUE_WAIT / H2 (FINISHED) | 170 | 55 | 347 | 0 | 57 | 572 | 802561 | 27.388 | 26.631 | 314.507 | 358.750 | 305.297 | 836141 |
| v4 / CANONICAL / H1 (FINISHED) | 125 | 8 | 506 | 0 | 0 | 639 | 1111935 | 10.774 | 4.462 | 281.522 | 291.500 | 275.734 | 1128457 |
| v4 / CANONICAL / H2 (FINISHED) | 529 | 20 | 88 | 0 | 0 | 637 | 2243319 | 18.000 | 5.394 | 556.866 | 566.391 | 543.406 | 2270456 |
| v4 / CONTINUE_WAIT / H1 (FINISHED) | 25 | 0 | 614 | 0 | 0 | 639 | 376219 | 10.951 | 5.753 | 99.491 | 114.984 | 97.828 | 392538 |
| v4 / CONTINUE_WAIT / H2 (FINISHED) | 312 | 23 | 304 | 0 | 0 | 639 | 1354166 | 16.537 | 6.504 | 382.457 | 400.516 | 377.188 | 1381701 |

Источник обозначает завершённый выбор на исходном observation. В measured он ещё может быть отклонён после задержки. `practical-portfolio.json` содержит кандидаты/планы/метрики, `practical-portfolio-stops.json` — прекращённую работу. Счётчики завершённых решений не выдаются за полный расход при наличии остановок. Search thread CPU входит в portfolio thread CPU и не суммируется с ним. Portfolio CPU не включает внешние observation/admission; whole-run CPU не называется planning-only CPU. Model operation CPU в таблицах исходов — другой показатель.

| Итоговая попытка | Причины retention | Причины остановки DFS | Прекращённых portfolio-вызовов | Selection wall с | Whole-run thread CPU с | Неиспользованные новые ключи / из них retired |
|---|---|---|---:|---:|---:|---:|
| [01-offline-practical-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/01-offline-practical-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) | FEASIBLE_RETAINED: 540; NO_RETAINED_PLAN: 1; REJECTED_STALE_TIME: 31 | NODE_LIMIT: 72; NONE: 317; TIME_LIMIT: 183 | 0 | 272.454 | 273.656 | 0 / 0 |
| [02-offline-practical-H2-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/02-offline-practical-H2-CANONICAL-SEED_AND_RETENTION/invocation.json) | FEASIBLE_RETAINED: 613; NO_RETAINED_PLAN: 1; REJECTED_STALE_TIME: 1 | NODE_LIMIT: 234; NONE: 20; TIME_LIMIT: 361 | 0 | 551.289 | 545.234 | 0 / 0 |
| [03-offline-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/03-offline-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) | FEASIBLE_RETAINED: 505; NO_RETAINED_PLAN: 1; REJECTED_STALE_TIME: 43 | NODE_LIMIT: 6; NONE: 521; TIME_LIMIT: 22 | 0 | 104.003 | 109.734 | 0 / 0 |
| [04-offline-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/iss46-v1/runs/04-offline-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) | FEASIBLE_RETAINED: 543; NO_RETAINED_PLAN: 1; REJECTED_STALE_TIME: 28 | NODE_LIMIT: 15; NONE: 309; TIME_LIMIT: 248 | 0 | 369.740 | 371.375 | 0 / 0 |
| [07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION/invocation.json) | FEASIBLE_RETAINED: 312; NO_RETAINED_PLAN: 1; REJECTED_COMMITTED_WORK_OR_UNCERTAINTY: 18; REJECTED_STALE_TIME: 308 | NODE_LIMIT: 60; NONE: 363; TIME_LIMIT: 216 | 0 | 297.757 | 301.969 | 0 / 0 |
| [08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION/invocation.json) | FEASIBLE_RETAINED: 235; NO_RETAINED_PLAN: 1; REJECTED_COMMITTED_WORK_OR_UNCERTAINTY: 1; REJECTED_STALE_TIME: 400 | NODE_LIMIT: 200; NONE: 21; TIME_LIMIT: 416 | 0 | 581.109 | 576.609 | 0 / 0 |
| [09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) | FEASIBLE_RETAINED: 358; NO_RETAINED_PLAN: 1; REJECTED_STALE_TIME: 280 | NONE: 610; TIME_LIMIT: 29 | 0 | 117.132 | 125.875 | 0 / 0 |
| [10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION](../../../target/practical-controller-pc2/measured-v4b/runs/10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION/invocation.json) | FEASIBLE_RETAINED: 289; NO_RETAINED_PLAN: 1; REJECTED_STALE_TIME: 349 | NODE_LIMIT: 30; NONE: 294; TIME_LIMIT: 315 | 0 | 406.274 | 410.094 | 0 / 0 |

`searches` берётся из полного ledger попыток, не из исторического `computation.searches`, который считает лишь явно возвращённые SEARCH-решения. Rollout/prediction transitions учитываются отдельно от узлов DFS. Retired означает DEACTIVATED/DESTROYED в конце сохранённого состояния, не аппаратное стирание.

## Причины пропусков и точные журналы

| Серия / попытка | Статус | Контактов ниже порога в сохранённых данных | Сохранённые причины | Свидетельства |
|---|---|---:|---|---|
| iss46-v1 / 07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION | FINISHED | 31 | просроченный WAIT переназначен до LOS: 31 | [контакты и решения](../../../target/practical-controller-pc2/iss46-v1/missed-contacts.json); [actual events](../../../target/practical-controller-pc2/iss46-v1/runs/07-measured-practical-H1-CANONICAL-SEED_AND_RETENTION/actual.events.json) |
| iss46-v1 / 08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION | FINISHED | 43 | просроченный WAIT переназначен до LOS: 43 | [контакты и решения](../../../target/practical-controller-pc2/iss46-v1/missed-contacts.json); [actual events](../../../target/practical-controller-pc2/iss46-v1/runs/08-measured-practical-H2-CANONICAL-SEED_AND_RETENTION/actual.events.json) |
| iss46-v1 / 09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION | FINISHED | 1 | просроченный WAIT переназначен до LOS: 1 | [контакты и решения](../../../target/practical-controller-pc2/iss46-v1/missed-contacts.json); [actual events](../../../target/practical-controller-pc2/iss46-v1/runs/09-measured-practical-H1-CONTINUE_WAIT-SEED_AND_RETENTION/actual.events.json) |
| iss46-v1 / 10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION | FINISHED | 13 | setup/доставка ниже порогов: 1; просроченный WAIT переназначен до LOS: 12 | [контакты и решения](../../../target/practical-controller-pc2/iss46-v1/missed-contacts.json); [actual events](../../../target/practical-controller-pc2/iss46-v1/runs/10-measured-practical-H2-CONTINUE_WAIT-SEED_AND_RETENTION/actual.events.json) |
| iss46-v1 / 12-measured-percontact-H1-CANONICAL-SEED_AND_RETENTION | FINISHED | 23 | просроченный WAIT переназначен до LOS: 23 | [контакты и решения](../../../target/practical-controller-pc2/iss46-v1/missed-contacts.json); [actual events](../../../target/practical-controller-pc2/iss46-v1/runs/12-measured-percontact-H1-CANONICAL-SEED_AND_RETENTION/actual.events.json) |

Причины берутся из actual decisions/events, а не угадываются по score. Только явное `ELAPSED_WAIT_RETARGETED_TO_CONTACT_END` подтверждает temporal-intent механизм. Другие категории описывают отсутствие setup или недостаточную доставку и не доказывают контрфактическую возможность обслуживания. Для PARTIAL список может включать ещё не достигнутые окна; это не доказанное число окончательных потерь.

Отдельно разобран исходный measured H2/CONTINUE_WAIT, контакт SATNOGS-4451-2. В 10:29:24.586833081 seed начинался INSTALL, retained — WAIT до10:31:37.456954239; оба оценены (3,60,1.93,3240,4), поэтому неизменный полный CONTINUE_WAIT выбрал retained. Это намеренный WAIT, а не temporal-retarget ошибка. Номинальный второй кадр завершался10:32:03.456954239 до LOS10:32:05.270233154. После реальных вычислений OPEN завершился10:31:46.995478439; фактически доставлен только uplink в10:31:57.101603739, второго полного10-секундного кадра уже не вместить. Это свидетельство ограничения номинального прогноза при measured задержках; objective не исправлялась задним числом. [Корень, кандидаты и фактическая последовательность](../../../target/practical-controller-pc2/partial-directional-contact-diagnosis.json).

## Проверки и воспроизведение

| Изолированная сборка | Tests | Классов | Failures | Errors | Exit code | Источник |
|---|---:|---:|---:|---:|---:|---|
| candidate-v2 | 121 | 14 | 0 | 0 | 0 | [tests/command](../../../target/practical-controller-pc2/candidate-v2-tests.json) |
| candidate-v3 | 124 | 15 | 3 | 0 | 1 | [tests/command](../../../target/practical-controller-pc2/candidate-v3-tests.json) |
| candidate-v4 | 124 | 15 | 0 | 0 | 0 | [tests/command](../../../target/practical-controller-pc2/candidate-v4-tests.json) |

Числа читаются из сохранённых tests JSON, включая candidate-v4; пересекающиеся наборы не суммируются и не выдаются за полный набор проекта. Candidate-v3 сохранён как неудачная сборка: три новых теста не включили обязательный STOP при закрытии контакта в ожидаемый журнал. В v4 исправлены только эти ожидания; production-код v3/v4 совпадает. Регрессии охватывают full-DFS oracle, actual цепочку операций/кадров, endpoint growth, инвалидацию, скрытый C3 с retention, checkpoint и отмену; v4 добавляет controlled-clock temporal-replan проверку.

| Oracle | Версия свидетельства | Статус | Узлы | Полный план и метрики |
|---|---|---|---:|---|
| CANONICAL | candidate-v4-build | COMPLETE | 1364 | [oracle JSON](../../../target/practical-controller-pc2/candidate-v4-build/target/pc2-oracle-CANONICAL.json) |
| CONTINUE_WAIT | candidate-v4-build | COMPLETE | 1364 | [oracle JSON](../../../target/practical-controller-pc2/candidate-v4-build/target/pc2-oracle-CONTINUE_WAIT.json) |

В реестре сохранения — 8832 исторических файлов; их проверка отделена от новых экспериментов. Генератор не запускает JVM и не заменяет результаты. Он сверяет analysis с регистрациями, различает PARTIAL и NOT_RUN; итоговая выбранная серия содержит все16+6 завершённых строк, а техническая ошибка сохранена отдельно.

```powershell
$py = "C:\Users\Muhalek\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $py -X utf8 scripts/pc2_extract_fixtures.py --verify
$env:PC2_BUILD_LABEL = "local-check-" + (Get-Date -Format "yyyyMMdd-HHmmss")
& $py -X utf8 scripts/run_pc2.py build
$env:PC2_SERIES = "measured-v4b"
& $py -X utf8 scripts/analyze_pc2.py
& $py -X utf8 scripts/write_pc2_report.py
```

Для повторного исполнения нужны отдельные выходные каталоги: runner не перезаписывает регистрацию/попытки. Maven-команды находятся в tests/binary JSON; точные JVM-команды — в invocation.json каждой строки. Сохраняйте соответствующие v2/v4 JAR, model hash и лимиты; не запускайте все команды параллельно.

Полные анализы: [исходные16](../../../target/practical-controller-pc2/iss46-v1/analysis.json), [техническая ошибка](../../../target/practical-controller-pc2/measured-v4/analysis.json), [corrected measured](../../../target/practical-controller-pc2/measured-v4b/analysis.json). Фактических попыток 23; NOT_RUN не считается попыткой. Принадлежность к итоговой восьмёрке задана явно. [Manifest](../../../target/practical-controller-pc2/manifest.json) фиксирует версии и хеши.

## Граница выводов

Портфель устраняет безусловный приоритет худшего терминального WAIT и заново проверяет сохранённый остаток. Отдельная measured-коррекция устраняет подтверждённую подмену прошедшего WAIT более поздним ожиданием; её фактический результат читается из corrected v4, стоимость новых решений показана явно. Локальная инварианта, миссионные исходы и причины остановок остаются разными утверждениями.

Дополнительный горизонт не объявляется лучше заранее. Terminal effects не исправляются бонусом или изменением objective. Согласование после неопределённого SWITCH/OPEN/REUSE, статистическая устойчивость и бортовая калибровка остаются за рамками PC2.

На текущей лёгкой ISS46-нагрузке итоговые Next/Multi, PerContact и offline seed-without-search совпали по пяти показателям; независимого преимущества Multi здесь не найдено. FixedPeriod тоже обслужил46/46, но создал122 ключа вместо92 и израсходовал35.66 вместо32.66 с модельного operation CPU. Практический Multi вычислительно дороже Next: measured процессы CANONICAL593.25 против336.61 с, CONTINUE_WAIT417.41 против129.88 с. Это единичные host измерения, а не статистически установленный коэффициент замедления.

Следующий узкий шаг — использовать уже допустимый seed/retained как начальный incumbent внутри ограниченного DFS, отдельно проверив корректность отсечений и сохранение полного tie-break на малых оракулах. Сейчас portfolio исправляет качество внешнего выбора, но не сокращает объём DFS. Также остаётся отдельная техническая неопределённость: причина единичного Windows checkpoint AccessDenied не установлена; успешный повтор не доказывает надёжность во всех условиях.
