package com.lk.studyassistant.quantum.util

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowManager

object Utils {
    fun isEmpty(src: String?): Boolean = src == null || src.trim().isEmpty()

    fun dp2px(context: Context, dip: Int): Int = (dip * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dp2px(context: Context, dip: Float): Int = (dip * context.resources.displayMetrics.density + 0.5f).toInt()

    fun sp2px(context: Context, spValue: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            spValue,
            context.resources.displayMetrics
        ).toInt()
    }

    fun calcStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else dp2px(context, 24f)
    }

    fun getRealScreenSize(context: Context): Point {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { point ->
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(point)
            }
        }
    }

    fun getViewRectOnScreen(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    fun clampRect(rect: Rect, maxWidth: Int, maxHeight: Int): Rect {
        val left = rect.left.coerceIn(0, maxWidth)
        val top = rect.top.coerceIn(0, maxHeight)
        val right = rect.right.coerceIn(left, maxWidth)
        val bottom = rect.bottom.coerceIn(top, maxHeight)
        return Rect(left, top, right, bottom)
    }
}
