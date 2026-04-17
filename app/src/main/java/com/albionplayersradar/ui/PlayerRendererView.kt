package com.albionplayersradar.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.albionplayersradar.data.Player
import com.albionplayersradar.data.ThreatLevel
import kotlin.math.min
import kotlin.math.sqrt

class PlayerRendererView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var localX = 0f
    private var localY = 0f
    private var players = listOf<Player>()
    private var pvpType = "safe"
    private var radarScale = 50f
    private var maxDistance = 100f

    private var showPassive = true
    private var showFaction = true
    private var showHostile = true
    private var showGuild = true
    private var showHealth = true
    private var showDist = false
    private var hasHostile = false

    private val bgPaint = Paint().apply { color = Color.parseColor("#1a1a2e"); style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#333366") }
    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER }
    private val alertPaint = Paint().apply { color = Color.parseColor("#FF0000"); style = Paint.Style.STROKE; strokeWidth = 8f }
    private val hpBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
    private val hpFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }

    private val playerPaints = mapOf(
        ThreatLevel.PASSIVE to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00FF88"); style = Paint.Style.FILL },
        ThreatLevel.FACTION to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFA500"); style = Paint.Style.FILL },
        ThreatLevel.HOSTILE to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF0000"); style = Paint.Style.FILL }
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (ring in listOf(25f, 50f, 75f)) {
            val radius = ring * radarScale
            if (radius < min(cx, cy)) canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        canvas.drawCircle(cx, cy, 8f, localPaint)

        for (player in players) {
            val show = when {
                player.isHostile && !showHostile -> false
                player.isFactionPlayer && !showFaction -> false
                player.isPassive && !showPassive -> false
                else -> true
            }
            if (!show) continue

            val dx = (player.posX - localX) * radarScale
            val dy = (player.posY - localY) * radarScale
            val px = cx + dx
            val py = cy + dy

            if (px < -50 || px > width + 50 || py < -50 || py > height + 50) continue
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxDistance * radarScale) continue

            val paint = playerPaints[player.threatLevel] ?: playerPaints[ThreatLevel.PASSIVE]!!
            val dotSize = if (player.isHostile) 12f else 8f
            canvas.drawCircle(px, py, dotSize, paint)

            if (showHealth && player.maxHealth > 0) {
                val bw = 40f; val bh = 4f; val hp = player.healthPercent
                canvas.drawRect(px - bw/2, py - 14f, px - bw/2 + bw, py - 14f + bh, hpBgPaint)
                canvas.drawRect(px - bw/2, py - 14f, px - bw/2 + bw * hp, py - 14f + bh, hpFgPaint)
            }

            if (showGuild && player.guildName != null) {
                canvas.drawText("${player.name.take(6)}[${player.guildName.take(4)}]", px, py + 20, textPaint.apply { textSize = 18f })
            } else if (showGuild) {
                canvas.drawText(player.name.take(8), px, py + 20, textPaint.apply { textSize = 18f })
            }

            if (showDist) canvas.drawText("%.0fm".format(dist), px + 10, py - 10, textPaint.apply { textSize = 16f })
        }

        if (hasHostile && pvpType == "black") {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), alertPaint)
        }
    }

    fun updateData(localPlayerX: Float, localPlayerY: Float, playerList: List<Player>, zoneId: String) {
        localX = localPlayerX
        localY = localPlayerY
        players = playerList
        hasHostile = playerList.any { it.isHostile }
        pvpType = zoneId
        invalidate()
    }

    fun setPvPType(type: String) { pvpType = type; invalidate() }
    fun setScale(s: Float) { radarScale = s; invalidate() }
    fun setShowPassive(v: Boolean) { showPassive = v; invalidate() }
    fun setShowFaction(v: Boolean) { showFaction = v; invalidate() }
    fun setShowHostile(v: Boolean) { showHostile = v; invalidate() }
    fun setShowGuild(v: Boolean) { showGuild = v; invalidate() }
    fun setShowHealth(v: Boolean) { showHealth = v; invalidate() }
    fun setShowDistance(v: Boolean) { showDist = v; invalidate() }
}
