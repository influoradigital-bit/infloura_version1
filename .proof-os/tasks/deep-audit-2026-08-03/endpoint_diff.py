import re,os,json
BE=set()
for r,d,fs in os.walk('influora-api/src/main/java'):
    for f in fs:
        if not f.endswith('.java'): continue
        s=open(os.path.join(r,f),encoding='utf-8',errors='replace').read()
        cls=re.findall(r'@RequestMapping\(\s*(?:value\s*=\s*)?\{?\s*"([^"]+)"',s)
        base=cls[0] if cls else ''
        for m in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)")?',s):
            p=m.group(2) or ''
            BE.add(((base.rstrip('/')+'/'+p.lstrip('/')) if p else base).rstrip('/') or '/')
def norm(p):
    p=re.sub(r'^/api/v1','',p)
    p=re.sub(r'\$\{[^}]*\}','{}',p)
    p=re.sub(r'\$\{.*$','',p)          # unbalanced template tail
    p=re.sub(r'\{[^}]*\}','{}',p)
    p=p.split('?')[0].split('`')[0]
    return p.rstrip('/') or '/'
BEN={norm(b) for b in BE}
FE={}
def add(path,src):
    n=norm(path)
    if n and n!='/': FE.setdefault(n,set()).add(src)
# admin client: every apiRequest(...) first string arg, generics of any depth
s=open('src/admin/services/api-contracts.ts',encoding='utf-8',errors='replace').read()
for m in re.finditer(r'apiRequest\s*<.*?>\s*\(\s*[`\'"]([^`\'"]+)', s, re.S):
    add('/admin'+m.group(1),'api-contracts.ts')
for m in re.finditer(r'apiRequest\s*\(\s*[`\'"]([^`\'"]+)', s):
    add('/admin'+m.group(1),'api-contracts.ts')
# every ts/tsx file in src: any string literal that starts with a known API segment
SEG=r'(?:auth|deals|campaigns|creators|brands|wallet|escrow|notifications|disputes|payouts|invoices|contracts|deliverables|reviews|uploads?|upload|analytics|admin|workspaces?|subscriptions?|coupons?|tracking|shipments|proposals|messages|client-errors|meera|trendspark|integrations|store|kyc|tax|referrals|support|health|onboarding|applications|portfolio|search|discover|billing|fees?|moderation|marketing|dashboard|finance|errors|emails|audit)'
pat=re.compile(r'[`\'"](/'+SEG+r'[a-zA-Z0-9_\-/{}$.]*)[`\'"]')
nfiles=0
for r,d,fs in os.walk('src'):
    for f in fs:
        if not f.endswith(('.ts','.tsx')): continue
        p=os.path.join(r,f); nfiles+=1
        t=open(p,encoding='utf-8',errors='replace').read()
        for m in pat.finditer(t): add(m.group(1), p)
missing=sorted(p for p in FE if p not in BEN)
print("files scanned: %d  |  backend mapped paths: %d  |  frontend API paths: %d"%(nfiles,len(BEN),len(FE)))
print("matched: %d   |   NO BACKEND MAPPING: %d"%(len(FE)-len(missing),len(missing)))
for m in missing: print("   %-48s  <- %s"%(m, ', '.join(sorted(x.replace('src/','') for x in FE[m]))[:70]))
json.dump({"missing":missing,"n_fe":len(FE),"n_be":len(BEN),"files":nfiles},open('/tmp/ep3.json','w'),indent=1)
