BUILDING ...

搞不定微信数据获取 准备移植OCR

Android PPOCR 集成说明（wx-server）

1. 已集成依赖：`com.github.equationl.paddleocr4android:fastdeplyocr:v1.2.9`
2. 识别入口：`wx-server/src/main/java/com/ws/kimi_server/ocr/PPOcrRecognizer.kt`
3. 截图链路已接入：`wx-server/src/main/java/com/ws/kimi_server/acc/MyAccessibilityService.kt`
4. 首次识别会自动下载模型到：`/data/data/<你的包名>/files/ppocr/`
5. 自动下载内容：
   - `det.pdmodel` + `det.pdiparams`
   - `rec.pdmodel` + `rec.pdiparams`
   - `cls.pdmodel` + `cls.pdiparams`
   - `ppocr_keys_v1.txt`
