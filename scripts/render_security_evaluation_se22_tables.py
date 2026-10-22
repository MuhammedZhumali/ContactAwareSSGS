#!/usr/bin/env python3
"""Readable four-cell tables from independently audited CSV; no experiments."""
import csv
from decimal import Decimal
from run_security_evaluation_se22 import DOC

def number(value):
    return format(Decimal(value).quantize(Decimal('0.001')), 'f').rstrip('0').rstrip('.') if '.' in str(value) else str(value)

def main():
    rows=list(csv.DictReader((DOC/'tables/four-combinations.csv').open(encoding='utf-8')))
    grouped={}
    for r in rows:
        grouped.setdefault((r['fixture'],r['attackPoint']),{})[(r['policy'],r['rule'])]=r
    cells=[('next','CANONICAL'),('multi','CANONICAL'),('next','CONTINUE_WAIT'),('multi','CONTINUE_WAIT')]
    output=['# SE2.2: полные таблицы четырёх сочетаний','',
        'C = CANONICAL; A = CONTINUE_WAIT. Ошибка/незавершение сохраняются отдельными исходами.',
        '— означает неприменимость; `censored` означает отсутствие события в наблюдаемом интервале.',
        'Округление здесь до 0.001 s; CSV сохраняет исходную точность.','']
    def matrix(title,description,render):
        output.extend(['## '+title,'',description,'',
            '| Случай | Next C | Multi C | Next A | Multi A |',
            '|---|---|---|---|---|'])
        for (fixture,point),group in grouped.items():
            parts=[]
            for key in cells:
                row=group.get(key)
                parts.append('NOT_RUN' if row is None else row['status'] if row['status']!='FINISHED' else render(row))
            output.append('| '+fixture+' / '+(point or 'none')+' | '+' | '.join(parts)+' |')
        output.append('')
    matrix('Пять основных показателей','(Контакты; полезные секунды; CPU s; management B; новые ключи).',
        lambda r:'; '.join(number(r[k]) for k in ('servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys')))
    def recover(r):
        values=[]
        for k in ('spacecraft_notice','independent_pair','independent_sa','independent_frame'):
            value=r.get(k+'_seconds'); status=r.get(k+'_status','')
            values.append(number(value) if value else 'censored' if 'censor' in status else '—')
        return '; '.join(values)
    matrix('Восстановление','Секунды от уведомления НС: КА; независимая пара; SA; первый кадр.',recover)
    matrix('Раскрытый и конфиденциально доставленный трафик',
        '(Раскрытые B до компрометации; после компрометации; конфиденциально доставленные B).',
        lambda r:'; '.join(number(r[k]) for k in ('decryptableBeforeBytes','decryptableAfterBytes','confidentialDeliveredBytes')))
    output += ['## Расходы поиска и неполные результаты','',
        '[search-decisions.csv](tables/search-decisions.csv) содержит каждый поиск: elapsed, CPU, узлы, фазы, GC и причину остановки.',
        '[four-combinations.csv](tables/four-combinations.csv) сохраняет расходы незавершённых контроллеров, когда процесс успел экспортировать их.',
        '[failed-attempts.json](tables/failed-attempts.json) содержит ошибки процесса и последнюю доступную контрольную точку.',
        'У PROCESS_ERROR итоговые расходы поиска недоступны; время процесса хранится отдельно.','']
    (DOC/'comparison-tables.md').write_text('\n'.join(output),encoding='utf-8')

if __name__=='__main__': main()
