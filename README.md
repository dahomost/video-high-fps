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
* [Interfaces](#interfaces)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### startRecording(...)

```typescript
startRecording(options: videoOptions) => Promise<VideoRecordingResult>
```

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#videooptions">videoOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#videorecordingresult">VideoRecordingResult</a>&gt;</code>

--------------------


### Interfaces


#### VideoRecordingResult

| Prop            | Type                |
| --------------- | ------------------- |
| **`videoPath`** | <code>string</code> |
| **`duration`**  | <code>number</code> |


#### videoOptions

| Prop                | Type                                   |
| ------------------- | -------------------------------------- |
| **`resolution`**    | <code>'720p' \| '1080p' \| '4k'</code> |
| **`fps`**           | <code>number</code>                    |
| **`sizeLimit`**     | <code>number</code>                    |
| **`slowMotion`**    | <code>boolean</code>                   |
| **`saveToLibrary`** | <code>boolean</code>                   |
| **`title`**         | <code>string</code>                    |

</docgen-api>
