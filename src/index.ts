import { registerPlugin } from '@capacitor/core';

import type { VideoHighFpsPluginPlugin } from './definitions';

const VideoHighFpsPlugin = registerPlugin<VideoHighFpsPluginPlugin>('VideoHighFpsPlugin', {
  web: () => import('./web').then((m) => new m.VideoHighFpsPluginWeb()),
});

export * from './definitions';
export { VideoHighFpsPlugin };
