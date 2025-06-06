export interface CaptureVideoOptions {
  /**
   * Maximum duration in seconds (0 = unlimited)
   */
  duration?: number;

  /**
   * Desired frame rate, e.g., 30, 60, 120
   */
  frameRate?: number;

  /**
   * Max file size in bytes (e.g., 50_000_000 for 50MB)
   */
  sizeLimit?: number;

  /**
   * Video quality preset
   * - 'hd' = 1280x720
   * - 'fhd' = 1920x1080
   * - 'uhd' = 3840x2160
   */
  quality?: 'hd' | 'fhd' | 'uhd';
}

export interface MediaFileResult {
  /**
   * Absolute local file path of the recorded video
   */
  videoPath: string;
}

export interface VideoHighFpsPlugin {
  openCamera(options: CaptureVideoOptions): Promise<MediaFileResult>;
}
