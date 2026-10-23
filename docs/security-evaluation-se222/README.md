# SE2.2.2: воспроизведение и расположение материалов

Исполняемые исходники: commit `1900d3d8a3b11b6b338c4bad44ec855d4cdca0ce`.
JAR: `target/security-evaluation-se222/se222-1900d3d8a3b1.jar`, SHA-256
`c0d70b9be866f7d411acf29db80df191a698dcf6c597489076c0e2b8d69e6086`.
Версии Java/Maven/Python, tree и хеш каждого исходника — в `build.json` нового
каталога результатов. Анализ и упаковка не меняют Java-классы этого JAR.

Все команды выполняются из корня ContactAwareSS, кроме Maven в изолированном
каталоге `target/se222-clean-build/<commit>/source`. Здесь `python` означает
имеющийся Python 3; в выполненном запуске использован bundled runtime Codex.

```powershell
python -X utf8 scripts/run_security_evaluation_se222.py prepare
# В отдельном source, с JAVA_HOME на Java 21 и абсолютными путями к repo/tmp:
.\mvnw.cmd -o -B -ntp -Dmaven.repo.local=<root>/.tools/m2 `
  '-DargLine=-Xmx1024m -Djava.io.tmpdir=<isolated>/tmp' clean verify
# Только после зафиксированных пяти ошибок Permission denied: connect:
.\mvnw.cmd -o -B -ntp -Dmaven.repo.local=<root>/.tools/m2 `
  '-DargLine=-Xmx1024m -Djava.io.tmpdir=<isolated>/tmp' -Dtest=HttpImportTest verify
# Снова из корня:
python -X utf8 scripts/run_security_evaluation_se222.py freeze
python -X utf8 scripts/run_security_evaluation_se222.py register
python -X utf8 scripts/run_security_evaluation_se222.py run
python -X utf8 scripts/analyze_security_evaluation_se222.py
python -X utf8 scripts/package_security_evaluation_se222.py
python -X utf8 scripts/run_security_evaluation_se222.py verify
```

Это команды выполненного этапа, а не разрешение заменить его файлы. `prepare`,
`freeze`, `register`, `run` и упаковка отказываются повторять уже зарегистрированную
попытку. Для новой серии требуется новое имя/регистрация. Независимый анализ можно
повторить без запуска JVM; определения берутся из прежних постпроцессоров.

Полный `clean verify` был выполнен один раз: 392 успешных теста, пять ошибок
создания localhost-сервера из-за sandbox. Сохранены `build.log` и исходный XML в
`failed-build-reports/`. Повторен только `HttpImportTest`: пять успешных тестов,
затем Maven завершил `verify` и упаковку в том же изолированном source. Итоговые
XML охватывают 397 уникальных тестов без ошибок/провалов/пропусков. Это не второй
полный прогон. Исходники не менялись между этими командами.

Первый целевой прогон имел ошибку нового тестового входа `Multi(H=1)`;
исправленный вход повторно проверен. Оба лога сохранены. Основной агент выполнил
интеграцию, тесты, JVM и анализ. Единственный подагент провёл ограниченное чтение
provenance и учёта времени; сборки ему не поручались.

Новые журналы: `target/security-evaluation-se222/runs/<case>/` — события,
компактные решения, полный trace с исходными COMPLETE-планами и proof-offset,
checkpoint, диагностика записи, GC, stdout и результат. `independent-audit.json`
содержит независимые показатели, интервалы готовности и проверку цепочек reuse.
Итоговые таблицы и выводы: [comparison.md](comparison.md), [comparison.csv](comparison.csv),
[computation.csv](computation.csv), [report.md](report.md).

`manifest.json` перечисляет новые файлы с хешами. `preserved-inputs.json`
перечисляет 2765 прежних файлов. Новый ZIP не содержит вложенных старых архивов;
он включает новый JAR, изменённые исходники, журналы и результаты анализа.
Полный исходный репозиторий восстанавливается из указанного commit, а старые
исходные сценарии и архивы остаются отдельными входами с хешами.
