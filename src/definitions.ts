export interface CaptureVideoOptions {
  duration?: number; // in seconds
  quality?: 'hd' | 'fhd' | 'uhd'; // resolution
  frameRate?: number; // FPS like 30, 60, 120
  sizeLimit?: number; // in bytes
}

export interface MediaFileResult {
  file: {
    path: string;
  };
}

export interface VideoHighFpsPlugin {
  startRecording(options: CaptureVideoOptions): Promise<MediaFileResult>;
  stopRecording(): Promise<{ videoPath: string }>;
}
