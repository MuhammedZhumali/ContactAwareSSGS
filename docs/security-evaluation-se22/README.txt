SE2.2 — версия, файлы и воспроизведение

Исполняемый commit: a206f6a29732e588e51f55e2d3e9a510571d9995.
Baseline SE2/SE2.1/Claude: d3a3a4b77a3c9afa71420222d5fb70813b4d841a.
Новый JAR: target/security-evaluation-se22-build/se22-a206f6a29732.jar.
SHA-256: 9875a1da505715c79e1bbe808806ff26755645c0a20ce0c2b6076872e78fd457.
Java/javac 21.0.10, Maven 3.9.9, Python 3.12.14, Windows 11 amd64.

Классы:
- PlanTieBreak: CANONICAL и CONTINUE_WAIT.
- PlanningResultComparator: неизменная пятёрка, выбранное правило только при
  полном равенстве; явные group/until/original-tuple в альтернативе.
- PlanningActionReduction: выбранный порядок для WAIT и равных bound-prefix.
- ExactHorizonPlanner: тот же порядок для итогов и memo suffix; прежний общий
  порядок обхода. planWithFirstAction фиксирует точное первое действие, сохраняя
  исходный корень, горизонт и учёт прогресса/затрат.
- SecurityEvaluationSe22Main: отдельный CLI для controller/probe; оригинальные
  модели, существующий SecurityPolicyController, checkpoints, telemetry.
- PlanTieBreakTest: 17 проверок обоих правил, DFS/ускорений/memo, обхода,
  приоритетов, фиксированного первого действия и INCOMPLETE.

Новые документы и таблицы: docs/security-evaluation-se22/.
Сырые новые результаты: target/security-evaluation-se22/.
Оригинальные программы и логи Claude: docs/reviews/research-audit-checks/.
Их новые журналы: target/security-evaluation-se22/claude-replay/.
Прозрачный ограничивающий adapter: audit-adapter/ClaudeTieReplay.java.
Снимки и результаты отдельных альтернатив: target/security-evaluation-se22/probes/.
PREPARE/INSTALL observation + выбранный полный результат: runs/*/key-decisions.json.
Полные фактические события, решения и checkpoint: runs/*/actual.*.json, checkpoint.json.
Ни один новый запуск не пишет в SE2, SE2.1 или docs/reviews.

Проверка уже сохранённых данных (из корня репозитория):
  python -X utf8 scripts/run_security_evaluation_se22.py verify
  python -X utf8 scripts/analyze_security_evaluation_se22.py all
Первое проверяет сохранённые входы и регистрацию; второе пересчитывает только
производные итоговые таблицы, не исполняет политики и не заменяет checkpoint X.

Чистая Maven-сборка выполнена из git archive в отдельном каталоге:
  target/se22-clean-build/a206f6a29732e588e51f55e2d3e9a510571d9995/source
Точный архив и его хеш — в build.json/manifest.json. Исходники архива сверены
пофайлово; старые target/classes не переносились. До сборки рабочее дерево чистое.
Первой попытке не хватило игнорируемых datasets; её лог сохранён. Перенесены 37
хешированных файлов earth, celestrak, iss-20261010/orekit и schedule; после этого
из тех же исходников выполнен новый clean verify, 361 тест без ошибок/пропусков.
Офлайн-сборке нужен сохранённый Maven cache; в команде указать его полный путь:
  mvnw.cmd -Dmaven.repo.local=<repo>/.tools/m2 -o -B -ntp
    "-DargLine=-Xmx1024m -Djava.io.tmpdir=<isolated-test-tmp>" clean verify
Исходные build logs и test-dataset-hashes.json включены в свидетельства.

Первоначальная регистрация/эксперимент, только в НОВОЙ рабочей копии без результатов:
  python -X utf8 scripts/freeze_security_evaluation_se22.py
  python -X utf8 scripts/run_security_evaluation_se22.py register
  python -X utf8 scripts/run_security_evaluation_se22.py x
  python -X utf8 scripts/analyze_security_evaluation_se22.py x
  [проверить X, сохранить промежуточную записку]
  python -X utf8 scripts/run_security_evaluation_se22.py controls
  python -X utf8 scripts/analyze_security_evaluation_se22.py all
Freeze ожидает подготовленный изолированный архив, build-preflight.json и Maven
результаты; это фиксация сборки, а не автоматическая установка инструментов.
Runner отказывается заменять регистрацию/попытки. Для другой версии или повтора
нужны отдельный каталог и отдельная регистрация. Путь Java зафиксирован в runner.
Восстановление корней и проверка старых хешей требуют сохранённого SE2/SE2.1.
Архив свидетельств не содержит дубликаты всех прежних больших архивов/кэшей.

Лимиты: 2M узлов/30 s на поиск, 60 s на контроллер, 90 s внешний watchdog.
Проверки корня: 120 s совокупно/150 s watchdog. Никаких повышений по результатам.
Незавершённые поиски и ошибки ввода-вывода не получают итоговую оценку качества.
Время поиска измеряется отдельно и по-прежнему не расходует модельное время.
Определения атак и восстановления неизменны; см. ../security-evaluation-se21/metrics-spec.md.
