export interface VideoHighFpsPluginPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
