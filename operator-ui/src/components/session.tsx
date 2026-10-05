'use client';

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { signIn, type User } from '@/lib/auth';

const SessionContext = createContext<User | null>(null);

export const useUser = () => useContext(SessionContext)!;
export const isApprover = (user: User) => user.roles.includes('approver');

/** Renders its children only once the user is logged in (Keycloak, PKCE). */
export function Session({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    signIn().then(setUser, (e: Error) => setError(e.message));
  }, []);

  if (error) {
    return <p className="mx-auto mt-24 max-w-md text-center text-rose-300">{error}</p>;
  }
  if (!user) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center text-sm text-muted">
        <span className="mr-3 h-2 w-2 rounded-full bg-brand pulse-dot" /> Signing in…
      </div>
    );
  }
  return <SessionContext.Provider value={user}>{children}</SessionContext.Provider>;
}
