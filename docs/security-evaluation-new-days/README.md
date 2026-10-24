# SE2.4 — воспроизводимость

Основной отчёт: [report.md](report.md). Регистрация и все исходные/полученные
данные: `target/security-evaluation-new-days/`.

Java-код и протокол зафиксированы commit
`67870e66f043ce63f8370f1333b564184a6793c1`; runner-only исправление отчёта
сопоставимости — `4175abe`. Движок, solver, comparator, guard и цели не менялись.
Изменён только адаптер SecurityEvaluationNewDaysMain: configure вызывает
существующие ISS fixtures/incidentScenario, run — прежний контроллер с заданными
опциями и H=count−1 для Multi. Политики выбирают все действия самостоятельно.

Новый JAR: `target/security-evaluation-new-days/new-days-67870e66f043.jar`.
SHA-256: `2cefc98259eb910364db57105ad9e4265a4a4fbfa9f6d7efc77ecfee0039a4ee`.
Он построен из git archive в отдельном source-каталоге. Орбитальные тесты
использовали копии сохранённых data/sources с проверкой хешей.

```powershell
# В изолированном source; JAVA_HOME — Java 21.0.10:
.\mvnw.cmd -o -B -ntp -Dmaven.repo.local=<root>/.tools/m2 `
  '-DargLine=-Xmx1024m -Djava.io.tmpdir=<isolated>/tmp' `
  -Dtest=OrekitContactGeneratorTest,ContactScheduleSelectorTest,SecurityEvaluationSe2MainTest,ContinuationReuseControllerTest,ContinuationReuseGuardTest clean verify
```

**40 тестов**, 0 failures/errors/skipped; BUILD SUCCESS, Maven 29.385 s.
Полный набор тестов проекта не запускался. Версии инструментов и все хеши
исходников — build.json; XML — verification-reports внутри итогового архива.

Выполненный конвейер (для новой серии нужен отдельный каталог; команды отказываются
заменять регистрацию, попытки и архив):

```powershell
python -X utf8 scripts/run_security_evaluation_new_days.py init
# commit источников и протокола
python -X utf8 scripts/run_security_evaluation_new_days.py prepare
# Maven в изолированном source
python -X utf8 scripts/run_security_evaluation_new_days.py freeze
python -X utf8 scripts/run_security_evaluation_new_days.py generate
python -X utf8 scripts/run_security_evaluation_new_days.py configure
# read-only input review, затем:
python -X utf8 scripts/run_security_evaluation_new_days.py register
python -X utf8 scripts/run_security_evaluation_new_days.py watchdog
python -X utf8 scripts/run_security_evaluation_new_days.py run
python -X utf8 scripts/analyze_security_evaluation_new_days.py
python -X utf8 scripts/package_security_evaluation_new_days.py
python -X utf8 scripts/run_security_evaluation_new_days.py verify
```

Генерация выполняет существующие `orekit` и `schedule-select` с полными командами
в `day*/geometry-command.json` и `selection-command.json`. Оба набора окон
рассчитываются из того же GP; временной сдвиг старых контактов не применяется.
Сохранены все raw-копии источников, параметры, полные datasets и schedules.

`configure.log` сохраняет единственную ошибку подготовки отчёта до регистрации:
прежний helper сравнения JSON не допускал добавление disclosure в пустой список.
Исправлен только отчёт; уже записанные day1 модели не заменены. Следующий вызов
сохранил `configure-attempt2.log`, сгенерировал day2 и завершил проверки. Это
не повтор эксперимента: ни одна политика на тот момент не запускалась.

`input-review.txt` — независимая проверка 13 source hashes, 62 input hashes,
геометрии, выбора фрагментов, finite LIGHT, инициализации и attack timestamps.
`watchdog.json` — молчащий бесконечный child остановлен и reaped за 2.047 s при
2 s timeout, пустой лог; причина прежних аномалий времени этим не установлена.

В `runs/<case>/` — checkpoint, result, GC/process log, journal, compact decisions,
full trace и per-attempt аудит. Анализ использует прежние audit_security_evaluation,
recovery_metrics_se21, proof_audit; собственные новые метрики не введены.
`mechanisms.json` содержит операции, доступные горизонты, материал при возврате
и фактические метаданные ключей; readiness intervals — в independent-audit.json.
Частичные исполнения остаются в таблице с unavailable для итоговых метрик.

Хеши 3492 прежних файлов проверяются `verify`. Новые архивы расположены только
в target/security-evaluation-new-days; прежние JAR и архивы не заменялись.
