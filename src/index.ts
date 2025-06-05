import { registerPlugin } from '@capacitor/core';
import type { VideoHighFpsPlugin } from './definitions';

const VideoHighFps = registerPlugin<VideoHighFpsPlugin>('VideoHighFps', {
  web: () => import('./web').then((m) => new m.VideoHighFpsWeb()),
});

export * from './definitions';
export { VideoHighFps };
