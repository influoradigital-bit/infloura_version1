import re,json,os
hits=[];BS=chr(92)
PAT=re.compile(r"""(?:request|requestWithMeta|downloadBlob|postForm|upload)\s*(?:<[^(]*?>)?\(\s*(?:['"](GET|POST|PUT|PATCH|DELETE)['"]\s*,\s*)?[`'"]([/][^`'"]+)""")
FET=re.compile(r"""(?:fetch|EventSource)\(\s*[`'"]([^`'"]+)""")
for dirpath,dirs,files in os.walk('src'):
    dirs[:]=[d for d in dirs if d!='node_modules']
    for f in files:
        if not f.endswith(('.ts','.tsx')): continue
        p=os.path.join(dirpath,f).replace(BS,'/')
        s=open(p,encoding='utf-8',errors='replace').read()
        istest='.test.' in p or '__tests__' in p
        for mm in PAT.finditer(s):
            hits.append({'file':p,'line':s[:mm.start()].count(chr(10))+1,'verb':mm.group(1) or 'GET','path':mm.group(2),'test':istest})
        for mm in FET.finditer(s):
            u=mm.group(1)
            if '/api' in u or 'API_BASE_URL' in u or u.startswith('/'):
                hits.append({'file':p,'line':s[:mm.start()].count(chr(10))+1,'verb':'ANY','path':u,'test':istest})
json.dump(hits,open('.proof-os/tasks/T-DEEP-0820/fe_calls.json','w'),indent=0)
print(len(hits),'frontend calls', sum(1 for h in hits if not h['test']),'non-test')
