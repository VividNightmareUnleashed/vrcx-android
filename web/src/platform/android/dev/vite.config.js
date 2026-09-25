// Dev server for the Android phone UI preview harness (README.md in this folder). Never used for builds.
//   cd web && npx vite --config src/platform/android/dev/vite.config.js
import { resolve } from 'node:path';

import { mergeConfig } from 'vite';

import baseConfig from '../../../vite.config.js';

export default async (env) => {
    // The shared config reads VRCX_TARGET when it is evaluated: this gives the Android define and targets.
    process.env.VRCX_TARGET = 'android';
    const base = await baseConfig(env);
    const webRoot = resolve(import.meta.dirname, '../../../..');

    return mergeConfig(base, {
        root: resolve(webRoot, 'src'),
        define: {
            ANDROID: JSON.stringify(true)
        },
        server: {
            port: Number(process.env.VRCX_PREVIEW_PORT ?? 9010),
            strictPort: false,
            open: false,
            fs: {
                allow: [webRoot]
            }
        }
    });
};
