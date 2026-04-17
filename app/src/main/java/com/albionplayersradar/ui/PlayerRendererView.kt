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
    private var showDistance = false

    private val bgPaint = Paint().apply { color = Color.parseColor("#1a1a2e"); style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#333366")
    }
    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER
    }
    private val alertPaint = Paint().apply {
        color = Color.parseColor("#FF000033"); style = Paint.Style.FILL
    }
    private val hostileAlert = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.RED
    }

    private val passivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF88"); style = Paint.Style.FILL
    }
    private val factionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA500"); style = Paint.Style.FILL
    }
    private val hostilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444"); style = Paint.Style.FILL
    }
    private val hpBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
    private val hpGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
    private val hpRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (ring in listOf(25f, 50f, 75f)) {
            val r = ring * radarScale
            if (r < min(cx, cy)) canvas.drawCircle(cx, cy, r, ringPaint)
        }

        canvas.drawCircle(cx, cy, 8f, localPaint)

        for (player in players) {
            val dx = (player.posX - localX) * radarScale
            val dy = (player.posY - localY) * radarScale
            val px = cx + dx
            val py = cy + dy
            if (px < 0 || px > width || py < 0 || py > height) continue
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > maxDistance * radarScale) continue
            if (!shouldShow(player)) continue

            val dotR = if (player.isMounted) 12f else 8f
            val paint = when (player.threatLevel) {
                ThreatLevel.HOSTILE -> hostilePaint
                ThreatLevel.FACTION -> factionPaint
                ThreatLevel.PASSIVE -> passivePaint
            }
            canvas.drawCircle(px, py, dotR, paint)

            if (showHealth && player.maxHealth > 0) {
                val bw = 40f; val bh = 4f
                val hp = player.healthPercent
                canvas.drawRect(px - bw / 2, py - 16f, px - bw / 2 + bw, py - 16f + bh, hpBgPaint)
                val hpPaint = if (hp > 0.5f) hpGreen else hpRed
                canvas.drawRect(px - bw / 2, py - 16f, px - bw / 2 + bw * hp, py - 16f + bh, hpPaint)
            }

            val label = if (showGuild && player.guildName != null) {
                "${player.name.take(8)}[${player.guildName.take(4)}]"
            } else {
                player.name.take(10)
            }
            if (dist < 80f) canvas.drawText(label, px, py + 20f, textPaint)
            if (showDistance) canvas.drawText("%.0fm".format(dist), px + 10f, py - 10f, textPaint)
        }

        val hasHostile = players.any { it.threatLevel == ThreatLevel.HOSTILE }
        if (hasHostile && pvpType == "black") {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), alertPaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), hostileAlert)
        }
    }

    private fun shouldShow(p: Player): Boolean {
        return when {
            p.threatLevel == ThreatLevel.HOSTILE && showHostile -> true
            p.threatLevel == ThreatLevel.FACTION && showFaction -> true
            p.threatLevel == ThreatLevel.PASSIVE && showPassive -> true
            else -> false
        }
    }

    fun updateData(lx: Float, ly: Float, list: List<Player>, zoneId: String, zonePvP: String) {
        localX = lx; localY = ly; players = list; pvpType = zonePvP; invalidate()
    }
    fun setShowPassive(v: Boolean) { showPassive = v; invalidate() }
    fun setShowFaction(v: Boolean) { showFaction = v; invalidate() }
    fun setShowHostile(v: Boolean) { showHostile = v; invalidate() }
    fun setShowGuild(v: Boolean) { showGuild = v; invalidate() }
    fun setShowHealth(v: Boolean) { showHealth = v; invalidate() }
    fun setShowDistance(v: Boolean) { showDistance = v; invalidate() }
    fun setScale(s: Float) { radarScale = s; invalidate() }
}
