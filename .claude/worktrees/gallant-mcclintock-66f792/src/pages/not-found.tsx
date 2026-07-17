import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { IconBadge } from '@/components/shared/icon-badge';
import { SearchX } from 'lucide-react';

export default function NotFoundPage() {
  const navigate = useNavigate();

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background px-4 text-center">
      <InfluoraLogo size="lg" className="mb-6" />
      <IconBadge icon={SearchX} variant="muted" size="xl" rounded="full" className="mb-4" />
      <h1 className="text-6xl font-bold text-muted-foreground">404</h1>
      <h2 className="mt-2 text-xl font-semibold text-foreground">Page not found</h2>
      <p className="mt-2 max-w-md text-sm text-muted-foreground">
        The page you&apos;re looking for doesn&apos;t exist or has been moved.
      </p>
      <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
        <Button onClick={() => navigate('/brand/dashboard')}>Go to Dashboard</Button>
        <Button variant="ghost" onClick={() => navigate(-1)}>
          Go back
        </Button>
      </div>
    </div>
  );
}
