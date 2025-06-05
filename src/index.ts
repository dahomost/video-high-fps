import { registerPlugin } from '@capacitor/core';

import type { VideoHighFpsPlugin } from './definitions';

const VideoHighFpsPlugin = registerPlugin<VideoHighFpsPlugin>('VideoHighFpsPlugin', {
  web: () => import('./web').then((m) => new m.VideoHighFpsPluginWeb()),
});

export * from './definitions';
export { VideoHighFpsPlugin };
