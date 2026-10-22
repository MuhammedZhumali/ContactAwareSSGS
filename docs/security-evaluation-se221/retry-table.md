# Двенадцать отдельных повторов SE2.2.1

A: исправление checkpoint, старый обход. B: исправление + SERVICE_FIRST.
Прежние попытки сохранены; ссылки ведут на их отдельные журналы. Время поиска не входит в модельное время.

| Исходная попытка | Версия | Было → стало | Контакты; полезное s; CPU s; B; ключи | Поиск s / узлы | Повторы записи |
|---|---|---|---|---|---:|
| [X-next-none-canonical](../../target/security-evaluation-se22/X-next-none-canonical.log) · [повтор](../../target/security-evaluation-se221/X-next-none-canonical.log) | A | PROCESS_ERROR → FINISHED | 2; 38; 0.12; 160; 0 | 5.514 / 60697 | 0 |
| [X-multi-none-canonical](../../target/security-evaluation-se22/X-multi-none-canonical.log) · [повтор](../../target/security-evaluation-se221/X-multi-none-canonical.log) | A | PROCESS_ERROR → FINISHED | 3; 37; 0.83; 1240; 2 | 4.66 / 45629 | 0 |
| [X-multi-none-continue_wait](../../target/security-evaluation-se22/X-multi-none-continue_wait.log) · [повтор](../../target/security-evaluation-se221/X-multi-none-continue_wait.log) | B | SEARCH_INCOMPLETE → CONTROLLER_TIME_LIMIT | — | 59.642 / 775534 | 0 |
| [X-next-original-detected-canonical](../../target/security-evaluation-se22/X-next-original-detected-canonical.log) · [повтор](../../target/security-evaluation-se221/X-next-original-detected-canonical.log) | A | PROCESS_ERROR → FINISHED | 3; 34; 0.87; 1336; 2 | 4.116 / 44892 | 0 |
| [X-next-original-detected-continue_wait](../../target/security-evaluation-se22/X-next-original-detected-continue_wait.log) · [повтор](../../target/security-evaluation-se221/X-next-original-detected-continue_wait.log) | A | PROCESS_ERROR → FINISHED | 3; 34; 0.87; 1336; 2 | 4.068 / 44892 | 0 |
| [X-multi-original-detected-continue_wait](../../target/security-evaluation-se22/X-multi-original-detected-continue_wait.log) · [повтор](../../target/security-evaluation-se221/X-multi-original-detected-continue_wait.log) | B | SEARCH_INCOMPLETE → CONTROLLER_TIME_LIMIT | — | 59.763 / 784024 | 0 |
| [X-multi-p70-detected-canonical](../../target/security-evaluation-se22/X-multi-p70-detected-canonical.log) · [повтор](../../target/security-evaluation-se221/X-multi-p70-detected-canonical.log) | A | PROCESS_ERROR → FINISHED | 3; 35.5; 0.93; 1416; 2 | 5.146 / 49665 | 0 |
| [X-multi-p70-detected-continue_wait](../../target/security-evaluation-se22/X-multi-p70-detected-continue_wait.log) · [повтор](../../target/security-evaluation-se221/X-multi-p70-detected-continue_wait.log) | B | SEARCH_INCOMPLETE → CONTROLLER_TIME_LIMIT | — | 59.75 / 797108 | 0 |
| [X-multi-p90-detected-continue_wait](../../target/security-evaluation-se22/X-multi-p90-detected-continue_wait.log) · [повтор](../../target/security-evaluation-se221/X-multi-p90-detected-continue_wait.log) | B | SEARCH_INCOMPLETE → CONTROLLER_TIME_LIMIT | — | 59.733 / 819100 | 0 |
| [X-multi-gap-midpoint-detected-continue_wait](../../target/security-evaluation-se22/X-multi-gap-midpoint-detected-continue_wait.log) · [повтор](../../target/security-evaluation-se221/X-multi-gap-midpoint-detected-continue_wait.log) | B | SEARCH_INCOMPLETE → CONTROLLER_TIME_LIMIT | — | 59.74 / 839010 | 0 |
| [Y-multi-original-detected-canonical](../../target/security-evaluation-se22/Y-multi-original-detected-canonical.log) · [повтор](../../target/security-evaluation-se221/Y-multi-original-detected-canonical.log) | A | PROCESS_ERROR → FINISHED | 3; 34; 0.87; 1336; 2 | 4.636 / 49603 | 0 |
| [iss3-cp7200-multi-none-continue_wait](../../target/security-evaluation-se22/iss3-cp7200-multi-none-continue_wait.log) · [повтор](../../target/security-evaluation-se221/iss3-cp7200-multi-none-continue_wait.log) | A | PROCESS_ERROR → FINISHED | 3; 60; 1.48; 2240; 4 | 0.717 / 1860 | 0 |

## X: объединённые полные исполнения

В каждой ячейке: версия; обслуженные контакты; первый независимый кадр (s от уведомления НС); конфиденциально доставленные B.
SE2.2 — прежний завершённый запуск; A/B — только новый повтор. CENSORED не равен нулю.

| Атака | Next canonical | Multi canonical | Next alternative | Multi alternative |
|---|---|---|---|---|
| none | SE2.2.1-a; 2; not-applicable; 15200 | SE2.2.1-a; 3; not-applicable; 14800 | SE2.2; 2; not-applicable; 15200 | CONTROLLER_TIME_LIMIT |
| original | SE2.2.1-a; 3; 69.25; 9200 | SE2.2; 3; 2.65; 11400 | SE2.2.1-a; 3; 69.25; 9200 | CONTROLLER_TIME_LIMIT |
| p70 | SE2.2; 2; censored; 7600 | SE2.2.1-a; 3; 2.65; 9800 | SE2.2; 2; censored; 7600 | CONTROLLER_TIME_LIMIT |
| p90 | SE2.2; 2; censored; 7600 | SE2.2; 3; 62.65; 8600 | SE2.2; 2; censored; 7600 | CONTROLLER_TIME_LIMIT |
| gap-midpoint | SE2.2; 2; censored; 7600 | SE2.2; 3; 55.65; 8600 | SE2.2; 2; censored; 7600 | CONTROLLER_TIME_LIMIT |
