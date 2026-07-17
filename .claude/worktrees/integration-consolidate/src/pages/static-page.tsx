import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { useSmoothScroll } from '@/hooks/useSmoothScroll';

interface StaticPageProps {
  title: string;
  description: string;
}

export default function StaticPage({ title, description }: StaticPageProps) {
  const navigate = useNavigate();
  useSmoothScroll(true);

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background px-4 text-center">
      <InfluoraLogo size="lg" className="mb-6" />
      <h1 className="text-3xl font-semibold text-foreground">{title}</h1>
      <p className="mt-3 max-w-md text-sm text-muted-foreground">{description}</p>
      <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
        <Button variant="ghost" onClick={() => navigate(-1)}>
          Go back
        </Button>
      </div>
    </div>
  );
}
