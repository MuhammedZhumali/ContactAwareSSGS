"""Concise closure report and explicit denominators for the registered v2 series."""
from pathlib import Path
from run_research_validation import ROOT,load
from write_aitu_v2_content import score,fmt
BASE=ROOT/'target/aitu-revision-v2';DOC=ROOT/'docs/aitu-revision-v2'
roots=load(BASE/'root-tables.json');runs=load(BASE/'controller-tables.json')
lines=['# AITU revision 2 — результат независимой доработки','',
'Научный вывод уточнён: отсутствие practical-выигрыша в прежней бюджетной серии относится к её таймеру поиска 1 s. В новой ограниченной проверке ordinary practical обнаруживает полезный автономный план при 150000 и 200000 разрешённых узлов с таймером 30 s. Полный offline-контроллер CONTINUE_WAIT подтверждает дополнительный контакт Multi. CANONICAL Multi остаётся незавершённым при общем лимите180 s; его нельзя объявлять проигравшим или завершённым.',
'', '## Замечания и границы', '',
'| Замечание | Изменение | Свидетельство | Остаток |','|---|---|---|---|',
'| Сохранность и область | Отдельные каталоги v2, неизменный frozen final-v2 JAR и production source | identity.json, preservation.json, protocol.json | Рабочее дерево исторически dirty; commit сам по себе не описывает исполняемый код. |',
'| Узлы при таймере1 s | Шесть ordinary practical поисков100k/150k/200k, таймеры30/32 s, watchdog45 s; никакого oracle | roots/*: portfolio, improvements, selected, replay, search, root-result | Тестируются три allowances, а не универсальный порог. CAN root INCOMPLETE; CAN full CONTROLLER_TIME_LIMIT. |',
'| Самодостаточный метод и входы | Псевдокод, фактический seed, полные tie tuples, отдельный traversal; ISS geometry/payload/initial state/constraints/resources | Статья, Supplement, input-descriptors.json, method-and-aitu-review.md | Значения скорости являются модельными; оконные linkRateBps остаются null. |',
'| Отрицательные disclosure-клетки | Включены обе CAN E−5/delay0,2; разделены цель, восстановление и конфиденциальная доставка | Сохранённые stage4/tables.json и независимая methodology-review.md | Новых атак нет; вероятность компрометации не оценивается. |',
'| DOCX/LaTeX/AITU | Широкие панели S5/S6,12pt, повтор headers; corrected URL punctuation; уточнённые abstract/conclusion | document-build.json, render/visual checks, компиляция обоих TeX | Авторские поля, MathType, давность и индексируемая доля не закрыты. |',
'| Воспроизведение | Версионированный пакет, хеши,27 идентичных adapter classes при пересборке, восстановление таблиц | REPRODUCE.md, manifest, package/portable validation | Публичный URL нового пакета ещё не назначен. |',
'', '## Знаменатели', '',
'| Новая популяция | Запланировано | Выполнено | Полностью завершено | Ограничено бюджетом |','|---|---:|---:|---:|---:|',
'| Практические root searches |6|6|2 COMPLETE|4 INCOMPLETE / NODE_LIMIT|',
'| Условные полные исполнения |4 из максимально4|4|3 FINISHED|1 CONTROLLER_TIME_LIMIT|',
'', 'Все четыре повторных полных исполнения были условно предусмотрены до запуска шести поисков. Для каждого правила минимум из проверенных allowances с replay-valid улучшением относительно frozen practical `(2,38,0.12,160,0)` оказался150000. Улучшения только относительно seed недостаточно для trigger. Ни одного нового attack run, никакого повышения лимитов после результатов. Двенадцать прежних paired-root diagnostics и110 прежних контроллеров не включены в эти6+4. Старые108/93/15 также остаются отдельной кампанией.',
'','## Корень t=0 H=2','',
'Один сохранённый observation подан обоим правилам. Семантическая идентичность проверяется с сохранением всех упорядоченных списков; различие сериализации Set authorizedContexts не называется изменением входа. Начальный retained отсутствует. Seed/retained планы не передаются DFS как incumbent. Режим offline, а root instrumentation только записывает улучшения. All six selected plans begin with OPEN and pass a complete replay by the ordinary BranchEngine and ContinuationEvaluator.',
'', '| Правило | Разрешено / посещено | Стоп | Выбранный S,U,C,B,K | Первое лучше old practical: узел / s | Сертификат |','|---|---:|---|---|---|---|']
for r in roots:
 first=r['firstStrictVsOld'];f='не найдено' if first is None else str(first['nodes'])+' / '+fmt(first['seconds'])
 lines.append(f"|{r['rule']}|{r['nodes']} / {r['visitedNodes']}|{r['stopReason']}|{score(r['selectedScore'])}|{f}|{'да' if r['certificate'] else 'нет'}|")
lines += ['', 'Во всех шести seed завершён и оценён; его значения, отсутствие retained и все доступные кандидаты сохранены в portfolio.json/root-tables.json. Поиск при100k улучшает расходы относительно seed, но не превосходит старый practical результат. При150k/200k дополнительный контакт найден обычным поиском. Во всех четырёх таких запусках первый пятикомпонентный выигрыш наблюдался на134269; время9.13–9.65 s отличается от прежних8.1–8.3 s. Это наблюдения конкретного состояния, traversal и host, не нижняя граница необходимого бюджета.',
'','## Полные исполнения — отдельная проверка','',
'| Правило / H | Статус | Итоговый S,U,C,B,K | Host s | Search calls / nodes |','|---|---|---|---:|---:|']
for r in runs:lines.append(f"|{r['rule']} / {r['horizon']}|{r['status']}|{score(r.get('finalScore'))}|{fmt(r.get('controllerSeconds',r['processSeconds']))}|{r.get('searches','NA')} / {r.get('searchNodes','NA')}|")
lines += ['', 'CW Multi выигрывает по первому лексикографическому критерию:3 контакта против2. При этом полезное время37 против38 s, modeled CPU0.83 против0.12 s, management1240 против160 B и2 против0 новых ключей. Host-время контроллера примерно32.43 против2.54 s; оно не списывается с контактного времени в этой offline-паре. Поэтому данный результат не доказывает преимущество при физическом учёте затрат вычисления.',
'', 'CAN Multi остановлен после180.0153904 host seconds при модельном t=4.75 s. Его префикс `(1,3.75,0.06,80,0)` сохранён отдельно, finalScore отсутствует. Имеется19 поисков:1 COMPLETE,17 NODE_LIMIT и1 CONTROLLER_TIME_LIMIT. Сравнение миссии CAN остаётся неизвестным. Изменять solver или автоматически повышать лимит для закрытия этой клетки не стали.',
'', '## Последствия раскрытия — без новых запусков','',
'В обеих CANONICAL клетках E−5/delay0 и E−5/delay2: Next=`(3,410,2.17,3336,6)`, Multi=`(3,400,2.82,4336,8)`. Multi теряет10 s полезного времени и дополнительно тратит0.65 s modeled CPU,1000 management B и2 ключа. Обе политики конфиденциально доставляют4000 B; полная задержка compromise→independent frame равна5559.211669922 s. Для delay0 ground-relative frame delay также5559.211669922 s; для delay2 —5557.211669922 s. Уведомление, активация, SA и кадр остаются раздельными метриками.',
'', 'Ранний independent g2 у Multi активируется через1.4 s после detection, но истекает до возврата. В этих клетках ранняя активация не даёт раннего SA или кадра. Меньшее раскрытое число emitted bytes сосуществует с меньшей передачей и прерванным кадром; это не дополнительная confidential delivery. Весь прежний disclosure-вывод остаётся зависимым от tie rule и не оценивает вероятность компрометации.',
'', '## Артефакты и оставшиеся вопросы','',
'`article.docx` —13 страниц; `supplement.docx` —14 страниц. Соответствующие LaTeX находятся в article-tex. Main сохраняет20 релевантных источников и269-word /1672-character abstract. Native OMML не назывался MathType. Подтверждённой совместной доли Scopus/WoS пока нет;0/20 непосредственно проверенных в обеих базах означает пробел свидетельств, а не фактическое отсутствие индексации. Подробности —submission-checklist.md.',
'', 'Новые данные не опубликованы в старом release research. Public URL новой редакции: **[TO BE ADDED AFTER PUBLIC DEPOSITION]**. Статья не объявляется готовой к подаче. Полный Maven и исторические кампании не запускались; проверены диагностическая обвязка, её реальные6+4 исполнения, независимый пересчёт, компаратор/полные replay, byte-identical rebuild и восстановление таблиц из архива. PDF используется только для внутренней проверки.',
'', 'Итог: новый offline практический выигрыш подтверждён для одного правила и заданного бюджета. Общее утверждение о превосходстве Multi не появляется. CAN полное исполнение, переносимость на другие бюджеты/нагрузки и эффект физически оплачиваемых150k/30s вычислений остаются неустановленными.']
(DOC/'report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Wrote six-concern report with separate 6-root / 4-controller denominators')
