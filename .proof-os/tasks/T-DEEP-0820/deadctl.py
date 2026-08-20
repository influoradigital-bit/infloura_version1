import re,os,json
BS=chr(92)
SURF=re.compile(r'(track|coupon|affiliate|analytic|wallet|billing|escrow|payment|payout|invoice|campaign|connected-account|copilot/IG|onboard)',re.I)
files=[]
for d in ('src/pages','src/components'):
    for dp,dn,fn in os.walk(d):
        for f in fn:
            if f.endswith('.tsx') and '.test.' not in f:
                p=os.path.join(dp,f).replace(BS,'/')
                if SURF.search(p): files.append(p)
findings=[]
BTN=re.compile(r'<(Button|button)\b((?:[^>]|'+chr(10)+r')*?)>',re.S)
for p in files:
    s=open(p,encoding='utf-8',errors='replace').read()
    for m in BTN.finditer(s):
        attrs=m.group(2)
        if 'onClick' in attrs or 'type="submit"' in attrs or "type='submit'" in attrs or 'href' in attrs or 'asChild' in attrs or 'onSubmit' in attrs:
            continue
        line=s[:m.start()].count(chr(10))+1
        # skip if inside a <form ...onSubmit> earlier and no type => default submit
        findings.append((p,line,attrs.strip().replace(chr(10),' ')[:90]))
print(len(files),'surface files scanned;',len(findings),'buttons with no onClick/submit/href:')
for f in findings[:40]: print(f'  {f[0]}:{f[1]}  {f[2]}')
