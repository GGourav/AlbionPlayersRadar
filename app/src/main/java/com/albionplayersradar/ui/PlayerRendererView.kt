package com.albionplayersradar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ThreatLevel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PlayerRendererView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }
    private val path = Path()

    private val players = mutableListOf<Player>()
    private var localX = 0f
    private var localY = 0f
    private var localAngle = 0f
    private var scale = 1f

    private val threatColors = mapOf(
        ThreatLevel.PASSIVE to Color.rgb(0, 200, 100),
        ThreatLevel.FACTION to Color.rgb(255, 200, 0),
        ThreatLevel.HOSTILE to Color.rgb(255, 60, 60)
    )

    fun updatePlayers(newPlayers: List<Player>, localPos: Pair<Float, Float>?, localAngle: Float) {
        players.clear()
        players.addAll(newPlayers)
        localPos?.let { localX = it.first; localY = it.second }
        this.localAngle = localAngle
        scale = min(width, height).toFloat() / 100f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f

        // Draw local player arrow
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        drawArrow(canvas, cx, cy, localAngle, 20f, Color.WHITE)

        // Draw grid
        paint.color = Color.argb(40, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        for (i in -5..5) {
            val pos = (min(width, height) / 2f) * i / 5f
            canvas.drawLine(cx + pos, 0f, cx + pos, height.toFloat(), paint)
            canvas.drawLine(0f, cy + pos, width.toFloat(), cy + pos, paint)
        }

        // Draw players
        for (player in players) {
            val dx = (player.posX - localX) * scale
            val dy = -(player.posY - localY) * scale  // Y is inverted
            val px = cx + dx
            val py = cy + dy

            if (px < -50 || px > width + 50 || py < -50 || py > height + 50) continue

            val color = threatColors[player.threat] ?: Color.WHITE
            paint.color = color
            paint.style = Paint.Style.FILL

            // Calculate direction angle from movement delta
            val angle = if (player.deltaX != 0f || player.deltaY != 0f) {
                atan2(-player.deltaY, player.deltaX)  // negated deltaY because screen Y is inverted
            } else {
                0f
            }

            val size = 12f
            drawArrow(canvas, px, py, angle, size, color)

            // Draw name
            canvas.drawText(player.name, px + 14, py + 8, textPaint.apply { this.color = color })
            if (player.guild.isNotEmpty()) {
                canvas.drawText("[${player.guild}]", px + 14, py + 36,
                    textPaint.apply { this.color = Color.argb(180, 255, 255, 255) })
            }
        }
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, angleRad: Float, size: Float, color: Int) {
        paint.color = color
        path.reset()
        path.moveTo(
            x + size * cos(angleRad),
            y - size * sin(angleRad)  // negated for screen Y inversion
        )
        path.lineTo(
            x + size * 0.6f * cos(angleRad + 2.5f),
            y - size * 0.6f * sin(angleRad + 2.5f)
        )
        path.lineTo(
            x + size * 0.4f * cos(angleRad),
            y - size * 0.4f * sin(angleRad)
        )
        path.lineTo(
            x + size * 0.6f * cos(angleRad - 2.5f),
            y - size * 0.6f * sin(angleRad - 2.5f)
        )
        path.close()
        canvas.drawPath(path, paint)
    }
}
