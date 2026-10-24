#!/usr/bin/env python3
"""Extract text (paragraphs + tables) from a .docx using only the stdlib."""
import sys
import zipfile
import re
from xml.etree import ElementTree as ET

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def para_text(p):
    parts = []
    for node in p.iter():
        if node.tag == W + "t":
            parts.append(node.text or "")
        elif node.tag == W + "tab":
            parts.append("\t")
        elif node.tag in (W + "br", W + "cr"):
            parts.append("\n")
    return "".join(parts)


def walk(body, out):
    for child in body:
        if child.tag == W + "p":
            out.append(para_text(child))
        elif child.tag == W + "tbl":
            out.append("<<<TABLE>>>")
            for tr in child.findall(W + "tr"):
                cells = []
                for tc in tr.findall(W + "tc"):
                    ct = " ".join(para_text(p).strip() for p in tc.findall(W + "p"))
                    cells.append(ct.strip())
                out.append("| " + " | ".join(cells) + " |")
            out.append("<<<END TABLE>>>")


def main():
    src, dst = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(src) as z:
        names = [n for n in z.namelist() if n == "word/document.xml"]
        if not names:
            raise SystemExit("word/document.xml not found in " + src)
        xml = z.read(names[0])
    root = ET.fromstring(xml)
    body = root.find(W + "body")
    out = []
    walk(body, out)
    text = "\n".join(out)
    text = re.sub(r"\n{3,}", "\n\n", text)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"wrote {dst}: {len(out)} blocks, {len(text)} chars")


if __name__ == "__main__":
    main()
