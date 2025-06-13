export interface TpaCameraPlugin {
  startRecording(options: videoOptions): Promise<VideoRecordingResult>;
}

export interface videoOptions {
  resolution: '720p' | '1080p' | '4k';
  fps: number;
  sizeLimit: number;
  slowMotion?: boolean;
  saveToLibrary?: boolean;
  title?: string;
}

export interface VideoRecordingResult {
  videoPath: string;
  duration?: number;
}
