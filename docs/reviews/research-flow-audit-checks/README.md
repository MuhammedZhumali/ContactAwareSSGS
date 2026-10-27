# Проверки аудита логики исследования

Изолированные проверки к [research-flow-audit.md](../research-flow-audit.md). Они только читают замороженные
журналы и checkpoint. Это не часть исследовательского кода.

```bash
# из корня репозитория; стандартная библиотека Python
python -X utf8 -I docs/reviews/research-flow-audit-checks/python/served_vs_confidential.py target/security-evaluation-se2
python -X utf8 -I docs/reviews/research-flow-audit-checks/python/se24_timing_readiness.py

# одиночный точный поиск сохранённого корня SE2.5 против JAR SE2.4/SE2.5 (commit 67870e6)
J="/c/Program Files/Java/jdk-21.0.10/bin"; JAR=target/security-evaluation-new-days/new-days-67870e66f043.jar
"$J/javac" --release 21 -cp "$JAR" -d /tmp/flow-cls java/org/contactaware/security/simulation/DemandRootProbe.java
"$J/java" -Xmx3g -cp "/tmp/flow-cls;$JAR" org.contactaware.security.simulation.DemandRootProbe \
  target/security-evaluation-remaining-demand/runs/demand-day2-multi-none-canonical/checkpoint.json 2 CANONICAL 4000000
```

Журналы фактических прогонов — в `logs/`.
