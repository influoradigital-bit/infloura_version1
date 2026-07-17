import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { InfluoraLogo } from '@/components/shared/influora-logo';

export default function BrandForgotPasswordPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 800));
    setSent(true);
    setLoading(false);
  };

  return (
    <div className="min-h-screen auth-gradient flex flex-col items-center justify-center px-4">
      <div className="w-full max-w-md space-y-6">
        <InfluoraLogo size="lg" showName={true} />
        <div className="bg-card border border-border shadow-sm rounded-2xl p-8 backdrop-blur-sm">
          <h1 className="text-2xl font-bold text-white mb-2">Reset password</h1>
          <p className="text-muted-foreground text-sm mb-6">
            {sent
              ? 'If an account exists for that email, we sent a reset link.'
              : 'Enter your email and we will send you a reset link.'}
          </p>
          {!sent ? (
            <form onSubmit={handleSubmit} className="space-y-4">
              <div>
                <Label htmlFor="email" className="text-foreground">
                  Email Address
                </Label>
                <Input
                  id="email"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@company.com"
                  className="mt-2 bg-muted/50 border-slate-600 text-white h-auto py-3"
                  required
                />
              </div>
              <Button type="submit" disabled={loading} className="w-full">
                {loading ? 'Sending...' : 'Send reset link'}
              </Button>
            </form>
          ) : (
            <Button className="w-full" onClick={() => navigate('/brand/login')}>
              Back to sign in
            </Button>
          )}
          <button
            type="button"
            onClick={() => navigate('/brand/login')}
            className="mt-4 w-full text-center text-sm text-primary hover:text-primary/80"
          >
            Back to login
          </button>
        </div>
      </div>
    </div>
  );
}
