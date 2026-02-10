BUILDING ...

搞不定微信数据获取 准备移植OCR

Android PPOCR 集成说明（wx-server）

1. 已集成依赖：`com.github.equationl.paddleocr4android:fastdeplyocr:v1.2.9`
2. 识别入口：`wx-server/src/main/java/com/ws/kimi_server/ocr/PPOcrRecognizer.kt`
3. 截图链路已接入：`wx-server/src/main/java/com/ws/kimi_server/acc/MyAccessibilityService.kt`
4. 模型目录（Android 端）：`/data/data/<你的包名>/files/ppocr/`
5. 需要放入的文件名：
   - `ch_PP-OCRv3_det_infer.onnx`
   - `ch_PP-OCRv3_rec_infer.onnx`
   - `ch_ppocr_mobile_v2.0_cls_infer.onnx`
   - `ppocr_keys_v1.txt`
