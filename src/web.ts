import { WebPlugin } from '@capacitor/core';
import type { TpaCameraPlugin, videoOptions, VideoRecordingResult } from './definitions';

export class TpaCameraWeb extends WebPlugin implements TpaCameraPlugin {
  async startRecording(_options: videoOptions): Promise<VideoRecordingResult> {
    throw this.unimplemented('open Camera plugin is not available on web.');
  }
}
