import type { NextConfig } from 'next';

// Production: a static export under /ui, served by agent-app (Maven packs out/ into the jar).
// Development (npm run dev, port 5173): the API and the UI config come from agent-app on 8080.
const production = process.env.NODE_ENV === 'production';

const config: NextConfig = {
  basePath: '/ui',
  trailingSlash: true,
  images: { unoptimized: true },
  ...(production
    ? { output: 'export' }
    : {
        async rewrites() {
          return [
            { source: '/api/:path*', destination: 'http://localhost:8080/api/:path*', basePath: false },
            { source: '/ui/config.json', destination: 'http://localhost:8080/ui/config.json', basePath: false },
          ];
        },
      }),
};

export default config;
