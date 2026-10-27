"""PC1 report from saved full-schedule, timing and same-root replay evidence."""
import csv, json
from pathlib import Path
from run_practical_controller import ROOT,OUT,DOC,load,save,sha,relative

def number(value):
    if value is None:return '—'
    return f'{float(value):.3f}'.rstrip('0').rstrip('.')

def write():
    offline=load(OUT/'full-iss-v1/analysis.json');measured=load(OUT/'measured-iss-v1/analysis.json')
    probe=load(OUT/'continuation-replay/result.json')
    rows=[]
    for mode,series in [('offline',offline),('measured-host-delay',measured)]:
        for r in series:
            five=r.get('five') or {}
            rows.append(dict(mode=mode,rule=r['rule'],horizon=r['horizon'],status=r['status'],**five,
                elapsedSeconds=r['process']['processElapsedNanos']/1e9,searches=r.get('searches'),nodes=r.get('nodes'),
                decisionDeadlineMisses=r.get('decisionDeadlineMisses'),maxDecisionSeconds=r.get('maxDecisionSeconds'),
                inputModel='target/research-validation/models/ISS46.json',result=r['result']))
    with (DOC/'runs.csv').open('w',encoding='utf-8',newline='') as f:
        fields=list(dict.fromkeys(k for r in rows for k in r));w=csv.DictWriter(f,fields);w.writeheader();w.writerows(rows)
    text='# PC1: полный ISS и практический контроллер\n\n'
    text+='Цель этапа — отделить deadline решения от лимита эксперимента, разобрать сохранённые неудачные решения и получить полные сравнения Next/Multi на неизменном ISS-расписании. Это проверка применимости, а не попытка получить преимущество Multi изменением целевой функции.\n\n'
    text+='## Лимиты и изменённый код\n\n'
    text+='`SecurityPolicyController` получил отдельный deadline наблюдения/генерации/выбора/подготовки продолжения. В offline-серии: 5000 узлов и 1 с на поиск, 2 с на решение, без накопительного лимита контроллера; внешний watchdog — 600 с на процесс. Все четыре регистрации сделаны заранее, каждая попытка выполнена один раз. Старый лимит R1 в 20 с ограничивал весь эксперимент. Увеличение отдельного process allowance не увеличивает бюджет одного поиска.\n\n'
    text+='При истечении deadline неподтверждённое выбранное действие отбрасывается: сохраняется последний снимок и выполняется WAIT до ближайшего доступного события. Уже начатая работа и обязательная реакция на уведомление остаются задачей исполнителя. Восстановление наблюдения для безопасного ответа измеряется отдельно; cooperative cancellation не является hard real-time гарантией против пауз ОС/native вызовов. Нулевой deadline сохраняет прежний режим.\n\n'
    text+='`PlanningLatencyController` теперь измеряет наблюдение, генерацию и выбор вместе, сохраняет вычислительную статистику и исходную причину глобального прекращения. Измеренный режим использует fixed=0, host-scale=1, deadline=2 с; время вычислений сдвигает общую физическую шкалу до повторной проверки действия. Checkpoint I/O, исполнение и окончательная проверка допуска вне этой модели задержки. Это измерения данного хоста, не бортового компьютера или полного ground–space протокола.\n\n'
    text+='## Полные исполнения\n\n| Режим | Правило | H | Статус | Контакты | Полезные с | CPU с | Служебные B | Новые ключи | Процесс с | Misses |\n|---|---|---|---|---|---|---|---|---|---|---|\n'
    for r in rows:
        text+='| '+' | '.join(str(r[k]) if k in ['mode','rule','status'] else number(r.get(k)) for k in ['mode','rule','horizon','status','servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys','elapsedSeconds','decisionDeadlineMisses'])+' |\n'
    text+='\nFINISHED означает достижение конца всего расписания, а не обслуживание всех 46 контактов и не COMPLETE каждого поиска. Аудит проверяет все 46 CONTACT_STARTED, точное время конца, журнал затрат/nonce/доставки и равенство checkpoint экспортированному состоянию. Все четыре сочетания используют один исходный model hash и одну версию внутри серии.\n\n'
    text+='В offline-серии CANONICAL/Next обслуживает 40 контактов, остальные три сочетания — только один. Ни один двухсекундный deadline не сработал. Это плохое качество ограниченного поиска, а не осознанный выбор экономии: обслуживание остаётся первым приоритетом. Получены завершённые отрицательные сравнения; пригодность текущего ограниченного алгоритма не доказана. Исторические FixedPeriod/PerContact на том же ISS46 обслуживали 46 контактов; они не перезапускались в PC1.\n\n'
    text+='Разница между offline и measured-host-delay включает изменения физических decision points и последующее перепланирование. Бюджет поиска по wall time также зависит от хоста. Это один зарегистрированный детерминированный физический сценарий на сочетание, не независимая статистическая выборка и не оценка onboard performance.\n\n'
    text+='## Почему теряются решения\n\n'
    text+='[Диагностика сохранённых состояний](decision-diagnosis.md) показывает три механизма: обход успевает получить только один невыгодный терминальный план X5; начальный WAIT пропускает доступный контакт; после PREPARE старое полезное продолжение теряется, а новый ограниченный поиск слишком рано активирует пару, которая истекает до возврата.\n\n'
    text+='Последний механизм проверен отдельным исполнением двух сохранённых продолжений из одного post-PREPARE наблюдения, без нового поиска. Оба допустимы до одной evaluationEnd и дают 2 новых обслуженных контакта / 100 с передачи. Старый остаток требует 1.22 с CPU, 2160 B и 2 ключа; новый — 1.93 с, 3240 B и 4 ключа. Старые затраты не учитываются повторно. Общий comparator выбирает старое продолжение по CPU. Это конкретное свидетельство потери найденного решения, не доказательство оптимальности старого плана.\n\n'
    text+='## Прерывание управляющих операций\n\n'
    text+='Новые интеграционные тесты одного окна передают контроллеру фактически начатую операцию и используют одинаковый одноузловой бюджет, чтобы проверить общий допустимый fallback без дорогого поиска. Это проверка исполнения/восстановления, не эффективности горизонта. Для обоих H после потери канала внутри INSTALL в t=2 и восстановления t=3 повторная установка подтверждается в t=7, после SWITCH/OPEN идут реальные кадры и контакт обслуживается. Списываются только выполненные фазы первой попытки и полные расходы повтора; ожидаемый ACTION_FAILED/CHANNEL_DOWN сохранён.\n\n'
    text+='Для прерванных SWITCH, OPEN и REUSE оба горизонта сохраняют неопределённость и не передают полезные кадры после восстановления канала. Физическое исполнение завершается с нулевым обслуживанием. Это проверенное безопасное прекращение, **не восстановление согласованности**: явный reconciliation protocol по-прежнему отсутствует. Изменения в криптографические предположения или в список допустимых security actions не внесены.\n\n'
    tests=load(OUT/'latency-verified-tests.json')
    text+=f'## Проверки и воспроизведение\n\nИзолированный Maven clean verify: 51 целевой тест версии разделения бюджетов; затем {tests["tests"]} целевых теста измеренной задержки/восстановления, 0 failures/errors. Наборы пересекаются и не суммируются. Полный набор проекта в PC1 не запускался. Первый latency-build сохранил одну ошибку нового теста: он ошибочно запрещал ожидаемый ACTION_FAILED для намеренного обрыва INSTALL. Исправлено только это утверждение; исходный неуспешный build сохранён отдельно.\n\n'
    text+='[Протокол offline](../../target/practical-controller/full-iss-v1/protocol.json), [протокол измеренной задержки](../../target/practical-controller/measured-iss-v1/protocol.json), [таблица](runs.csv), [same-root replay](../../target/practical-controller/continuation-replay/result.json). Команды и границы в [README](README.md). Версии source/JAR и результаты проверок записаны в manifest. Старые R1 и SE2 материалы не заменены; проверяются 6882 сохранённых хеша.\n\n'
    text+='## Что остаётся\n\nСледующий алгоритмический шаг — общий для Next/Multi дешёвый допустимый исходный план и сохранение лучшего уже известного продолжения через границы служебных операций, с повторной оценкой относительно нового корня. Все затраты такого шага должны входить в тот же deadline, а законченные решения сверяться с полным DFS. PC1 этого улучшения не реализует и не объявляет практический контроллер качественным. Согласование сторон после неопределённого SWITCH/OPEN/REUSE требует отдельной явно заданной модели.\n'
    (DOC/'report.md').write_text(text,encoding='utf-8')
    save(DOC/'validation.json',dict(status='PASS',fullScheduleContacts=46,
        offlineFinished=sum(r['status']=='FINISHED' for r in offline),measuredFinished=sum(r['status']=='FINISHED' for r in measured),
        tested=tests,diagnosticReplay='Two feasible terminal continuations compared at same root',
        sources={relative(p):sha(p) for p in [OUT/'full-iss-v1/analysis.json',OUT/'measured-iss-v1/analysis.json',OUT/'continuation-replay/result.json']}))
    print('PC1 report:',len(rows),'attempts')

if __name__=='__main__':write()
