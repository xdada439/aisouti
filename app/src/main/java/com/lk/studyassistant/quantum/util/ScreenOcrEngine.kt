package com.lk.studyassistant.quantum.util

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object ScreenOcrEngine {
    const val ENGINE_NAME = "ML Kit Chinese Text Recognition"

    data class StructuredOcrResult(
        val fullText: String,
        val blocks: List<OcrBlock>,
        /** 所有行按 Y 坐标从上到下排序 */
        val sortedLines: List<OcrLine>,
        val success: Boolean,
        val errorMessage: String = ""
    )

    suspend fun recognizeBest(bitmaps: List<Bitmap>): StructuredOcrResult {
        var best: StructuredOcrResult? = null
        bitmaps.forEachIndexed { index, bitmap ->
            AppLogger.log("[OCR] variant=$index input=${bitmap.width}x${bitmap.height}")
            val result = recognize(bitmap)
            val currentScore = ocrQualityScore(result.fullText)
            val bestScore = ocrQualityScore(best?.fullText.orEmpty())
            AppLogger.log("[OCR] variant=$index success=${result.success} score=$currentScore length=${result.fullText.length}")
            if (best == null || currentScore > bestScore) best = result
        }
        return best ?: StructuredOcrResult("", emptyList(), emptyList(), false, "OCR_ENGINE_NOT_READY")
    }

    private fun ocrQualityScore(text: String): Int {
        if (text.isBlank()) return 0
        val optionHits = Regex("(?m)^\\s*\\(?[A-Da-d]\\)?[).:：、]?").findAll(text).count()
        val chineseCount = text.count { it in '\u4e00'..'\u9fff' }
        val digitCount = text.count { it.isDigit() }
        return chineseCount + digitCount + optionHits * 80 + if (text.contains("单选") || text.contains("多选")) 40 else 0
    }

    suspend fun recognize(bitmap: Bitmap): StructuredOcrResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            AppLogger.log("[OCR] engine=ML_KIT")
            AppLogger.log("[OCR] start")
            AppLogger.log("[OCR] bitmap_width=${bitmap.width}")
            AppLogger.log("[OCR] bitmap_height=${bitmap.height}")
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = runCatching {
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            }.getOrElse { e ->
                AppLogger.log("[OCR] failed OCR_ENGINE_NOT_READY ${e.message.orEmpty()}")
                continuation.resume(
                    StructuredOcrResult(
                        fullText = "",
                        blocks = emptyList(),
                        sortedLines = emptyList(),
                        success = false,
                        errorMessage = "OCR 初始化失败：${e.message.orEmpty()}"
                    )
                )
                return@suspendCancellableCoroutine
            }

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    AppLogger.log("[OCR] raw_text=${visionText.text.take(1500)}")
                    if (visionText.text.isBlank()) AppLogger.log("[OCR] failed OCR_RESULT_EMPTY")
                    val blocks = visionText.textBlocks.map { block ->
                        val lines = block.lines.map { line ->
                            val lineBounds = line.boundingBox ?: Rect(0, 0, 0, 0)
                            OcrLine(
                                text = line.text,
                                bounds = lineBounds,
                                confidence = line.confidence ?: 0.5f
                            )
                        }
                        val blockBounds = block.boundingBox ?: Rect(0, 0, 0, 0)
                        OcrBlock(
                            text = block.text,
                            lines = lines,
                            bounds = blockBounds
                        )
                    }

                    val sortedLines = blocks.flatMap { it.lines }
                        .sortedWith(compareBy<OcrLine> { it.bounds.top }
                            .thenBy { it.bounds.left })

                    continuation.resume(
                        StructuredOcrResult(
                            fullText = visionText.text,
                            blocks = blocks,
                            sortedLines = sortedLines,
                            success = true
                        )
                    )
                }
                .addOnFailureListener { e ->
                    AppLogger.log("[OCR] failed OCR_FAILED ${e.message.orEmpty()}")
                    continuation.resume(
                        StructuredOcrResult(
                            fullText = "",
                            blocks = emptyList(),
                            sortedLines = emptyList(),
                            success = false,
                            errorMessage = e.message ?: "OCR 识别失败"
                        )
                    )
                }
                .addOnCompleteListener {
                    runCatching { recognizer.close() }
                }
        }
    }
}
