"""Results-first report from completed registered attempts; no manuscript edits."""
import collections, json, re, statistics
from pathlib import Path
from analyze_measured_x01 import analyze, load, ns, save_json

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'target/measured-delay-x01-20261028';DOC=ROOT/'docs/measured-delay-x01'
T0=ns('2026-01-01T00:00:00Z')
def fmt(v):return '—' if v is None else f'{float(v):.3f}'.rstrip('0').rstrip('.')
def spread(values):
 values=list(values)
 return '—' if not values else f'{fmt(statistics.median(values))} [{fmt(min(values))}; {fmt(max(values))}]'

def main():
 runs,contacts,decisions,pairs,summary=analyze()
 assert len(runs)==88 and all(r['status'] not in ['NOT_RUN','IN_PROGRESS_OR_INTERRUPTED'] for r in runs),'Series not terminal'
 mainruns=[r for r in runs if r['phase']=='main'];controls=[r for r in runs if r['phase']=='control'];pilots=[r for r in runs if r['phase']=='pilot']
 finished=sum(r['status']=='FINISHED' for r in mainruns);statuses=dict(collections.Counter(r['status'] for r in mainruns))
 outcomes={rule:{k:summary['rules'][rule][k] for k in ['completed_pairs','S_wins','S_ties','S_losses','incomplete_pairs']} for rule in ['CONTINUE_WAIT','CANONICAL']}
 anywins=any(x['S_wins'] for x in outcomes.values())
 headline=('Измеренная задержка не устранила выигрыш во всех завершённых парах: частоты положительных, равных и отрицательных исходов приведены ниже.' if anywins else
 'В завершённых основных парах выигрыш Multi по числу обслуженных контактов не наблюдался после начисления измеренного времени выбора действия.')
 lines=['# X01 с начислением времени принятия решений','',headline,'',
  f'Основная серия: {finished}/80 FINISHED; статусы: `{statuses}`. Контроли и пилоты считаются отдельно. Это результат для X01 и измеренного настольного компьютера, а не универсальное отрицание пользы горизонта.','',
  '## Постановка и контроль','',
  'Сохранены два горизонта H1/H2, оба правила разрешения равенства, пять приоритетов, алгоритм, порядок обхода, seed и retention. X01 содержит A[0,20], B[30,50], A[80,85]с. Начало t=-2 перенесено из успешных архивных полных запусков; t=0 в инструкции относится к диагностическому корню. Запасной пары, атак и отказов нет. PREPARE/INSTALL/ключи/SA исполняются общим исполнителем.','',
  'Одинаковые лимиты:150000узлов на поиск,30с поиска,32с выбора,180с контроллера,210с watchdog; Java21.0.10, heap1024MiB, свежая JVM без специального прогрева. Ryzen7 5800HS,8ядер/16потоков, RAM16541487104байта, Windows11 build26200. Все попытки последовательны; в нечётной паре первым идёт Next, в чётной Multi. Фоновая активность ОС не отключалась и причинно не атрибутировалась.','',
  'Перед регистрацией исправлена совместимость measured-контроллера: существующая проверка доказанного остатка теперь включена в измеряемый цикл. Коммит `b2d04b8`;13целевых тестов пройдены. Новый JAR сохраняет6703прочих записей исходного JAR побайтно, включая solver/portfolio/offline-контроллер. Это отдельная версия, не оптимизация поиска.','',
  '| Контроль | Статус | S | U,с | CPU,с | Management,B | Ключи |','|---|---|---:|---:|---:|---:|---:|']
 for r in controls:lines.append(f"|{r['tie_rule']} / H{r['horizon']}|{r['status']}|{fmt(r['S'])}|{fmt(r['U'])}|{fmt(r['control_cpu'])}|{fmt(r['management_bytes'])}|{fmt(r['new_keys'])}|")
 lines+=['','CW offline-различие2против3 и все пять координат воспроизведены. Незавершённый CANконтроль не получает финального нуля. Четыре пилота проверили цикл начисления/повторной проверки; их результаты не входят в80основных попыток.','',
  '## Основные результаты','',
  '| Правило | Полные пары /20 | Победы / равенства / проигрыши Multi по S | Незавершённые | ΔU,с: медиана [min;max] |',
  '|---|---:|---|---:|---|']
 for rule in outcomes:
  s=summary['rules'][rule];d=s['delta_U']
  lines.append(f"|{rule}|{s['completed_pairs']}/20|{s['S_wins']} / {s['S_ties']} / {s['S_losses']}|{s['incomplete_pairs']}|{fmt(d['median'])} [{fmt(d['min'])}; {fmt(d['max'])}]|")
 mainpairs=[p for p in pairs if p['phase']=='main' and p['status']=='COMPLETE']
 full_losses=sum(p['score_outcome']=='LOSS' for p in mainpairs)
 lines+=['','Победы/равенства/проигрыши классифицированы по S только среди полных пар. ΔU и проценты рассчитаны по каждой полной паре отдельно; при нулевом Next процент отсутствует. Полный лексикографический исход также сохранён в `tables/pairs.csv`.',
  f'По полному порядку пяти критериев Multi проиграл в {full_losses}/{len(mainpairs)} полных пар; равенство по S само по себе не является равенством результата.','',
  '| Правило / политика | FINISHED /20 | S: частоты | U,с | Host контроллер,с | Выбор,мс |',
  '|---|---:|---|---|---|---|']
 for rule in outcomes:
  for h in (1,2):
   group=[r for r in mainruns if r['tie_rule']==rule and r['horizon']==h];complete=[r for r in group if r['status']=='FINISHED']
   ds=[d for d in decisions if d['phase']=='main' and d['tie_rule']==rule and d['horizon']==h]
   lines.append(f"|{rule} / {'Next' if h==1 else 'Multi'}|{len(complete)}/20|{dict(sorted(collections.Counter(r['S'] for r in complete).items()))}|{spread(r['U'] for r in complete)}|{spread(r['controller_elapsed_seconds'] for r in group if r.get('controller_elapsed_seconds') is not None)}|{spread(d['host_nanos']/1e6 for d in ds)}|")
 lines+=['','В трёх последних столбцах указаны медиана[min;max]; длительности выбора агрегированы по отдельным решениям, а не средним по запускам. Полные UL/DL, расходы операций, служебные байты, созданные ключи, узлы и причины остановки доступны для каждой попытки в `tables/runs.csv`, для каждого контакта —в `tables/contacts.csv`.','',
  '| Правило / политика | CPU операций,с | Служебные байты | Новые направленные ключи |',
  '|---|---|---|---|']
 for rule in outcomes:
  for h in (1,2):
   group=[r for r in mainruns if r['tie_rule']==rule and r['horizon']==h and r['status']=='FINISHED']
   lines.append(f"|{rule} / {'Next' if h==1 else 'Multi'}|{spread(r['control_cpu'] for r in group)}|{spread(r['management_bytes'] for r in group)}|{spread(r['new_keys'] for r in group)}|")
 lines+=['','Здесь также медиана[min;max]. В этих основных попытках расходы операций совпали; различие результатов определяется обслуживанием и полезным временем. Новых направленных ключей фактически не создано.','',
  '## Механизм и границы измерения','']
 mechanism=[]
 for rule in outcomes:
  representatives=summary['rules'][rule]['representative_pairs']
  for outcome,pair in representatives.items():
   rid=pair+'-H2';raw=load(BASE/'runs'/rid/'latency-decisions.json');initial=load(BASE/'runs'/rid/'initial-checkpoint.json')['snapshot']
   # Optimistic necessary bound before any new material or pending PREPARE/INSTALL.
   # Other committed work cannot shorten PREPARE2s+INSTALL4s, even with free resources.
   lost=None
   for i,d in enumerate(raw):
    o=d['origin'];at=ns(o['at'])-T0;after=ns(d['availableAt'])-T0
    if not (0<=at<20_000_000_000 and after>14_000_000_000):continue
    keys=o['securityState']['keys']
    if set(keys)!=set(initial['securityState']['keys']) or any(p['action'] in ['PREPARE','INSTALL','ROTATE'] for p in o['ongoingOperations']):continue
    lost={'run_id':rid,'decision_id':i,'at_s':at/1e9,'available_s':after/1e9,'host_seconds':d['hostNanos']/1e9,
     'selected':(d['selection'].get('action') or {}).get('kind'),'disposition':d['disposition'],
     'bound':'No prepared material/pending key preparation or installation: even with free resources PREPARE2+INSTALL4 requires availability <=14 for C1 end20; necessary condition only'};break
   if lost:
    mechanism.append(lost)
    lines.append(f"В представительной паре `{pair}` ({outcome} по S) решение Multi№{lost['decision_id']} началось в t={fmt(lost['at_s'])} и стало доступно в t={fmt(lost['available_s'])}; измерено{fmt(lost['host_seconds'])}с, намерение{lost['selected']}, исход `{lost['disposition']}`. В наблюдении нет нового материала и выполняемой подготовки/установки ключей: даже при свободных ресурсах PREPARE2с+INSTALL4с уже не помещается до t=20. Это необходимая временная граница, не дополнительный поиск контрфактического оптимума.")
    c1=[d for d in raw if 0<=ns(d['origin']['at'])-T0<20_000_000_000][:2]
    lines.append('Первые циклы выбора внутри C1: '+ '; '.join(f"t={fmt((ns(d['origin']['at'])-T0)/1e9)}→{fmt((ns(d['availableAt'])-T0)/1e9)}, {(d['selection'].get('action') or {}).get('kind')}, {d['disposition']}" for d in c1)+'.')
    lines.append('')
   else:lines.append(f"Для `{pair}` простой диагностический предел PREPARE2+INSTALL4 не применим; не заявляем его причиной без разбора состояния. Полный журнал сохранён.")
 lines+=['','Начисляется каждый полный цикл выбора: observation/candidates, проверка/подготовка доказанного остатка, seed, проверка допустимого продолжения, DFS и сравнение. Во время него общая шкала продвигает уже начатую работу и события. Затем выполняется новая проверка действия. Сохранённый доказанный план не становится сертификатом для сдвинутого кадра; это проверено тестами и отражено в guard-журнале.','',
  'Запись checkpoint, свежая проверка допуска, фактическое исполнение, старт JVM и финальный экспорт не начисляются как время выбора. Эти расходы входят в соответствующие host/process измерения. Поэтому эксперимент не моделирует полностью все вычислительные накладные расходы бортовой системы. Модельная CPUстоимость GS+SC не является hostCPU/time поиска. В показателях задержки нет ретроспективного вычитания суммарного времени из контактов.','',
  '![Фактические временные шкалы](figures/measured-timelines.png)','',
  'Рисунок выбран воспроизводимо: для каждого правила и наблюдаемого S-исхода берётся полная пара с ΔU, ближайшим к медиане группы; равенство разрешается по ID. Пилоты не участвуют. Непрерывность готовности не выводится из одной активации; обслуживание подтверждено доставленными кадрами по400байт в обоих направлениях.','',
  '## Вывод и материалы','',
  headline+' Offline-допустимость полезного плана и возможность вычислить/исполнить его вовремя —разные утверждения. Границы относятся к одному сценарию, двум правилам и данному режиму настольной JVM. Они не доказывают отсутствие пользы Multi при иных алгоритмах/бюджетах/оборудовании и не дают оценки вероятности компрометации. Новых оптимизаций или искусственно подобранных задержек в этой серии нет.','',
  'Все исходные попытки, включая лимиты, сохранены; итоговые S/U незавершённых запусков пусты, префиксы помечены отдельно. Команды, manifest и исходники находятся в README и пакете воспроизведения. Статья и её формат пока не изменены: сначала эти результаты передаются пользователю для согласования.']
 text='\n'.join(lines)+'\n'
 text=re.sub(r'([А-Яа-яЁё])(?=\d)',r'\1 ',text)
 text=re.sub(r'(\d)(?=[А-Яа-яЁё])',r'\1 ',text)
 for old,new in [('CW offline-различие','Offline-различие CW'),('CANконтроль','контроль CAN'),('CPUстоимость','CPU-стоимость'),('hostCPU/time','host CPU/time'),('—разные','— разные'),('—в','— в'),('намерениеOPEN','намерение OPEN'),('намерениеWAIT','намерение WAIT'),('Multi№','Multi №'),('PREPARE2','PREPARE 2'),('INSTALL4','INSTALL 4')]:text=text.replace(old,new)
 DOC.joinpath('report.md').write_text(text,encoding='utf-8')
 save_json(DOC/'mechanism.json',mechanism)
 proposal=['# Proposed article updates pending approval','',
 'These are proposed statements only. No manuscript file has been edited.','',
 '**Experimental design.** We repeated the autonomous X01 scenario using the archived physical model and150000nodes per search. Four offline controls and four measured-delay pilots preceded80registered main attempts:20Next/Multi pairs for each of two tie-breaking rules. Fresh Java21 processes ran serially without explicit warmup, alternating which horizon ran first. The measured decision pipeline was charged before fresh intent admission; other host overhead was reported separately.','',
 '**Results.** '+f'The main series completed {finished}/80 executions. '+ '; '.join(f"{rule}: {v['completed_pairs']}/20 complete pairs, with {v['S_wins']} service wins, {v['S_ties']} ties and {v['S_losses']} losses for Multi" for rule,v in outcomes.items())+'. Unfinished executions were not assigned final service scores.','',
 '**Discussion.** The offline horizon advantage describes a feasible and discoverable continuation under the tested budget; execution with measured decision latency can consume the very contact capacity needed to realize it. The observation is specific to X01 and the tested desktop/JVM timing boundary.','',
 '**Conclusion.** Report the observed paired outcomes above alongside the preserved offline gain. Do not generalize to all hardware, contact schedules or tie rules.','',
 '**Abstract.** Revise last, after approval, to include the registered main-series denominator and both offline and measured outcomes. Do not replace the current abstract automatically.']
 DOC.joinpath('proposed-article-updates.md').write_text('\n'.join(proposal)+'\n',encoding='utf-8')
 print('REPORT WRITTEN; article unchanged')

if __name__=='__main__':main()
