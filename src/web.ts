import { WebPlugin } from '@capacitor/core';

import type { VideoHighFpsPluginPlugin } from './definitions';

export class VideoHighFpsPluginWeb extends WebPlugin implements VideoHighFpsPluginPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
