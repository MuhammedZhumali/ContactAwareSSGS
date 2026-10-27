#!/usr/bin/env python3
"""Render the short synthesis only; no experiments or input mutation."""
import html
import re
from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak
from pypdf import PdfReader

ROOT=Path(__file__).resolve().parents[1]
DOC=ROOT/'docs/research-synthesis'


def inline(text):
    text=re.sub(r'\[([^\]]+)\]\([^)]+\)',r'\1',text)
    text=text.replace('—','-').replace('–','-').replace('‑','-')
    text=html.escape(text)
    text=re.sub(r'\*\*(.*?)\*\*',r'<b>\1</b>',text)
    return text.replace('`','')


def main():
    pdfmetrics.registerFont(TTFont('Research','C:/Windows/Fonts/arial.ttf'))
    pdfmetrics.registerFont(TTFont('Research-Bold','C:/Windows/Fonts/arialbd.ttf'))
    pdfmetrics.registerFontFamily('Research',normal='Research',bold='Research-Bold',italic='Research',boldItalic='Research-Bold')
    body=ParagraphStyle('body',fontName='Research',fontSize=10,leading=13.2,spaceAfter=7,textColor=colors.HexColor('#17232e'))
    heading=ParagraphStyle('heading',parent=body,fontName='Research-Bold',fontSize=13.5,leading=17,spaceBefore=2,spaceAfter=10)
    title=ParagraphStyle('title',parent=heading,fontSize=18,leading=22,spaceAfter=10)
    cell=ParagraphStyle('cell',parent=body,fontSize=8.4,leading=10.6,spaceAfter=0)
    content=[];lines=(DOC/'report.md').read_text(encoding='utf-8').splitlines();i=0
    while i<len(lines):
        line=lines[i].strip()
        if not line:i+=1;continue
        if line=='<!-- pagebreak -->':content.append(PageBreak());i+=1;continue
        if line.startswith('# '):content.append(Paragraph(inline(line[2:]),title));i+=1;continue
        if line.startswith('## '):content.append(Paragraph(inline(line[3:]),heading));i+=1;continue
        if line.startswith('|'):
            rows=[]
            while i<len(lines) and lines[i].startswith('|'):
                values=[x.strip() for x in lines[i].strip().strip('|').split('|')]
                if not all(set(x)<=set('-: ') for x in values):rows.append(values)
                i+=1
            n=len(rows[0]);fractions={3:[.29,.36,.35],4:[.32,.17,.23,.28],6:[.13,.15,.19,.18,.21,.14]}[n]
            data=[[Paragraph('<b>'+inline(x)+'</b>' if j==0 else inline(x),cell) for x in r] for j,r in enumerate(rows)]
            table=Table(data,colWidths=[523*f for f in fractions],repeatRows=1,hAlign='LEFT')
            table.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#e4edf3')),
                ('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor('#f5f8fa')]),
                ('LINEBELOW',(0,0),(-1,0),.6,colors.HexColor('#829aaa')),('BOTTOMPADDING',(0,0),(-1,-1),6),
                ('TOPPADDING',(0,0),(-1,-1),6),('LEFTPADDING',(0,0),(-1,-1),6),('RIGHTPADDING',(0,0),(-1,-1),6)]))
            content.extend([table,Spacer(1,9)]);continue
        paragraph=[]
        while i<len(lines) and lines[i].strip() and not lines[i].startswith(('#','|','<!--')):
            paragraph.append(lines[i].strip());i+=1
        content.append(Paragraph(inline(' '.join(paragraph)),body))
    def footer(canvas,doc):
        canvas.setStrokeColor(colors.HexColor('#ccd5dc'));canvas.line(36,34,559,34)
        canvas.setFont('Research',8);canvas.setFillColor(colors.HexColor('#526673'))
        canvas.drawString(36,21,'ContactAwareSS | Сводка сохранённых результатов | 24.10.2026')
        canvas.drawRightString(559,21,str(doc.page))
    target=DOC/'report.pdf'
    SimpleDocTemplate(str(target),pagesize=A4,rightMargin=36,leftMargin=36,topMargin=31,bottomMargin=45,
        title='ContactAwareSS: условия полезности многоконтактного прогноза',author='ContactAwareSS research',
        pageCompression=1).build(content,onFirstPage=footer,onLaterPages=footer)
    reader=PdfReader(target);print('Pages:',len(reader.pages))
    assert len(reader.pages)==3, 'The requested synthesis must fit exactly three pages'
    for i,page in enumerate(reader.pages):
        text=page.extract_text();assert text and len(text)>1000
        print('Page',i+1,'characters',len(text))


if __name__=='__main__':main()
