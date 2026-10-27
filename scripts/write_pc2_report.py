"""Generate the Russian PC2 README from all 16 initial and 6 corrected saved attempts.

Reads results only; never launches experiments or raises budgets. Both analysis
files must contain every registered case, including unsuccessful/partial cases.
"""
import collections
from decimal import Decimal
from pathlib import Path
from run_pc2 import ROOT, OUT, PC1, load, relative

FIELDS = ('servedContacts', 'usefulSecureTime', 'controlCpu', 'managementBytes', 'newKeys')
REPLANS = ('WAIT_EVENT_REACHED_REPLAN', 'WAIT_EVENT_CHANGED_REPLAN', 'STALE_INTENT_REPLAN')
CAUSES = {'ELAPSED_WAIT_RETARGETED_TO_CONTACT_END': 'просроченный WAIT переназначен до LOS',
          'NO_FRAMES_NO_ONLINE_SETUP': 'нет кадров и online setup в сохранённом окне',
          'SETUP_OR_DELIVERY_BELOW_THRESHOLD': 'setup/доставка ниже порогов'}


def num(value):
    if value is None:
        return '—'
    try:
        return format(Decimal(str(value)).normalize(), 'f')
    except Exception:
        return str(value)


def seconds(value):
    return '—' if value is None else f'{float(value):.3f}'


def link(path, label):
    return f'[{label}](../../../{relative(path)})'


def directory(row):
    return OUT / row['_series'] / 'runs' / row['id']


def run_link(row):
    return link(directory(row) / 'invocation.json', row['id'])


def status(row):
    if row.get('status')=='NOT_RUN': return 'NOT_RUN'
    return 'FINISHED' if row.get('status') == 'FINISHED' else f"PARTIAL ({row.get('status', 'UNKNOWN')})"


def metrics(row):
    value = row.get('five') or row.get('prefixFive') or {}
    return [num(value.get(field)) for field in FIELDS]


def five(value):
    return ' / '.join(num(value.get(field)) for field in FIELDS)


def metric_key(value):
    return tuple(Decimal(str(value[field])) * (-1 if i < 2 else 1) for i, field in enumerate(FIELDS))


def counts(value):
    return '; '.join(f'{key}: {count}' for key, count in sorted(value.items())) or '0'


def read_series(name, version, expected):
    folder = OUT / name
    protocol, rows = load(folder / 'protocol.json'), load(folder / 'analysis.json')
    registered, actual = [c['id'] for c in protocol['cases']], [r['id'] for r in rows]
    if len(registered)!=expected or len(set(actual))!=len(actual) or not set(actual).issubset(registered):
        raise RuntimeError(f'{name}: analysis/registration mismatch')
    if len(rows)!=expected:
        if name=='iss46-v1':raise RuntimeError('Initial series must be complete')
        rows += [dict(c,status='NOT_RUN',process={}) for c in protocol['cases'] if c['id'] not in actual]
    if protocol['binary']['jar'].replace('\\', '/') != f'target/practical-controller-pc2/candidate-{version}.jar':
        raise RuntimeError(f'{name}: unexpected binary identity')
    missed = load(folder / 'missed-contacts.json')
    if any(entry['run'] not in set(registered) for entry in missed):
        raise RuntimeError(f'{name}: missed-contact evidence references an unregistered run')
    return protocol, [dict(r, _series=name, _version=version) for r in rows], missed


def outcome_table(lines, rows, previous=None):
    lines.extend(['| Версия / режим | Правило / H или контроль | Статус | PC1 контакты | Контакты | Полезные с | CPU операций с | Management B | Ключи | Процесс с | Misses | Попытка |',
                  '|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|'])
    for row in rows:
        old = (previous or {}).get((row['measured'], row['rule'], row['horizon']), {}) if row['mode'] == 'practical' else {}
        label = f"{row['rule']} / H{row['horizon']}" if row['mode'] == 'practical' else row['mode']
        elapsed = row.get('process', {}).get('processElapsedNanos')
        values = [f"{row['_version']} / {'measured' if row['measured'] else 'offline'}", label, status(row),
                  num((old.get('five') or {}).get('servedContacts')), *metrics(row),
                  seconds(None if elapsed is None else elapsed / 1e9), num(row.get('deadlineMisses')), run_link(row)]
        lines.append('| ' + ' | '.join(values) + ' |')


def replan_work(row):
    path = directory(row) / 'latency-decisions.json'
    if not path.exists():
        return None
    decisions = load(path)
    selected = [d for d in decisions if d['disposition'] in REPLANS]
    return dict(counts=collections.Counter(d['disposition'] for d in selected), decisions=len(decisions),
                hostSeconds=sum(d['hostNanos'] for d in selected) / 1e9,
                modeledSeconds=sum((Decimal(str(d['modeledLatency'])) for d in selected), Decimal(0)))


def main():
    # Validate registrations and all analysis rows before touching the existing README.
    p2, initial, missed2 = read_series('iss46-v1', 'v2', 16)
    p3, corrected, missed3 = read_series('measured-v4b', 'v4', 6)
    technical=[dict(r,_series='measured-v4',_version='v4') for r in load(OUT/'measured-v4/analysis.json')]
    attempted=sum(r['status']!='NOT_RUN' for r in initial+corrected+technical)
    if any(not r['measured'] for r in corrected):
        raise RuntimeError('The corrected series must contain only six measured attempts')
    if p2['model'] != p3['model'] or p2['budgets'] != p3['budgets']:
        raise RuntimeError('The corrected series changed model or budgets')
    primary = [r for r in initial if not r['measured'] and r['mode'] == 'practical' and r['portfolio'] == 'SEED_AND_RETENTION']
    primary += [r for r in corrected if r['mode'] == 'practical' and r['portfolio'] == 'SEED_AND_RETENTION']
    controls = [r for r in initial if not r['measured'] and r['mode'] != 'practical'] + [r for r in corrected if r['mode'] != 'practical']
    seedonly = [r for r in initial if not r['measured'] and r['portfolio'] == 'SEED_WITHOUT_SEARCH']
    original_measured = [r for r in initial if r['measured']]
    if tuple(map(len, (primary, controls, seedonly, original_measured))) != (8, 4, 4, 6):
        raise RuntimeError('Require final8, controls4, offline seed-only4, original measured6')
    tests = {version: load(OUT / f'candidate-{version}-tests.json') for version in ('v2', 'v3', 'v4')}
    registration = load(ROOT / 'docs/practical-controller/pc2/latency-correction-registration.json')
    roots = load(OUT / 'candidate-v2-root-ablation/results/results.json')
    contact_count = len(load(ROOT / p2['model']['file'])['scenario']['contacts'])
    previous = {}
    for measured, series in ((False, 'full-iss-v1'), (True, 'measured-iss-v1')):
        for row in load(PC1 / series / 'analysis.json'):
            previous[measured, row['rule'], row['horizon']] = row
    doc = ROOT / 'docs/practical-controller/pc2'
    lines = ['# PC2: допустимый исходный план и сохранение продолжения', '',
        f'PC2 меняет выбор ограниченного контроллера, сохраняя пять критериев, допустимые действия, costs и constraints. Итоговые восемь практических конфигураций состоят из четырёх offline v2 и четырёх corrected measured v4. Сохранены все {attempted} фактических попытки: исходные16, отдельная техническая ошибка checkpoint и новая зарегистрированная measured-серия. Автоматических повторов и повышения бюджетов нет; один технический повтор описан отдельно.', '',
        '## Причины и алгоритмическое исправление', '',
        'PC1 принимал найденный терминальный incumbent, используя bootstrap только при отсутствии такого плана; плохой WAIT мог вытеснить доступное обслуживание. Сохранение ограниченного продолжения заканчивалось после служебного действия, поэтому новый поиск мог потерять найденный более дешёвый вариант. Для post-PREPARE есть сохранённое сравнение двух допустимых продолжений из одного корня. Для ранних WAIT новые альтернативы проверены отдельно в PC2; их оценки не приписываются задним числом PC1. См. [фикстуры и границы свидетельств](fixtures-note.md).', '',
        '`FeasibleSeedPlanBuilder` создаёт общий допустимый исходный план. `PracticalPlanPortfolio` сравнивает seed, заново исполненный остаток, его дополнение до расширенного горизонта и терминальный результат DFS из одного корня до одной evaluationEnd. DFS не получает новых отсечений или иной целевой функции. Старые score/costs не переносятся; проверяются время, состояние, counters, progress, pending work, uncertainty, IDs и видимый прогноз. [Контракт](contract.md) описывает допуск и инвалидацию.', '',
        'Гарантия локальная: завершённое решение выбирает лучший полностью оценённый допустимый кандидат по пяти критериям и полному tie-break. Это не доказательство оптимальности ограниченного поиска или превосходства Multi на всей миссии. SAFE_FALLBACK, прекращённая работа и сертифицированный точный suffix выделяются отдельно.', '',
        '## Три сохранённых корня: абляция', '',
        'Порядок метрик: контакты / полезные секунды / CPU операций в секундах / management bytes / новые directional keys.', '',
        '| Корень | PC1 | + seed | + seed и retained |', '|---|---|---|---|']
    seed_improved = retained_improved = 0
    for root in roots:
        baseline, seed, retained = root['pc1']['evaluation'], root['SEED_ONLY']['selected']['evaluation'], root['SEED_AND_RETENTION']['selected']['evaluation']
        seed_improved += metric_key(seed) < metric_key(baseline)
        retained_improved += metric_key(retained) < metric_key(seed)
        lines.append(f"| {root['fixture']} | {five(baseline)} | {five(seed)} | {five(retained)} ({root['SEED_AND_RETENTION']['source']}) |")
    lines += ['', f'По сохранённым оценкам seed улучшает лексикографический результат по пяти метрикам относительно PC1 в {seed_improved}/{len(roots)} корнях; retention дополнительно улучшает его относительно seed в {retained_improved}/{len(roots)}. Полный tie-break может выбирать другой план при равных метриках. Во всех вариантах использован один сохранённый терминальный результат PC1, чтобы отделить seed/retention от wall-time вариаций нового DFS. Планы заново исполнены до endpoint; это отдельное свидетельство, а не сертификат оптимальности.', '',
        '## Исправление measured: просроченный WAIT и новое решение', '',
        'В исходном measured v2 подтверждён дефект временной проверки: расчёт WAIT мог закончиться после события, до которого он был выбран. Проверка `sameIntent` игнорировала эффективную точку пробуждения и сопоставляла такой WAIT с более поздним WAIT до LOS. Это теряло оставшуюся часть окна после уже завершённого SWITCH. Причина установлена по сохранённым observation/action/availableAt, а не только по числу пропусков.', '']
    e, source_run = registration['evidence'], ROOT / registration['evidenceRun']
    lines += [f"Конкретный пример: корень `{e['origin']}`, ожидаемое пробуждение `{e['effectiveWake']}`, результат доступен `{e['availableAt']}`, исполненный WAIT до `{e['executedUntil']}`. Источники: {link(source_run / 'elapsed-wait-targets.json', 'WAIT-свидетельства')} и {link(source_run / 'latency-decisions.json', 'измеренные решения')}.", '',
        'Узкая коррекция v4: прошедшее или исчезнувшее событие WAIT не заменяется более поздним WAIT. Исчезновение прежнего wake, например после прерывания INSTALL, требует WAIT_EVENT_CHANGED_REPLAN; наступившее событие — WAIT_EVENT_REACHED_REPLAN. Контроллер заново наблюдает состояние и принимает решение; другие устаревшие намерения после сдвига физического времени также возвращаются к проверке через новое решение. Начатая работа сохраняется. Повторные observation/generation/selection оплачиваются заново в том же измеренном режиме; это не бесплатный поиск и не увеличение бюджета. Модель, objective, comparator и пространство кандидатов не меняются.', '',
        '[Регистрация коррекции](latency-correction-registration.json) заранее ограничивает повторную серию четырьмя measured Next/Multi и двумя measured controls. Исходные measured результаты остаются ниже. Offline не перезапускался: исправляется measured temporal-admission path. Поэтому сравнение offline v2 с measured v4 включает физическую задержку и явную версию этой проверки; это два разных JAR.', '',
        '## Версии и единые лимиты', '', '| Назначение | JAR | SHA-256 | Регистрация |', '|---|---|---|---|']
    for label, protocol, series in (('Исходные16, включая итоговые offline', p2, 'iss46-v1'), ('Исправленные measured6', p3, 'measured-v4b')):
        binary = protocol['binary']
        lines.append(f"| {label} | {link(ROOT / binary['jar'], Path(binary['jar']).name)} | `{binary['sha256']}` | {link(OUT / series / 'protocol.json', series)} |")
    b = p2['budgets']
    lines += ['', f"Model hash `{p2['model']['sha256']}`; контактов в расписании: {contact_count}. Лимиты: {b['nodesPerSearch']} узлов / {b['searchMilliseconds']} мс на поиск; {b['decisionMilliseconds']} мс на решение; seed/replay по {b['seedStageMilliseconds']}/{b['retainedStageMilliseconds']} мс внутри deadline; {b['processSeconds']} с process watchdog. Controller limit {b['controllerSeconds']} означает отсутствие накопительного лимита. Fixed=0, host-scale=1 в measured. Process watchdog не расширяет бюджет решения.", '',
        '## Итоговые восемь практических исполнений: PC1 → PC2', '']
    outcome_table(lines, primary, previous)
    finished = [r for r in primary if r.get('status') == 'FINISHED']
    fully_served = [r for r in finished if Decimal(str((r.get('five') or {}).get('servedContacts', -1))) == contact_count]
    service_summary = f'все {contact_count} контактов обслужены в {len(fully_served)}/{len(finished)} завершённых исполнений' if finished else 'завершённых исполнений нет; итоговое обслуживание не установлено'
    lines += ['', f'Фактический итог этих строк: FINISHED {len(finished)}/{len(primary)}; {service_summary}. FINISHED означает конец физического расписания, а не COMPLETE каждого поиска. Строки PARTIAL показывают только накопленные значения сохранённого префикса, если доступны; им не назначаются финальные показатели или миссионный проигрыш.', '']
    for measured in (False, True):
        for rule in ('CANONICAL', 'CONTINUE_WAIT'):
            pair = {r['horizon']: r for r in primary if r['measured'] == measured and r['rule'] == rule}
            label = f"{'measured v4' if measured else 'offline v2'}, {rule}"
            if all(pair[h].get('status') == 'FINISHED' and pair[h].get('five') for h in (1, 2)):
                next_score, multi_score = metric_key(pair[1]['five']), metric_key(pair[2]['five'])
                verdict = 'H1 и H2 совпали по всем пяти миссионным метрикам' if next_score == multi_score else 'H2 лучше H1 по порядку пяти метрик' if multi_score < next_score else 'H2 хуже H1 по порядку пяти метрик'
                lines.append(f'- {label}: {verdict} в этих зарегистрированных исполнениях.')
            else:
                lines.append(f'- {label}: есть незавершённый результат; сравнение финальных миссионных метрик не сформировано.')
    lines += ['', 'Это единичные исполнения детерминированной физической модели на данном хосте. Wall-time остановки поиска и физические decision points зависят от времени вычислений; результаты не являются статистической выборкой или бортовой калибровкой.', '',
        '## Контроли: offline v2 и corrected measured v4', '',
        'FixedPeriod: T=3600 с от успешного SWITCH; PerContact: JUST_IN_TIME. Measured controls оплачивают наблюдение, генерацию и собственное правило выбора, без фиктивной стоимости DFS.', '']
    outcome_table(lines, controls)
    lines += ['', '## Четыре исходные offline-диагностики только seed', '']
    outcome_table(lines, seedonly)
    lines += ['', 'Это заранее зарегистрированная SEED_WITHOUT_SEARCH-серия v2. Она не подменяет абляцию одного корня и не расширяет сетку спутников, атак или параметров.', '',
        '## Все шесть исходных measured v2: сохранены отдельно', '',
        'Следующие результаты входят в исходные16. Они не удалены и не смешаны с corrected measured v4 в итоговой восьмёрке. Коррекция не превращает исходные результаты в другие значения.', '']
    outcome_table(lines, original_measured)
    lines += ['', '## Отдельный PROCESS_ERROR и ограниченный технический повтор', '',
        'Первый запуск candidate-v4 остановился при atomic rename checkpoint с AccessDeniedException и ACCESS_DENIED_TIME_EXHAUSTED. Последний корректный JSON читается, снимок и диагностический журнал сохранены. Причина блокировки не установлена. Пять следующих конфигураций той регистрации не запускались из-за stop-on-PROCESS_ERROR.', '',
        '[Отдельная регистрация](technical-repeat-registration.json) разрешила ровно один повтор этой технически прерванной конфигурации и первые исполнения пяти оставшихся с тем же JAR, входами и бюджетами. Живой checkpoint во второй серии не читался; это ограничение процедуры, а не доказательство причины прежней блокировки. Retry policy и production-код checkpoint не менялись. При повторном PROCESS_ERROR дальнейших повторов нет.', '']
    outcome_table(lines,technical)
    lines += ['', '## Повторные measured-решения v4 и оплаченная задержка', '',
        '| Попытка | WAIT_EVENT_REACHED_REPLAN | WAIT_EVENT_CHANGED_REPLAN | STALE_INTENT_REPLAN | Решений всего | Host time отклонённых решений, с | Их modeled delay, с |', '|---|---:|---:|---:|---:|---:|---:|']
    for row in corrected:
        work = replan_work(row)
        if work is None:
            lines.append(f'| {run_link(row)} | — | — | — | — | — | — |')
        else:
            c = work['counts']
            lines.append(f"| {run_link(row)} | {c.get(REPLANS[0], 0)} | {c.get(REPLANS[1], 0)} | {c.get(REPLANS[2], 0)} | {work['decisions']} | {seconds(work['hostSeconds'])} | {num(work['modeledSeconds'])} |")
    lines += ['', 'Суммированы уже оплаченные решения с replan по `latency-decisions.json`. Следующие решения имеют собственные записи и задержку; все они входят в физическое исполнение. Host time/modelled delay — время, не operation CPU или thread CPU. Отсутствующий журнал не считается нулевым расходом.', '',
        '## Работа поиска, источники выбора и CPU', '',
        '| Версия / правило / H | Seed | Retained | Search | Fallback | Точные suffix | Поиски | Узлы | Seed с | Replay с | Search с | Portfolio thread CPU с | Search thread CPU с | Переходы |', '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for row in primary:
        source = row.get('sources', {})
        values = [f"{row['_version']} / {row['rule']} / H{row['horizon']} ({status(row)})",
                  *(num(source.get(name, 0)) if 'sources' in row else '—' for name in ('SEED', 'RETAINED', 'SEARCH', 'SAFE_FALLBACK')),
                  *(num(row.get(name)) for name in ('certifiedSuffixes', 'searches', 'searchNodes')),
                  *(seconds(row.get(name)) for name in ('seedSeconds', 'suffixSeconds', 'searchSeconds', 'portfolioThreadCpuSeconds', 'searchThreadCpuSeconds')),
                  num(row.get('transitions'))]
        lines.append('| ' + ' | '.join(values) + ' |')
    lines += ['', 'Источник обозначает завершённый выбор на исходном observation. В measured он ещё может быть отклонён после задержки. `practical-portfolio.json` содержит кандидаты/планы/метрики, `practical-portfolio-stops.json` — прекращённую работу. Счётчики завершённых решений не выдаются за полный расход при наличии остановок. Search thread CPU входит в portfolio thread CPU и не суммируется с ним. Portfolio CPU не включает внешние observation/admission; whole-run CPU не называется planning-only CPU. Model operation CPU в таблицах исходов — другой показатель.', '',
        '| Итоговая попытка | Причины retention | Причины остановки DFS | Прекращённых portfolio-вызовов | Selection wall с | Whole-run thread CPU с | Неиспользованные новые ключи / из них retired |', '|---|---|---|---:|---:|---:|---:|']
    for row in primary:
        cpu = row.get('wholeRunTelemetry', {}).get('threadCpuNanos')
        stops = len(row['stoppedPortfolioWork']) if 'stoppedPortfolioWork' in row else '—'
        lines.append(f"| {run_link(row)} | {counts(row['retention']) if 'retention' in row else 'недоступно'} | {counts(row['searchStopReasons']) if 'searchStopReasons' in row else 'недоступно'} | {stops} | {seconds(row.get('selectionSeconds'))} | {seconds(None if cpu is None else cpu / 1e9)} | {num(row.get('unusedCreatedKeys'))} / {num(row.get('discardedUnusedKeys'))} |")
    lines += ['', '`searches` берётся из полного ledger попыток, не из исторического `computation.searches`, который считает лишь явно возвращённые SEARCH-решения. Rollout/prediction transitions учитываются отдельно от узлов DFS. Retired означает DEACTIVATED/DESTROYED в конце сохранённого состояния, не аппаратное стирание.', '',
        '## Причины пропусков и точные журналы', '',
        '| Серия / попытка | Статус | Контактов ниже порога в сохранённых данных | Сохранённые причины | Свидетельства |', '|---|---|---:|---|---|']
    for series, rows, missed in (('iss46-v1', initial, missed2), ('measured-v4b', corrected, missed3)):
        by_run = collections.defaultdict(list)
        for entry in missed:
            by_run[entry['run']].append(entry)
        for row in rows:
            entries = by_run[row['id']]
            if not entries and row.get('status') == 'FINISHED':
                continue
            reasons = collections.Counter(CAUSES.get(e.get('cause'), e.get('cause', 'не классифицировано')) for e in entries)
            available = row.get('audit') == 'PASS' or (directory(row) / 'actual.events.json').exists()
            evidence = f"{link(OUT / series / 'missed-contacts.json', 'контакты и решения')}; {link(directory(row) / 'actual.events.json', 'actual events')}" if available else run_link(row)
            lines.append(f"| {series} / {row['id']} | {status(row)} | {len(entries) if available else '—'} | {counts(reasons) if available else 'журнал недоступен'} | {evidence} |")
    if not missed2 and not missed3 and all(r.get('status') == 'FINISHED' for r in initial + corrected):
        lines.append('| Все строки | По сохранённым анализам | 0 | — | См. analysis.json обеих серий |')
    lines += ['', 'Причины берутся из actual decisions/events, а не угадываются по score. Только явное `ELAPSED_WAIT_RETARGETED_TO_CONTACT_END` подтверждает temporal-intent механизм. Другие категории описывают отсутствие setup или недостаточную доставку и не доказывают контрфактическую возможность обслуживания. Для PARTIAL список может включать ещё не достигнутые окна; это не доказанное число окончательных потерь.', '',
        'Отдельно разобран исходный measured H2/CONTINUE_WAIT, контакт SATNOGS-4451-2. В 10:29:24.586833081 seed начинался INSTALL, retained — WAIT до10:31:37.456954239; оба оценены (3,60,1.93,3240,4), поэтому неизменный полный CONTINUE_WAIT выбрал retained. Это намеренный WAIT, а не temporal-retarget ошибка. Номинальный второй кадр завершался10:32:03.456954239 до LOS10:32:05.270233154. После реальных вычислений OPEN завершился10:31:46.995478439; фактически доставлен только uplink в10:31:57.101603739, второго полного10-секундного кадра уже не вместить. Это свидетельство ограничения номинального прогноза при measured задержках; objective не исправлялась задним числом. [Корень, кандидаты и фактическая последовательность](../../../target/practical-controller-pc2/partial-directional-contact-diagnosis.json).', '',
        '## Проверки и воспроизведение', '',
        '| Изолированная сборка | Tests | Классов | Failures | Errors | Exit code | Источник |', '|---|---:|---:|---:|---:|---:|---|']
    for version, result in tests.items():
        lines.append(f"| candidate-{version} | {num(result.get('tests'))} | {num(result.get('classes'))} | {num(result.get('failures'))} | {num(result.get('errors'))} | {num(result.get('exitCode'))} | {link(OUT / f'candidate-{version}-tests.json', 'tests/command')} |")
    lines += ['', 'Числа читаются из сохранённых tests JSON, включая candidate-v4; пересекающиеся наборы не суммируются и не выдаются за полный набор проекта. Candidate-v3 сохранён как неудачная сборка: три новых теста не включили обязательный STOP при закрытии контакта в ожидаемый журнал. В v4 исправлены только эти ожидания; production-код v3/v4 совпадает. Регрессии охватывают full-DFS oracle, actual цепочку операций/кадров, endpoint growth, инвалидацию, скрытый C3 с retention, checkpoint и отмену; v4 добавляет controlled-clock temporal-replan проверку.', '',
        '| Oracle | Версия свидетельства | Статус | Узлы | Полный план и метрики |', '|---|---|---|---:|---|']
    for rule in ('CANONICAL', 'CONTINUE_WAIT'):
        path = next((OUT / f'candidate-{v}-build/target/pc2-oracle-{rule}.json' for v in ('v4', 'v2')
                     if (OUT / f'candidate-{v}-build/target/pc2-oracle-{rule}.json').exists()), None)
        if path is None:
            lines.append(f'| {rule} | — | Нет сохранённого oracle JSON | — | — |')
        else:
            search = load(path)['search']
            lines.append(f"| {rule} | {path.parents[1].name} | {search['status']} | {search['exploredNodes']} | {link(path, 'oracle JSON')} |")
    preserved = len(load(OUT / 'preserved-inputs.json'))
    lines += ['', f'В реестре сохранения — {preserved} исторических файлов; их проверка отделена от новых экспериментов. Генератор не запускает JVM и не заменяет результаты. Он сверяет analysis с регистрациями, различает PARTIAL и NOT_RUN; итоговая выбранная серия содержит все16+6 завершённых строк, а техническая ошибка сохранена отдельно.', '',
        '```powershell', '$py = "C:\\Users\\Muhalek\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe"',
        '& $py -X utf8 scripts/pc2_extract_fixtures.py --verify',
        '$env:PC2_BUILD_LABEL = "local-check-" + (Get-Date -Format "yyyyMMdd-HHmmss")',
        '& $py -X utf8 scripts/run_pc2.py build',
        '$env:PC2_SERIES = "measured-v4b"', '& $py -X utf8 scripts/analyze_pc2.py',
        '& $py -X utf8 scripts/write_pc2_report.py', '```', '',
        'Для повторного исполнения нужны отдельные выходные каталоги: runner не перезаписывает регистрацию/попытки. Maven-команды находятся в tests/binary JSON; точные JVM-команды — в invocation.json каждой строки. Сохраняйте соответствующие v2/v4 JAR, model hash и лимиты; не запускайте все команды параллельно.', '',
        f"Полные анализы: {link(OUT / 'iss46-v1/analysis.json', 'исходные16')}, {link(OUT / 'measured-v4/analysis.json', 'техническая ошибка')}, {link(OUT / 'measured-v4b/analysis.json', 'corrected measured')}. Фактических попыток {attempted}; NOT_RUN не считается попыткой. Принадлежность к итоговой восьмёрке задана явно. [Manifest](../../../target/practical-controller-pc2/manifest.json) фиксирует версии и хеши.", '',
        '## Граница выводов', '',
        'Портфель устраняет безусловный приоритет худшего терминального WAIT и заново проверяет сохранённый остаток. Отдельная measured-коррекция устраняет подтверждённую подмену прошедшего WAIT более поздним ожиданием; её фактический результат читается из corrected v4, стоимость новых решений показана явно. Локальная инварианта, миссионные исходы и причины остановок остаются разными утверждениями.', '',
        'Дополнительный горизонт не объявляется лучше заранее. Terminal effects не исправляются бонусом или изменением objective. Согласование после неопределённого SWITCH/OPEN/REUSE, статистическая устойчивость и бортовая калибровка остаются за рамками PC2.', '',
        'На текущей лёгкой ISS46-нагрузке итоговые Next/Multi, PerContact и offline seed-without-search совпали по пяти показателям; независимого преимущества Multi здесь не найдено. FixedPeriod тоже обслужил46/46, но создал122 ключа вместо92 и израсходовал35.66 вместо32.66 с модельного operation CPU. Практический Multi вычислительно дороже Next: measured процессы CANONICAL593.25 против336.61 с, CONTINUE_WAIT417.41 против129.88 с. Это единичные host измерения, а не статистически установленный коэффициент замедления.', '',
        'Следующий узкий шаг — использовать уже допустимый seed/retained как начальный incumbent внутри ограниченного DFS, отдельно проверив корректность отсечений и сохранение полного tie-break на малых оракулах. Сейчас portfolio исправляет качество внешнего выбора, но не сокращает объём DFS. Также остаётся отдельная техническая неопределённость: причина единичного Windows checkpoint AccessDenied не установлена; успешный повтор не доказывает надёжность во всех условиях.']
    doc.mkdir(parents=True, exist_ok=True)
    (doc / 'README.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    root_path=ROOT/'README.md';root_text=root_path.read_text(encoding='utf-8')
    marker='## PC1: практический контроллер на полном ISS-расписании'
    if marker not in root_text:raise RuntimeError('Root README insertion anchor missing')
    pc2='## PC2: допустимый исходный план и сохранение продолжения'
    if pc2 in root_text:
        start=root_text.index(pc2);end=root_text.index(marker,start);root_text=root_text[:start]+root_text[end:]
    block=[pc2,'','[Результаты, контракты и команды](docs/practical-controller/pc2/README.md), [все попытки CSV](docs/practical-controller/pc2/runs.csv).','',
        'Добавлены общий `FeasibleSeedPlanBuilder` и `PracticalPlanPortfolio`. Seed, переоценённый остаток и терминальный результат ограниченного DFS сравниваются из одного actual root до одного endpoint по прежним пяти критериям и полному tie-break. Потеря более дешёвого post-PREPARE продолжения устранена; локальная гарантия выбора не означает глобальной оптимальности или превосходства Multi.','',
        '| Режим | Правило | H | PC1 обслужено | PC2 обслужено | PC2 статус |','|---|---|---:|---:|---:|---|']
    for row in primary:
        old=previous[row['measured'],row['rule'],row['horizon']]
        block.append(f"| {'measured' if row['measured'] else 'offline'} | {row['rule']} | {row['horizon']} | {old['five']['servedContacts']} | {num((row.get('five') or {}).get('servedContacts'))} | {row['status']} |")
    block+=['',f"Сохранены {attempted} фактических попытки, включая исходную отрицательную measured-серию и технический PROCESS_ERROR checkpoint. Итоговый measured использует отдельно проверенную коррекцию устаревшего WAIT: прошедшее/исчезнувшее событие вызывает новое оплачиваемое решение. Budgets не увеличивались. Исторические SE2/R1/PC1 не заменены.",'',
        f"Изолированный Maven `clean verify`: {tests['v4']['tests']} целевых теста, {tests['v4']['classes']} классов, без failures/errors; полный набор проекта не запускался. Два малых full-DFS oracle завершились COMPLETE. Offline seed без DFS также обслуживает46/46; дополнительный поиск на этой нагрузке не дал выигрыша обслуживания. Его вычислительная стоимость и границы measured-сравнений приведены в отчёте.",'','']
    root_path.write_text(root_text.replace(marker,'\n'.join(block)+marker,1),encoding='utf-8')
    print(f"Wrote {doc / 'README.md'} from {len(initial)} initial + {len(corrected)} corrected attempts")


if __name__ == '__main__':
    main()
