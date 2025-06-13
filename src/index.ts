import { registerPlugin } from '@capacitor/core';
import type { TpaCameraPlugin } from './definitions';

const TpaCamera = registerPlugin<TpaCameraPlugin>('TpaCamera', {
  web: () => import('./web').then((m) => new m.TpaCameraWeb()),
});

export * from './definitions';
export { TpaCamera };
