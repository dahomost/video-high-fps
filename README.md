# @dahomoha/video-high-fps

A Capacitor plugin to record high FPS (120fps) video with preview on Android

## Install

```bash
npm install @dahomoha/video-high-fps
npx cap sync
```

## API

<docgen-index>

* [`openCamera(...)`](#opencamera)
* [Interfaces](#interfaces)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### openCamera(...)

```typescript
openCamera(options: CaptureVideoOptions) => Promise<MediaFileResult>
```

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#capturevideooptions">CaptureVideoOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#mediafileresult">MediaFileResult</a>&gt;</code>

--------------------


### Interfaces


#### MediaFileResult

| Prop            | Type                | Description                                    |
| --------------- | ------------------- | ---------------------------------------------- |
| **`videoPath`** | <code>string</code> | Absolute local file path of the recorded video |


#### CaptureVideoOptions

| Prop            | Type                                | Description                                                                    |
| --------------- | ----------------------------------- | ------------------------------------------------------------------------------ |
| **`duration`**  | <code>number</code>                 | Maximum duration in seconds (0 = unlimited)                                    |
| **`frameRate`** | <code>number</code>                 | Desired frame rate, e.g., 30, 60, 120                                          |
| **`sizeLimit`** | <code>number</code>                 | Max file size in bytes (e.g., 50_000_000 for 50MB)                             |
| **`quality`**   | <code>'hd' \| 'fhd' \| 'uhd'</code> | Video quality preset - 'hd' = 1280x720 - 'fhd' = 1920x1080 - 'uhd' = 3840x2160 |

</docgen-api>
