# SE2.3 — воспроизводимость

Исполняемые исходники зафиксированы commit `e3266427f33902e636e2eedb81c0b45fcc28bee3`.
Java-изменение — только новая точка запуска SecurityEvaluationC3Main. Движок,
контроллер, guard, solver, comparator, политики и метрики совпадают с SE2.2.2.
JAR `target/security-evaluation-c3/c3-e3266427f339.jar`, SHA-256:
`e7a97c56e6223c5ba2cb9408999f45d0d69ee3065ab63a014ef78ed289cf0fd3`.

Новые входы и порядок — `target/security-evaluation-c3/configurations.json`;
полная предрегистрация — `registration.json`. Машинная сверка —
[configuration-audit.json](configuration-audit.json). Независимый read-only
review находится в `target/security-evaluation-c3/input-review.txt`: проверены
20 baseline-моделей, 40 новых моделей и 56 хешей входов. Проверяющий не выполнял
JVM или сборок. Основной агент выполнил конфигурации, интеграцию, сборку и серию.

Сборка из чистого git archive в `target/c3-clean-build/<commit>/source`:

```powershell
.\mvnw.cmd -o -B -ntp -Dmaven.repo.local=<root>/.tools/m2 `
  '-DargLine=-Xmx1024m -Djava.io.tmpdir=<isolated>/tmp' `
  -Dtest=ContinuationReuseControllerTest,ContinuationReuseGuardTest,ContinueSuffixOptimalityTest,ContinueSuffixPrepareOptimalityTest clean verify
```

23 целевых теста прошли, 0 failures/errors/skipped, Maven 21.580 s. Полный
397-test прогон SE2.2.2 остаётся сохранённым прежним результатом и здесь не
повторялся. Версии Java/Maven/Python и хеши всех исходников/JAR — в `build.json`.

Из корня репозитория (python — установленный Python3; использовался bundled runtime):

```powershell
python -X utf8 scripts/run_security_evaluation_c3.py configure
python -X utf8 scripts/run_security_evaluation_c3.py prepare
# Maven в изолированном source, затем:
python -X utf8 scripts/run_security_evaluation_c3.py freeze
python -X utf8 scripts/run_security_evaluation_c3.py register
python -X utf8 scripts/run_security_evaluation_c3.py run
python -X utf8 scripts/analyze_security_evaluation_c3.py
python -X utf8 scripts/package_security_evaluation_c3.py
python -X utf8 scripts/run_security_evaluation_c3.py verify
```

Это команды выполненной серии, не разрешение заменить её. Первые четыре шага
отказываются перезаписывать входы/сборку/регистрацию. `run` исполняет только
NOT_RUN, а существующий каталог попытки запрещает её молчаливое повторение.
Для новой серии нужны другое имя и регистрация. Анализ можно повторить без JVM.

В `runs/<case>/` находятся process/GC/checkpoint/result, события, компактные
решения, полные планы и цепочки reuse. `audit-status.json` проверяется после
каждой попытки до следующей JVM. Итоговый independent-audit.json сохраняет также
частичные факты и интервалы готовности; c3-entry-and-operations.json — независимое
восстановление состояния на входе C3 и фактические операции. Частичное исполнение
не превращается в итоговые метрики.

[Сравнение 60 строк](comparison.md), [CSV](comparison.csv), [Multi−Next](differences.md),
[CSV разностей](differences.csv), [отчёт](report.md), [валидация](validation.json).
Архив `c3-new-evidence.zip` содержит только новые свидетельства, новый JAR,
изменённые исходники и неизменные постпроцессоры для анализа. Прежние архивы
не вложены; исходные 20 строк5s доступны по ссылкам и хешам сохранённых источников.
Сохранность 2920 прежних файлов проверяется `verify`.

После основных 40 попыток отдельно зарегистрированы три технических повтора
из-за аномального elapsed/watchdog (343/561/46476 s при CPU около2–3s).
Причина промежутков не установлена. Первичные `summary.json` и все их файлы
не изменены; `technical-repeat-registration.json` зафиксировал ровно эти три
случая, прежний JAR и бюджеты до повторов. Команды:

```powershell
python -X utf8 scripts/run_c3_technical_repeats.py register
python -X utf8 scripts/run_c3_technical_repeats.py run
```

Все три повторные попытки FINISHED. Их происхождение отражено в `selectedAttempt`
и `attemptStatuses` основной таблицы; [attempts.csv](attempts.csv) содержит все
43 новые попытки, включая три первоначальных CONTROLLER_TIME_LIMIT. Обычные
SEARCH_INCOMPLETE не повторялись. Ни новых сочетаний сетки, ни повышенных бюджетов
этот технический повтор не добавляет.
