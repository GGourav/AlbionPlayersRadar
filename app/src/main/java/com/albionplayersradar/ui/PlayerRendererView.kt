package com.albionplayersradar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ThreatLevel
import kotlin.math.sqrt

class PlayerRendererView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var players = listOf<Player>()
    private var localX = 0f
    private var localY = 0f
    private var currentZone = ""
    private var scale = 50f

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1a1a2e")
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint().apply {
        color = Color.parseColor("#333366")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val passivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88")
        style = Paint.Style.FILL
    }

    private val factionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA500")
        style = Paint.Style.FILL
    }

    private val hostilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0000")
        style = Paint.Style.FILL
    }

    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (i in 1..5) {
            canvas.drawCircle(cx, cy, i * 25f * scale / 50f, ringPaint)
        }

        canvas.drawCircle(cx, cy, 8f, localPaint)

        for (p in players) {
            if (p.isPassive) continue

            val dx = (p.posX - localX) * scale
            val dy = (p.posY - localY) * scale
            val px = cx + dx
            val py = cy + dy

            if (px < -50 || px > width + 50 || py < -50 || py > height + 50) continue

            val dist = sqrt(dx * dx + dy * dy)
            val maxDist = 100f * scale / 50f
            if (dist > maxDist) continue

            val dotPaint = when {
                p.isHostile -> hostilePaint
                p.isFactionPlayer -> factionPaint
                else -> passivePaint
            }

            val dotSize = if (p.isHostile) 14f else 10f
            canvas.drawCircle(px, py, dotSize, dotPaint)

            if (dist < 80f) {
                val name = if (p.guildName.isNullOrEmpty()) p.name else "${p.name} [${p.guildName}]"
                canvas.drawText(name.take(12), px, py - 14f, namePaint)
            }
        }
    }

    fun updateData(locX: Float, locY: Float, playerList: List<Player>, zone: String) {
        localX = locX
        localY = locY
        players = playerList
        currentZone = zone
        invalidate()
    }

    fun setScale(s: Float) {
        scale = s
        invalidate()
    }
}
