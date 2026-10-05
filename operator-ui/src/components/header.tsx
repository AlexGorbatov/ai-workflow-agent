'use client';

import Link from 'next/link';
import { LogOut, ShieldCheck, UserRound } from 'lucide-react';
import { logout } from '@/lib/auth';
import { isApprover, useUser } from './session';

export function Header() {
  const user = useUser();
  return (
    <header className="sticky top-0 z-20 border-b border-line bg-ink/70 backdrop-blur-xl">
      <div className="mx-auto flex h-16 max-w-7xl items-center gap-4 px-4 sm:px-6">
        <Link href="/" className="flex items-center gap-3">
          <span className="grid h-9 w-9 place-items-center rounded-xl bg-gradient-to-br from-brand to-brand-2 font-semibold text-white shadow-lg shadow-brand/30">
            A
          </span>
          <span className="leading-tight">
            <span className="block text-sm font-semibold tracking-tight">Quote Agent</span>
            <span className="block text-[11px] text-muted">by Altronix Soft</span>
          </span>
        </Link>
        <div className="flex-1" />
        <div className="hidden items-center gap-2 rounded-full border border-line bg-white/[0.03] px-3 py-1.5 text-xs sm:flex">
          {isApprover(user) ? <ShieldCheck className="h-3.5 w-3.5 text-emerald-300" /> : <UserRound className="h-3.5 w-3.5 text-muted" />}
          <span className="font-medium">{user.name}</span>
          <span className="text-muted">{isApprover(user) ? 'approver' : 'operator'}</span>
        </div>
        <button onClick={logout} className="btn btn-ghost !px-3 !py-2" aria-label="Log out">
          <LogOut className="h-4 w-4" />
          <span className="hidden sm:inline">Log out</span>
        </button>
      </div>
    </header>
  );
}
