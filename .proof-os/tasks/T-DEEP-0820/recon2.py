import json,re
be=json.load(open('.proof-os/tasks/T-DEEP-0820/be_endpoints.json'))
fe=json.load(open('.proof-os/tasks/T-DEEP-0820/fe_calls.json'))
def norm(p):
    p=p.split('?')[0].rstrip('/')
    p=re.sub(r'\$\{[^}]*\}','*',p); p=re.sub(r'\{[^}]*\}','*',p)
    p=re.sub(r'/me(?=/|$)','/*',p)
    if p.startswith('/api/v1'): p=p[7:]
    elif p.startswith('/api'): p=p[4:]
    return p or '/'
feset={(c['verb'],norm(c['path'])) for c in fe}
fepaths={norm(c['path']) for c in fe}
SURF={'social':'MetaOAuth|Shopify|WooCommerce|StoreIntegration|Onboarding',
      'payment':'Escrow|Wallet|Billing|Invoicing|PlatformFee|Finance|Revenue|Deal',
      'campaign':'Campaign|Application|Deliverable|Contract|Approval',
      'analytics':'Analytics|Dashboard|DeliverableMetric|ReportExport',
      'coupon':'Coupon|AffiliateEarning',
      'tracking':'CampaignTracking|ConversionWebhook'}
for surf,pat in SURF.items():
    rows=[e for e in be if re.search(pat,e['file']) and 'Admin' not in e['file']]
    orph=[e for e in rows if (e['verb'],norm(e['path'])) not in feset]
    print(f"\n### {surf.upper()}  ({len(rows)} endpoints, {len(orph)} with no FE caller)")
    for e in orph:
        near = norm(e['path']) in fepaths
        print(f"  {e['verb']:6} {norm(e['path']):58} {'[path used, other verb]' if near else 'NO FE CALLER'}  {e['file'].split('/')[-1]}:{e['line']}")
