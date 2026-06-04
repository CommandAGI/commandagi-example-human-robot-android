package com.commandagi.humanrobot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A small labeled diagram of the wiring: [ Phone ] —USB→ [ ESP32 ] —Wi-Fi→ [ Drone ].
 * The ESP32 box lights up (accent border + filled dot) when one is detected over USB.
 */
class DroneWiringDiagram @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var espDetected: Boolean = false
        set(v) { field = v; invalidate() }
    var droneConnected: Boolean = false
        set(v) { field = v; invalidate() }

    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = sp(13f) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = sp(10f) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val muted = Color.parseColor("#9AA0A6")
    private val ink = Color.parseColor("#202124")
    private val accent = Color.parseColor("#1A73E8")
    private val good = Color.parseColor("#1E8E3E")
    private val bad = Color.parseColor("#D93025")

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(120f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(8f)
        val gap = dp(26f)
        val top = dp(28f)
        val boxH = dp(52f)
        val boxW = (width - pad * 2 - gap * 2) / 3f
        val ys = top
        val phoneR = RectF(pad, ys, pad + boxW, ys + boxH)
        val espR = RectF(phoneR.right + gap, ys, phoneR.right + gap + boxW, ys + boxH)
        val droneR = RectF(espR.right + gap, ys, espR.right + gap + boxW, ys + boxH)

        drawBox(canvas, phoneR, "Phone", ink, fillBg = true)
        drawBox(canvas, espR, "ESP32", if (espDetected) accent else muted, fillBg = espDetected)
        drawBox(canvas, droneR, "Drone", if (droneConnected) accent else muted, fillBg = false)

        // Connectors with link labels above.
        line.color = if (espDetected) accent else muted
        canvas.drawLine(phoneR.right, phoneR.centerY(), espR.left, espR.centerY(), line)
        small.color = if (espDetected) accent else muted
        canvas.drawText("USB-C", (phoneR.right + espR.left) / 2f, ys - dp(6f), small)

        line.color = if (droneConnected) accent else muted
        canvas.drawLine(espR.right, espR.centerY(), droneR.left, droneR.centerY(), line)
        small.color = if (droneConnected) accent else muted
        canvas.drawText("Wi-Fi", (espR.right + droneR.left) / 2f, ys - dp(6f), small)

        // Status dot under the ESP32 box.
        dot.color = if (espDetected) good else bad
        canvas.drawCircle(espR.centerX() - dp(34f), espR.bottom + dp(14f), dp(4f), dot)
        small.color = muted
        small.textAlign = Paint.Align.LEFT
        canvas.drawText(if (espDetected) "detected" else "not detected", espR.centerX() - dp(26f), espR.bottom + dp(18f), small)
        small.textAlign = Paint.Align.CENTER
    }

    private fun drawBox(canvas: Canvas, r: RectF, text: String, color: Int, fillBg: Boolean) {
        val rad = dp(12f)
        if (fillBg) {
            fill.color = Color.argb(20, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRoundRect(r, rad, rad, fill)
        }
        box.color = color
        canvas.drawRoundRect(r, rad, rad, box)
        label.color = ink
        canvas.drawText(text, r.centerX(), r.centerY() + sp(5f), label)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
