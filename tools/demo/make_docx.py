"""Демо-документ Word для библиотеки пульта: заголовки, абзац по ширине, нумерованный и маркированный
списки, таблица с заливкой шапки, разрыв страницы. Собирается вручную из WordprocessingML, без Word.

Запуск: python3 tools/demo/make_docx.py  (результат — android/phone/src/main/assets/demo/Конспект. Город 15 минут.docx)
"""
import zipfile

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
OUT = "android/phone/src/main/assets/demo/Конспект. Город 15 минут.docx"


def p(text, ppr="", rpr=""):
    return f'<w:p><w:pPr>{ppr}</w:pPr><w:r><w:rPr>{rpr}</w:rPr><w:t xml:space="preserve">{text}</w:t></w:r></w:p>'


def item(text, num, lvl=0):
    return p(text, f'<w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="{lvl}"/><w:numId w:val="{num}"/></w:numPr>')


def cell(text, head=False):
    shd = '<w:tcPr><w:shd w:fill="D9E2F3"/></w:tcPr>' if head else ""
    return f'<w:tc>{shd}{p(text, "", "<w:b/>" if head else "")}</w:tc>'


BODY = (
    p("Город пятнадцати минут", '<w:pStyle w:val="Title"/><w:jc w:val="center"/>')
    + p("Конспект к лекции", '<w:jc w:val="center"/>', '<w:i/><w:color w:val="595959"/>')
    + p("Идея", '<w:pStyle w:val="Heading1"/>')
    + p("Город пятнадцати минут — это город, где всё нужное для жизни находится в пятнадцати минутах пешком "
        "или на велосипеде: работа, школа, магазины, врач, парк и место для встреч. Идея не новая, но после "
        "2020 года её заново открыли мэры многих городов.", '<w:jc w:val="both"/><w:ind w:firstLine="567"/>')
    + p("Шесть нужд горожанина", '<w:pStyle w:val="Heading1"/>')
    + "".join(item(t, 1) for t in ["Жить", "Работать", "Покупать", "Лечиться", "Учиться", "Отдыхать"])
    + p("Примеры", '<w:pStyle w:val="Heading1"/>')
    + '<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr><w:tblGrid><w:gridCol w:w="2600"/><w:gridCol w:w="6400"/></w:tblGrid>'
    + f'<w:tr>{cell("Город", True)}{cell("Что сделано", True)}</w:tr>'
    + f'<w:tr>{cell("Париж")}{cell("Школьные дворы открыты по выходным, улицы у школ — без машин")}</w:tr>'
    + f'<w:tr>{cell("Барселона")}{cell("Суперкварталы: девять кварталов, внутри — пешеходы и зелень")}</w:tr>'
    + f'<w:tr>{cell("Мельбурн")}{cell("План «20-минутных районов» до 2050 года")}</w:tr></w:tbl>'
    + '<w:p><w:r><w:br w:type="page"/></w:r></w:p>'
    + p("Вопросы для обсуждения", '<w:pStyle w:val="Heading1"/>')
    + "".join(item(t, 2) for t in ["Что в вашем районе есть в пятнадцати минутах?", "Чего не хватает больше всего?",
                                   "Что можно сделать без больших денег?"])
)

DOCUMENT = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="{W}"><w:body>{BODY}'
            '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1701"/>'
            '</w:sectPr></w:body></w:document>')

BORDERS = "".join(f'<w:{s} w:val="single" w:sz="4"/>' for s in ["top", "left", "bottom", "right", "insideH", "insideV"])
STYLES = f'''<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="{W}">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="24"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="80"/></w:pPr><w:rPr><w:rFonts w:ascii="Cambria" w:hAnsi="Cambria"/><w:sz w:val="52"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="360" w:after="80"/></w:pPr><w:rPr><w:b/><w:color w:val="2F5496"/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:tblPr><w:tblBorders>{BORDERS}</w:tblBorders></w:tblPr></w:style>
</w:styles>'''

NUMBERING = f'''<?xml version="1.0" encoding="UTF-8"?><w:numbering xmlns:w="{W}">
<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
<w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr><w:rPr><w:rFonts w:ascii="Symbol" w:hAnsi="Symbol"/></w:rPr></w:lvl></w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num></w:numbering>'''

CT = ('<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      '<Default Extension="xml" ContentType="application/xml"/>'
      '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
      '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
      '<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/></Types>')
RELS = ('<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
DOC_RELS = ('<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/></Relationships>')

if __name__ == "__main__":
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CT)
        z.writestr("_rels/.rels", RELS)
        z.writestr("word/document.xml", DOCUMENT)
        z.writestr("word/styles.xml", STYLES)
        z.writestr("word/numbering.xml", NUMBERING)
        z.writestr("word/_rels/document.xml.rels", DOC_RELS)
    print(OUT)
