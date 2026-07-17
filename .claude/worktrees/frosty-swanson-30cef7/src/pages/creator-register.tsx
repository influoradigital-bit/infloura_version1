import * as React from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuthStore } from '@/lib/store';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { Checkbox } from '@/components/ui/checkbox';
import { Instagram, Youtube, ArrowRight, Loader2, CheckCircle2, Shield, Wallet, Users } from 'lucide-react';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { createMockCreatorUser } from '@/lib/mock-user';
import { assertMockAuthAllowed } from '@/lib/api';

export default function CreatorRegisterPage() {
  const navigate = useNavigate();
  const { login } = useAuthStore();
  const [isLoading, setIsLoading] = React.useState(false);
  const [step, setStep] = React.useState<'info' | 'phone' | 'otp'>('info');
  const [formData, setFormData] = React.useState({
    name: '',
    phone: '',
    otp: '',
    acceptTerms: false,
  });

  const handleSendOTP = async () => {
    if (!formData.phone || formData.phone.length < 10) return;
    setIsLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 1500));
    setStep('otp');
    setIsLoading(false);
  };

  const handleVerifyOTP = async () => {
    if (!formData.otp || formData.otp.length < 4) return;
    setIsLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 1500));
    
    // Fail-closed mock guard (Kabir A3): a production build must never
    // silently mint a hardcoded mock token with no credential check.
    assertMockAuthAllowed();

    login(
      createMockCreatorUser({
        id: 'creator-new',
        displayName: formData.name || 'New Creator',
        email: 'creator@influora.com',
      }),
    );

    localStorage.setItem('creator_token', 'mock_creator_token');
    navigate('/creator/onboarding');
    setIsLoading(false);
  };

  const handleSocialRegister = async (platform: 'instagram' | 'youtube') => {
    setIsLoading(true);
    await new Promise((resolve) => setTimeout(resolve, 2000));

    // Fail-closed mock guard (Kabir A3) — see handleVerifyOTP above.
    assertMockAuthAllowed();

    login(createMockCreatorUser({ id: 'creator-new', displayName: 'New Creator' }));

    localStorage.setItem('creator_token', 'mock_creator_token');
    navigate('/creator/onboarding');
    setIsLoading(false);
  };

  const benefits = [
    { icon: Users, text: 'Connect with verified brands' },
    { icon: Shield, text: 'Secure escrow payments' },
    { icon: Wallet, text: 'Get paid within 24 hours' },
  ];

  return (
    <div className="min-h-screen auth-gradient flex items-center justify-center p-4">
      <div className="w-full max-w-md space-y-6">
        <div className="flex flex-col items-center text-center">
          <InfluoraLogo size="lg" showName={true} />
          <p className="text-muted-foreground mt-3">Start earning from brand collaborations</p>
        </div>

        {/* Benefits */}
        <div className="flex justify-center gap-6">
          {benefits.map((benefit, idx) => (
            <div key={idx} className="flex flex-col items-center text-center">
              <div className="h-10 w-10 rounded-full bg-stage-contracted flex items-center justify-center mb-1">
                <benefit.icon className="h-5 w-5 text-stage-contracted-fg" />
              </div>
              <span className="text-xs text-muted-foreground max-w-[80px]">{benefit.text}</span>
            </div>
          ))}
        </div>

        <Card>
          <CardHeader className="text-center">
            <CardTitle>Create your account</CardTitle>
            <CardDescription>
              Join 50,000+ creators already earning on our platform
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {/* Social Register Options */}
            <div className="grid grid-cols-2 gap-3">
              <Button
                variant="outline"
                className="h-12"
                onClick={() => handleSocialRegister('instagram')}
                disabled={isLoading}
              >
                <Instagram className="h-5 w-5 mr-2 text-pink-500" />
                Instagram
              </Button>
              <Button
                variant="outline"
                className="h-12"
                onClick={() => handleSocialRegister('youtube')}
                disabled={isLoading}
              >
                <Youtube className="h-5 w-5 mr-2 text-red-500" />
                YouTube
              </Button>
            </div>

            <p className="text-xs text-center text-muted-foreground">
              Connect your social account to auto-import your profile
            </p>

            <div className="relative">
              <div className="absolute inset-0 flex items-center">
                <Separator className="w-full" />
              </div>
              <div className="relative flex justify-center text-xs uppercase">
                <span className="bg-card px-2 text-muted-foreground">
                  Or register with phone
                </span>
              </div>
            </div>

            {/* Phone Registration */}
            {step === 'info' && (
              <div className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="name">Your Name</Label>
                  <Input
                    id="name"
                    placeholder="Enter your full name"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="phone">Phone Number</Label>
                  <div className="flex gap-2">
                    <div className="flex items-center px-3 border rounded-md bg-muted text-muted-foreground text-sm">
                      +91
                    </div>
                    <Input
                      id="phone"
                      type="tel"
                      placeholder="Enter your phone number"
                      value={formData.phone}
                      onChange={(e) => setFormData({ ...formData, phone: e.target.value })}
                      className="flex-1"
                      maxLength={10}
                    />
                  </div>
                </div>
                <div className="flex items-start space-x-2">
                  <Checkbox
                    id="terms"
                    checked={formData.acceptTerms}
                    onCheckedChange={(checked) => setFormData({ ...formData, acceptTerms: !!checked })}
                  />
                  <label htmlFor="terms" className="text-xs text-muted-foreground leading-tight cursor-pointer">
                    I agree to the{' '}
                    <a href="#" className="text-primary hover:underline">Terms of Service</a>
                    {' '}and{' '}
                    <a href="#" className="text-primary hover:underline">Privacy Policy</a>
                  </label>
                </div>
                <Button
                  className="w-full bg-primary hover:bg-primary/90"
                  onClick={handleSendOTP}
                  disabled={isLoading || !formData.name || formData.phone.length < 10 || !formData.acceptTerms}
                >
                  {isLoading ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Sending OTP...
                    </>
                  ) : (
                    <>
                      Continue
                      <ArrowRight className="ml-2 h-4 w-4" />
                    </>
                  )}
                </Button>
              </div>
            )}

            {step === 'otp' && (
              <div className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="otp">Enter OTP</Label>
                  <Input
                    id="otp"
                    type="text"
                    placeholder="Enter 6-digit OTP"
                    value={formData.otp}
                    onChange={(e) => setFormData({ ...formData, otp: e.target.value })}
                    maxLength={6}
                    className="text-center text-lg tracking-widest"
                  />
                  <p className="text-xs text-muted-foreground text-center">
                    OTP sent to +91 {formData.phone}
                    <button
                      type="button"
                      className="ml-2 text-primary hover:underline"
                      onClick={() => setStep('info')}
                    >
                      Change
                    </button>
                  </p>
                </div>
                <Button
                  className="w-full bg-primary hover:bg-primary/90"
                  onClick={handleVerifyOTP}
                  disabled={isLoading || formData.otp.length < 4}
                >
                  {isLoading ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Creating account...
                    </>
                  ) : (
                    <>
                      Create Account
                      <ArrowRight className="ml-2 h-4 w-4" />
                    </>
                  )}
                </Button>
                <button
                  type="button"
                  className="w-full text-sm text-muted-foreground hover:text-primary"
                  onClick={handleSendOTP}
                  disabled={isLoading}
                >
                  Resend OTP
                </button>
              </div>
            )}
          </CardContent>
          <CardFooter className="flex flex-col gap-4">
            <p className="text-sm text-center text-muted-foreground">
              Already have an account?{' '}
              <Link to="/creator/login" className="text-primary hover:underline font-medium">
                Sign in
              </Link>
            </p>
          </CardFooter>
        </Card>

        {/* Brand Link */}
        <div className="text-center">
          <Link to="/brand/register" className="text-sm text-muted-foreground hover:text-primary">
            Are you a brand? Register here
          </Link>
        </div>
      </div>
    </div>
  );
}
