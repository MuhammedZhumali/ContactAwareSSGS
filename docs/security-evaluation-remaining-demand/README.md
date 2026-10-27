# SE2.5: day2, остаточный спрос C1

[Отчёт](report.md) · [Протокол](protocol.md) · [8 попыток](comparison.md) ·
[4 пары](differences.md) · [после уведомления](post-notice.md) ·
[LIGHT → новая нагрузка](versus-light.md) · [manifest](artifact-manifest.json).

Данные: `target/security-evaluation-remaining-demand/`. Прежние файлы не меняются.
JAR SE2.4 повторно используется без сборки. Новая модель отличается ровно двумя
offered-полями C1; исходные имена/provenance сохранены и подписаны в протоколе.

PowerShell (в корне проекта):

```powershell
$taskPython = 'C:\Users\Muhalek\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $taskPython -X utf8 scripts/run_security_evaluation_remaining_demand.py verify
& $taskPython -X utf8 -m unittest discover -s scripts -p test_remaining_demand_metrics.py -v
& $taskPython -X utf8 scripts/analyze_security_evaluation_remaining_demand.py
```

Исходная последовательность серии: `configure`, read-only input review,
`register`, `watchdog`, `run`. Повторное configure/register/run поверх сохранённой
серии запрещено обвязкой. Восемь попыток, без повторов; автоматический запуск
исторического LIGHT отсутствует. Постобработка не вызывает Java или поиск.

`run_security_evaluation_remaining_demand.py` меняет только экспериментальные
входы и подключает прежнюю обвязку watchdog/run. `analyze_…` использует прежние
аудиторы и вычисляет новые показатели из журнала. `test_remaining_demand_metrics.py`
проверяет границы постобработки; `package_…` создаёт архив только один раз,
проверяет SHA-256/CRC и сохранность прежних файлов.

В `comparison.csv` — конечные показатели только FINISHED, статусы четырёх
этапов, signed remaining C1 time, elapsed/CPU, SEARCH/REUSE/узлы. `differences.csv`
содержит все четыре сравнения, включая незавершённые. `versus-light.csv` —
сопоставление с историей; `historical-light.csv` содержит прежние версии и метрики.
`stopped-searches.csv` — причины/фазы четырёх остановок. Фактические события,
снимки и полные доказанные/незавершённые планы остаются в отдельных `runs/`.

Результат: 4 FINISHED, 4 SEARCH_INCOMPLETE, 0 завершённых пар Multi−Next;
3 целевых Python-теста прошли. Нового Maven-прогона нет. Следующий исследовательский
шаг потребует отдельного решения: текущая серия не устанавливает качество Multi
при сохраняющемся спросе и не разрешает повышение бюджета или оптимизацию.
