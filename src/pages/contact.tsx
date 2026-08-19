import { Building2, Mail, Phone, ShieldCheck } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getOrganizationSchema } from '@/lib/seo/schema';
import { COMPANY } from '@/lib/company';

/** Registered in `COMPANY.state` before `/:handle` in App.tsx per URL-structure convention. */
export default function ContactPage() {
  const addressLine = COMPANY.registeredAddress || `Registered in ${COMPANY.state}, India`;

  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Contact Us"
        description="Reach the Influora team — support email, phone, and our registered company details (CIN, GSTIN)."
        canonical="/contact"
      />
      <JsonLd data={getOrganizationSchema()} />

      <SiteHeader />

      <main>
        <section className="border-b border-border/60 py-20">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <ShieldCheck className="h-3 w-3" aria-hidden="true" /> Contact
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">Talk to us</h1>
              <p className="mt-4 text-lg text-muted-foreground">
                Questions about a campaign, payments, or your account — reach the Influora team directly.
              </p>
            </FadeUp>
          </div>
        </section>

        <section className="py-16">
          <div className="mx-auto max-w-4xl px-6">
            <StaggerContainer className="grid gap-6 sm:grid-cols-2">
              <StaggerItem>
                <Card className="h-full">
                  <CardContent className="flex h-full flex-col items-start gap-3 p-6">
                    <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                      <Mail className="h-5 w-5" aria-hidden="true" />
                    </span>
                    <h2 className="font-semibold">Email support</h2>
                    <p className="text-sm text-muted-foreground">
                      For account, campaign, payment, or general questions.
                    </p>
                    <a
                      href={`mailto:${COMPANY.email}`}
                      className="text-sm font-medium text-accent-foreground underline underline-offset-2 hover:no-underline"
                    >
                      {COMPANY.email}
                    </a>
                  </CardContent>
                </Card>
              </StaggerItem>

              <StaggerItem>
                <Card className="h-full">
                  <CardContent className="flex h-full flex-col items-start gap-3 p-6">
                    <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                      <Phone className="h-5 w-5" aria-hidden="true" />
                    </span>
                    <h2 className="font-semibold">Call us</h2>
                    <p className="text-sm text-muted-foreground">Available for brand and creator support.</p>
                    <a
                      href={`tel:${COMPANY.phoneHref}`}
                      className="text-sm font-medium text-accent-foreground underline underline-offset-2 hover:no-underline"
                    >
                      {COMPANY.phone}
                    </a>
                  </CardContent>
                </Card>
              </StaggerItem>
            </StaggerContainer>

            <FadeUp delay={0.1}>
              <Card className="mt-6">
                <CardContent className="flex flex-col gap-3 p-6">
                  <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                    <Building2 className="h-5 w-5" aria-hidden="true" />
                  </span>
                  <h2 className="font-semibold">Registered company details</h2>
                  <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                    <div>
                      <dt className="text-xs uppercase tracking-wide text-muted-foreground">Legal name</dt>
                      <dd className="mt-0.5 text-foreground">{COMPANY.legalName}</dd>
                    </div>
                    <div>
                      <dt className="text-xs uppercase tracking-wide text-muted-foreground">CIN</dt>
                      <dd className="mt-0.5 font-mono text-foreground">{COMPANY.cin}</dd>
                    </div>
                    <div>
                      <dt className="text-xs uppercase tracking-wide text-muted-foreground">GSTIN</dt>
                      <dd className="mt-0.5 font-mono text-foreground">{COMPANY.gstin}</dd>
                    </div>
                    <div>
                      <dt className="text-xs uppercase tracking-wide text-muted-foreground">Registered office</dt>
                      <dd className="mt-0.5 text-foreground">{addressLine}</dd>
                    </div>
                  </dl>
                </CardContent>
              </Card>
            </FadeUp>
          </div>
        </section>
      </main>

      <SiteFooter />
    </div>
  );
}
