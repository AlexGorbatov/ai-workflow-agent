import type { Metadata } from 'next';
import { GeistSans } from 'geist/font/sans';
import { GeistMono } from 'geist/font/mono';
import { Header } from '@/components/header';
import { Session } from '@/components/session';
import './globals.css';

export const metadata: Metadata = {
  title: 'Quote Agent — operator console',
  description: 'AI agent that turns freight quote requests from email into approved, priced replies.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${GeistSans.variable} ${GeistMono.variable}`}>
      <body className="min-h-screen">
        <div className="backdrop" />
        <Session>
          <Header />
          <main className="mx-auto max-w-7xl px-4 pb-20 pt-8 sm:px-6">{children}</main>
        </Session>
      </body>
    </html>
  );
}
