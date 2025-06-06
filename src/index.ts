import { registerPlugin } from '@capacitor/core';
import type { VideoHighFpsPlugin } from './definitions';

export const VideoHighFps = registerPlugin<VideoHighFpsPlugin>('VideoHighFps', {
  web: () => import('./web').then((m) => new m.VideoHighFpsWeb()),
});
