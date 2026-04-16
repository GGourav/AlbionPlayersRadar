package com.albionplayersradar.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ThreatLevel

class PlayerRendererView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var localX = 0f
    private var localY = 0f
    private var players = listOf<Player>()
    private var pvpType = "safe"
    private var radarScale = 50f  // pixels per game unit
    private var maxDistance = 100f // max display distance

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#1a1a2e")
        style = Paint.Style.FILL
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#333366")
    }

    private val playerPaints = mapOf(
        ThreatLevel.PASSIVE to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FF88")
            style = Paint.Style.FILL
        },
        ThreatLevel.FACTION to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFA500")
            style = Paint.Style.FILL
        },
        ThreatLevel.HOSTILE to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF0000")
            style = Paint.Style.FILL
        }
    )

    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0000")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private var hasHostile = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Draw range rings
        for (ring in listOf(25f, 50f, 75f)) {
            val radius = ring * radarScale
            if (radius < Math.min(cx, cy)) {
                canvas.drawCircle(cx, cy, radius, circlePaint)
            }
        }

        // Draw center dot (local player)
        canvas.drawCircle(cx, cy, 8f, localPaint)

        // Draw players
        for (player in players) {
            if (player.isPassive && pvpType == "safe") continue
            if (player.isPassive && pvpType in listOf("yellow", "red")) continue

            val dx = (player.posX - localX) * radarScale
            val dy = (player.posY - localY) * radarScale

            val px = cx + dx
            val py = cy + dy

            if (px < 0 || px > width || py < 0 || py > height) continue

            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > maxDistance * radarScale) continue

            val paint = playerPaints[player.threatLevel] ?: playerPaints[ThreatLevel.PASSIVE]!!

            // Draw as dot
            val dotSize = if (player.isHostile) 12f else 8f
            canvas.drawCircle(px, py, dotSize, paint)

            // Draw name
            if (dist < 60f) {
                canvas.drawText(player.name.take(8), px, py - 10f, textPaint.apply { textSize = 20f })
            }
        }

        // Hostile border alert
        if (hasHostile && pvpType == "black") {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), alertPaint)
        }
    }

    fun updateData(localPlayerX: Float, localPlayerY: Float, playerList: List<Player>, zoneId: String) {
        localX = localPlayerX
        localY = localPlayerY
        players = playerList
        hasHostile = playerList.any { it.isHostile }
        invalidate()
    }

    fun setPvPType(type: String) {
        pvpType = type
        invalidate()
    }
}
