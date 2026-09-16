import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['app-icon-uppro.png', 'logo-uppro-wordmark.png'],
      manifest: {
        name: 'UP.PRO',
        short_name: 'UP.PRO',
        description: 'Treinos guiados, plano semanal e acompanhamento do seu progresso.',
        lang: 'pt-BR',
        start_url: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#17171b',
        theme_color: '#17171b',
        icons: [
          { src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' }
        ]
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,png,svg,woff2}', 'data/*.json'],
        globIgnores: ['media/**'],
        maximumFileSizeToCacheInBytes: 8 * 1024 * 1024,
        runtimeCaching: [
          {
            urlPattern: ({ url }) => url.pathname.startsWith('/media/'),
            handler: 'CacheFirst',
            options: { cacheName: 'uppro-media', expiration: { maxEntries: 3000, maxAgeSeconds: 60 * 60 * 24 * 365 } }
          },
          {
            // Cache dos blocos do mapa, como o flutter_map_cache do RootStep.
            urlPattern: ({ url }) => url.hostname === 'tile.openstreetmap.org',
            handler: 'CacheFirst',
            options: { cacheName: 'uppro-mapa', expiration: { maxEntries: 2000, maxAgeSeconds: 60 * 60 * 24 * 30 } }
          },
          {
            urlPattern: ({ url }) => url.origin.includes('fonts.googleapis.com') || url.origin.includes('fonts.gstatic.com'),
            handler: 'StaleWhileRevalidate',
            options: { cacheName: 'uppro-fonts' }
          }
        ]
      }
    })
  ],
  server: { host: true }
});
