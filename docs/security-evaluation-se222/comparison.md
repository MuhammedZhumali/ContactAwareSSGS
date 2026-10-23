# X: четыре сочетания горизонта и tie-break

Смешанные сохранённые версии указаны явно. Это не единый benchmark времени. Полные источники и commit — в [CSV](comparison.csv).

Пять показателей: обслуженные контакты / useful s / CPU GS+SC s / служебные B / новые ключи. Доставка и раскрытие — байты. Четыре задержки отсчитаны от уведомления НС. CENSORED означает отсутствие события до конца полного опыта; unavailable — нет итогового измерения; not-applicable — нет применимого инцидента. Ноль допустим только при подтверждённой готовности на старте.

| Атака | Политика / правило | Статус; версия | Пять показателей | Конфиденциально / раскрыто до / после | КА / пара / SA / первый кадр, s |
| --- | --- | --- | --- | --- | --- |
| none | next / CANONICAL | FINISHED; SE2.2.1a | 2 / 38.000000000 / 0.120000000 / 160 / 0 | 15200 / 0 / 0 | not-applicable / not-applicable / not-applicable / not-applicable |
| none | multi / CANONICAL | FINISHED; SE2.2.1a | 3 / 37.000000000 / 0.830000000 / 1240 / 2 | 14800 / 0 / 0 | not-applicable / not-applicable / not-applicable / not-applicable |
| none | next / CONTINUE_WAIT | FINISHED; SE2.2 | 2 / 38.000000000 / 0.120000000 / 160 / 0 | 15200 / 0 / 0 | not-applicable / not-applicable / not-applicable / not-applicable |
| none | multi / CONTINUE_WAIT | FINISHED; SE2.2.2 | 3 / 37.000000000 / 0.830000000 / 1240 / 2 | 14800 / 0 / 0 | not-applicable / not-applicable / not-applicable / not-applicable |
| original-detected | next / CANONICAL | FINISHED; SE2.2.1a | 3 / 34.000000000 / 0.870000000 / 1336 / 2 | 9200 / 3600 / 800 | 0.400 / 7.400 / 69 / 69.250 |
| original-detected | multi / CANONICAL | FINISHED; SE2.2 | 3 / 35.500000000 / 0.930000000 / 1416 / 2 | 11400 / 2000 / 800 | 0.400 / 1.400 / 2.400 / 2.650 |
| original-detected | next / CONTINUE_WAIT | FINISHED; SE2.2.1a | 3 / 34.000000000 / 0.870000000 / 1336 / 2 | 9200 / 3600 / 800 | 0.400 / 7.400 / 69 / 69.250 |
| original-detected | multi / CONTINUE_WAIT | FINISHED; SE2.2.2 | 3 / 34.000000000 / 0.870000000 / 1336 / 2 | 9200 / 3600 / 800 | 0.400 / 7.400 / 69 / 69.250 |
| p70-detected | next / CANONICAL | FINISHED; SE2.2 | 2 / 34.000000000 / 0.660000000 / 1056 / 2 | 7600 / 5200 / 800 | 0.400 / censored / censored / censored |
| p70-detected | multi / CANONICAL | FINISHED; SE2.2.1a | 3 / 35.500000000 / 0.930000000 / 1416 / 2 | 9800 / 3600 / 800 | 0.400 / 1.400 / 2.400 / 2.650 |
| p70-detected | next / CONTINUE_WAIT | FINISHED; SE2.2 | 2 / 34.000000000 / 0.660000000 / 1056 / 2 | 7600 / 5200 / 800 | 0.400 / censored / censored / censored |
| p70-detected | multi / CONTINUE_WAIT | FINISHED; SE2.2.2 | 3 / 36.250000000 / 0.870000000 / 1336 / 2 | 8600 / 5200 / 700 | 64.400 / 65.400 / 66.400 / 66.650 |
| p90-detected | next / CANONICAL | FINISHED; SE2.2 | 2 / 38.000000000 / 0.660000000 / 1056 / 2 | 7600 / 6800 / 800 | 60.400 / censored / censored / censored |
| p90-detected | multi / CANONICAL | FINISHED; SE2.2 | 3 / 36.500000000 / 0.870000000 / 1336 / 2 | 8600 / 5200 / 800 | 60.400 / 61.400 / 62.400 / 62.650 |
| p90-detected | next / CONTINUE_WAIT | FINISHED; SE2.2 | 2 / 38.000000000 / 0.660000000 / 1056 / 2 | 7600 / 6800 / 800 | 60.400 / censored / censored / censored |
| p90-detected | multi / CONTINUE_WAIT | FINISHED; SE2.2.2 | 3 / 36.500000000 / 0.870000000 / 1336 / 2 | 8600 / 5900 / 100 | 60.400 / 61.400 / 62.400 / 62.650 |
| gap-midpoint-detected | next / CANONICAL | FINISHED; SE2.2 | 2 / 38.000000000 / 0.660000000 / 1056 / 2 | 7600 / 7600 / 0 | 53.400 / censored / censored / censored |
| gap-midpoint-detected | multi / CANONICAL | FINISHED; SE2.2 | 3 / 36.500000000 / 0.870000000 / 1336 / 2 | 8600 / 6000 / 0 | 53.400 / 54.400 / 55.400 / 55.650 |
| gap-midpoint-detected | next / CONTINUE_WAIT | FINISHED; SE2.2 | 2 / 38.000000000 / 0.660000000 / 1056 / 2 | 7600 / 7600 / 0 | 53.400 / censored / censored / censored |
| gap-midpoint-detected | multi / CONTINUE_WAIT | FINISHED; SE2.2.2 | 3 / 36.500000000 / 0.870000000 / 1336 / 2 | 8600 / 6000 / 0 | 53.400 / 54.400 / 55.400 / 55.650 |
