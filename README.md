BUILDING ...

鎼炰笉瀹氬井淇℃暟鎹幏鍙?鍑嗗绉绘OCR

Android PPOCR 闆嗘垚璇存槑锛坵x-server锛?
1. 宸查泦鎴愪緷璧栵細`ncnn + opencv-mobile (auto download at build time)`
2. 璇嗗埆鍏ュ彛锛歚wx-server/src/main/java/com/ws/**/ocr/PPOcrRecognizer.kt`
3. 鎴浘閾捐矾宸叉帴鍏ワ細`wx-server/src/main/java/com/ws/**/acc/MyAccessibilityService.kt`
4. 模型和字典在构建阶段自动下载并打包到 APK assets（无需运行时下载）
5. 鑷姩涓嬭浇鍐呭锛?   - `pdocrv2.0_det-op.param + pdocrv2.0_det-op.bin`
   - `pdocrv2.0_rec-op.param + pdocrv2.0_rec-op.bin`
   - `paddleocr_keys.txt`
   - `PaddleOCRNcnn.java + native paddleocrncnn`


