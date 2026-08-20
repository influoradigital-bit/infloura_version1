import re,json,os
hits=[]
BS=chr(92)
for p in ['src/lib/api.ts','src/lib/meera-api.ts']:
    s=open(p,encoding='utf-8',errors='replace').read()
    # request('VERB', '/path'  OR request<T>('VERB', `/path...`
    for mm in re.finditer(r"""request\s*(?:<[^(]*?>)?\(\s*['"](GET|POST|PUT|PATCH|DELETE)['"]\s*,\s*[`'"]([^`'"]+)""", s, re.S):
        hits.append({'file':p,'line':s[:mm.start()].count(chr(10))+1,'verb':mm.group(1),'path':mm.group(2)})
# also any fetch( with template literal paths across src
for dirpath,dirs,files in os.walk('src'):
    dirs[:]=[d for d in dirs if d!='node_modules']
    for f in files:
        if not f.endswith(('.ts','.tsx')): continue
        p=os.path.join(dirpath,f).replace(BS,'/')
        if p in ('src/lib/api.ts','src/lib/meera-api.ts'): continue
        s=open(p,encoding='utf-8',errors='replace').read()
        for mm in re.finditer(r"""fetch\(\s*[`'"]([^`'"]*/(?:api|v1)/[^`'"]*)""",s):
            hits.append({'file':p,'line':s[:mm.start()].count(chr(10))+1,'verb':'FETCH','path':mm.group(1)})
json.dump(hits,open('.proof-os/tasks/T-DEEP-0820/fe_calls.json','w'),indent=0)
print(len(hits),'frontend calls captured')
