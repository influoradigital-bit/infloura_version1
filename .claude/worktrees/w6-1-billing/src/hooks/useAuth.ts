import { useState, useCallback } from 'react';
import { clearBrandSession, hasBrandToken } from '@/lib/auth-session';
import { api } from '@/lib/api';

export const useAuth = () => {
  const [user, setUser] = useState(() => {
    const email = localStorage.getItem('brand_email');
    const company = localStorage.getItem('brand_company');
    return email ? { email, company: company || '' } : null;
  });

  const login = useCallback((email: string, company?: string) => {
    localStorage.setItem('brand_email', email);
    if (company) localStorage.setItem('brand_company', company);
    setUser({ email, company: company || '' });
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.auth.logout('brand');
    } catch {
      // ignore — still clear local session
    }
    clearBrandSession();
    setUser(null);
  }, []);

  const isAuthenticated = hasBrandToken();

  return { user, login, logout, isAuthenticated };
};
