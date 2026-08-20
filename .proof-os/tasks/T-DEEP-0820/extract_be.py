import re, os, json, sys
root='influora-api/src/main/java/com/influora/web'
out=[]
for f in sorted(os.listdir(root)):
    if not f.endswith('.java'): continue
    p=os.path.join(root,f)
    s=open(p,encoding='utf-8',errors='replace').read()
    m=re.search(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"',s)
    base=m.group(1) if m else ''
    for mm in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"|\()?',s):
        verb=mm.group(1).upper()
        sub=mm.group(2) or ''
        line=s[:mm.start()].count('\n')+1
        out.append({'file':p,'line':line,'verb':verb,'path':(base+sub) or '/'})
json.dump(out,open('.proof-os/tasks/T-DEEP-0820/be_endpoints.json','w'),indent=0)
print(len(out),'backend endpoints')
