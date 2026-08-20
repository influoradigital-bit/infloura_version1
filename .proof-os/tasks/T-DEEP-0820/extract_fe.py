import re, os, json
hits=[]
BS=chr(92)
for dirpath,dirs,files in os.walk('src'):
    dirs[:] = [d for d in dirs if d not in ('node_modules',)]
    for f in files:
        if not f.endswith(('.ts','.tsx')): continue
        p=os.path.join(dirpath,f).replace(BS,'/')
        s=open(p,encoding='utf-8',errors='replace').read()
        for mm in re.finditer(r'''["'`](/api/[A-Za-z0-9_\-/{}$:.\[\]]*)["'`]''',s):
            hits.append({'file':p,'line':s[:mm.start()].count(chr(10))+1,'path':mm.group(1),'test':('test' in p or '__tests__' in p)})
json.dump(hits,open('.proof-os/tasks/T-DEEP-0820/fe_calls.json','w'),indent=0)
print(len(hits),'frontend /api references')
