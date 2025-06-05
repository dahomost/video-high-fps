import { WebPlugin } from '@capacitor/core';
import type { VideoHighFpsPlugin, CaptureVideoOptions, MediaFileResult } from './definitions';

export class VideoHighFpsWeb extends WebPlugin implements VideoHighFpsPlugin {
  async startRecording(_options: CaptureVideoOptions): Promise<MediaFileResult> {
    throw this.unimplemented('startRecording is not available on web.');
  }

  async stopRecording(): Promise<{ videoPath: string }> {
    throw this.unimplemented('stopRecording is not available on web.');
  }
}
