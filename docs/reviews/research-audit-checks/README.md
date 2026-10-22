# Проверки аудита

Изолированные программы к [research-audit.md](../research-audit.md). Это не часть исследовательского
кода и не тесты Maven. Java-классы лежат в пакете `org.contactaware.security.simulation`, чтобы
обращаться к package-private API, и компилируются против замороженного `se21.jar`.

```bash
J="/c/Program Files/Java/jdk-21.0.10/bin"
JAR="C:/Users/Muhalek/ContactAwareSS/target/security-evaluation-se21-build/se21.jar"
mkdir -p /tmp/audit-cls
"$J/javac" --release 21 -encoding UTF-8 -cp "$JAR" -d /tmp/audit-cls java/org/contactaware/security/simulation/*.java
# reference DFS vs accelerations, bound admissibility, memo-key sufficiency: <seeds> <firstSeed>
"$J/java" -Xmx2g -cp "/tmp/audit-cls;$JAR" org.contactaware.security.simulation.AuditDifferential 100 1000
"$J/java" -Xmx2g -cp "/tmp/audit-cls;$JAR" org.contactaware.security.simulation.AuditDifferential2 80 5000   # roots after random factual prefix
# exact ties of X/Multi security decisions; X/Y grid under canonical vs lazy tie-break
"$J/java" -Xmx2g -cp "/tmp/audit-cls;$JAR" org.contactaware.security.simulation.TieProbe X 2
"$J/java" -Xmx2g -cp "/tmp/audit-cls;$JAR" org.contactaware.security.simulation.TieBreakSensitivity
# simulator/security checks and SE2 journal scan (run from repository root)
"$J/java" -cp "/tmp/audit-cls;$JAR" org.contactaware.security.simulation.SimAudit all
"$J/java" -cp "/tmp/audit-cls;$JAR" org.contactaware.security.simulation.JournalScan
```

Python-скрипты (`python/`) — независимый парсер журналов `target/security-evaluation-se2/` и
`target/security-evaluation-se21/`. Они используют только стандартную библиотеку; запускать из корня
репозитория: `python -X utf8 -I docs/reviews/research-audit-checks/python/own_parser.py`.
Пути вывода внутри скриптов указывают на временный каталог аудита; при повторе их можно заменить.
Журналы фактических прогонов аудита лежат в `logs/`.
