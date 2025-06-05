# @dahomoha/video-high-fps

A Capacitor plugin to record high FPS (120fps) video with preview on Android

## Install

```bash
npm install @dahomoha/video-high-fps
npx cap sync
```

## API

<docgen-index>

* [`startRecording(...)`](#startrecording)
* [`stopRecording()`](#stoprecording)
* [Interfaces](#interfaces)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### startRecording(...)

```typescript
startRecording(options: CaptureVideoOptions) => Promise<MediaFileResult>
```

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#capturevideooptions">CaptureVideoOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#mediafileresult">MediaFileResult</a>&gt;</code>

--------------------


### stopRecording()

```typescript
stopRecording() => Promise<{ videoPath: string; }>
```

**Returns:** <code>Promise&lt;{ videoPath: string; }&gt;</code>

--------------------


### Interfaces


#### MediaFileResult

| Prop       | Type                           |
| ---------- | ------------------------------ |
| **`file`** | <code>{ path: string; }</code> |


#### CaptureVideoOptions

| Prop            | Type                                |
| --------------- | ----------------------------------- |
| **`duration`**  | <code>number</code>                 |
| **`quality`**   | <code>'hd' \| 'fhd' \| 'uhd'</code> |
| **`frameRate`** | <code>number</code>                 |
| **`sizeLimit`** | <code>number</code>                 |

</docgen-api>
