"""Index display names from published mod jars in an operator-prepared gameplay snapshot."""
import json
from pathlib import Path
import re
import sys
from zipfile import BadZipFile, ZipFile

def build(game):
    names = {}
    for jar in sorted((game/"mods").glob("*.jar")):
        try:
            with ZipFile(jar) as archive:
                for file in archive.infolist():
                    match = re.fullmatch(r"assets/[^/]+/lang/(zh_cn|en_us)\.json", file.filename)
                    if not match or file.file_size > 4000000: continue
                    try:
                        table = json.loads(archive.read(file).decode('utf-8-sig'))
                    except (UnicodeError,ValueError): continue
                    if not isinstance(table,dict): continue
                    for key,value in table.items():
                        k = re.fullmatch(r"(item|block)\.([a-z0-9_]+)\.([a-z0-9_./-]+)",key)
                        if not k or not isinstance(value,str): continue
                        row = names.setdefault((jar.name,k[2]+":"+k[3]),{})
                        row[match[1]] = ' '.join(value.split())[:160]
        except BadZipFile: continue
    with (game/'CATALOG.tsv').open('w',encoding='utf8') as f:
        f.write('jar\ttranslation_key_item_id_hint\tzh_cn\ten_us\n')
        for (jar,key),row in sorted(names.items()):
            f.write('\t'.join((jar,key,row.get('zh_cn',''),row.get('en_us',''))) + '\n')
    print('Indexed',len(names),'display names',flush=True)

if __name__=="__main__": build(Path(sys.argv[1]))
