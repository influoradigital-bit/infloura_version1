import json,re
be=json.load(open('.proof-os/tasks/T-DEEP-0820/be_endpoints.json'))
fe=json.load(open('.proof-os/tasks/T-DEEP-0820/fe_calls.json'))
def norm(p):
    p=p.split('?')[0].rstrip('/')
    p=re.sub(r'\$\{[^}]*\}','*',p)
    p=re.sub(r'\{[^}]*\}','*',p)
    p=re.sub(r'/v1','',p,count=1) if p.startswith('/v1') else p
    if p.startswith('/api/v1'): p=p[7:]
    elif p.startswith('/api'): p=p[4:]
    return p or '/'
bemap={}
for e in be:
    bemap.setdefault((e['verb'],norm(e['path'])),[]).append(e)
missing=[]
for c in fe:
    k=(c['verb'],norm(c['path']))
    if c['verb']=='FETCH':
        ok=any(norm(c['path'])==kk[1] for kk in bemap)
    else:
        ok=k in bemap
    if not ok: missing.append((c,norm(c['path'])))
print('=== FE calls with NO matching backend endpoint:',len(missing))
for c,n in missing: print(f"  {c['verb']:6} {n:55} <- {c['file']}:{c['line']}")
