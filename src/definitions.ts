export interface VideoHighFpsPlugin {
  startRecording(): Promise<{ path: string }>;
  stopRecording(): Promise<{ videoPath: string }>;
}
