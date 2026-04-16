package com.albionplayersradar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ThreatLevel
import kotlin.math.min
import kotlin.math.sqrt

class PlayerRendererView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(ctx, attrs, defStyleAttr) {

    private var localX = 0f
    private var localY = 0f
    private var players = listOf<Player>()
    private var pvpType = "safe"
    private var scale = 50f
    private var maxDist = 100f

    private val bgPaint = Paint().apply { color = Color.parseColor("#1a1a2e"); style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333366"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0000"); style = Paint.Style.STROKE; strokeWidth = 10f
    }
    private val hpBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
    private val hpFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
    private val hostilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL }
    private val factionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 165, 0); style = Paint.Style.FILL }
    private val passivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 255, 136); style = Paint.Style.FILL }

    private var hasHostileNearby = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (ring in listOf(25f, 50f, 75f)) {
            val r = ring * scale
            if (r < min(cx, cy)) {
                ringPaint.alpha = 50
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
        }

        canvas.drawCircle(cx, cy, 8f, localPaint)

        hasHostileNearby = false
        for (p in players) {
            val dx = (p.posX - localX) * scale
            val dy = (p.posY - localY) * scale
            val px = cx + dx
            val py = cy + dy

            if (px < -50 || px > width + 50 || py < -50 || py > height + 50) continue

            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxDist * scale) continue

            val paint = when {
                p.threatLevel == ThreatLevel.HOSTILE -> { hasHostileNearby = true; hostilePaint }
                p.threatLevel == ThreatLevel.FACTION -> factionPaint
                else -> passivePaint
            }

            val dotR = if (p.isMounted) 12f else 8f
            canvas.drawCircle(px, py, dotR, paint)

            if (p.maxHealth > 0) {
                val bw = 40f; val bh = 4f
                val hp = p.healthPercent
                canvas.drawRect(px - bw / 2, py - 16f, px - bw / 2 + bw, py - 16f + bh, hpBgPaint)
                canvas.drawRect(px - bw / 2, py - 16f, px - bw / 2 + bw * hp, py - 16f + bh, hpFgPaint)
            }

            if (dist < 70f) {
                val label = if (p.guildName != null) {
                    "${p.name.take(6)}[${p.guildName.take(4)}]"
                } else {
                    p.name.take(8)
                }
                canvas.drawText(label, px, py + 18f, textPaint.apply { textSize = 18f })
            }

            canvas.drawText("%.0f".format(dist / scale), px + 10, py - 8, textPaint.apply { textSize = 16f })
        }

        if (hasHostileNearby && pvpType == "black") {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), alertPaint)
        }
    }

    fun updateData(lx: Float, ly: Float, playerList: List<Player>, zoneId: String) {
        localX = lx; localY = ly; players = playerList
        invalidate()
    }

    fun setPvPType(type: String) {
        pvpType = type
        invalidate()
    }
}
