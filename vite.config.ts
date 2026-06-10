import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'T1D Harness',
        short_name: 'Harness',
        display: 'standalone',
        theme_color: '#0B0F14',
        background_color: '#0B0F14',
        icons: [
          { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: '/icon-maskable.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        // Dexie is the offline layer — never serve stale API/Supabase JSON from the SW.
        runtimeCaching: [
          { urlPattern: /^https:\/\/.*\.supabase\.co\/.*/, handler: 'NetworkOnly' },
          { urlPattern: /\/api\/.*/, handler: 'NetworkOnly' },
        ],
        navigateFallbackDenylist: [/^\/api\//],
      },
    }),
  ],
});
