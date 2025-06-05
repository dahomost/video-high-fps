import { WebPlugin } from '@capacitor/core';
import type { VideoHighFpsPlugin } from './definitions';

export class VideoHighFpsPluginWeb extends WebPlugin implements VideoHighFpsPlugin {
  async startRecording(): Promise<{ path: string }> {
    throw this.unimplemented('startRecording is not supported on web.');
  }

  async stopRecording(): Promise<{ videoPath: string }> {
    throw this.unimplemented('stopRecording is not supported on web.');
  }
}
