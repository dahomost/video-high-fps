import { WebPlugin } from '@capacitor/core';
import type { CaptureVideoOptions, MediaFileResult } from './definitions';

export class VideoHighFpsWeb extends WebPlugin {
  async openCamera(_options: CaptureVideoOptions): Promise<MediaFileResult> {
    throw this.unimplemented('openCamera is not available on web.');
  }
}
