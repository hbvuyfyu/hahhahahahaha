package com.vcam.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import com.vcam.R
import com.vcam.ui.MainActivity
import com.vcam.ui.PreviewActivity
import com.vcam.utils.MediaSlotManager

class FloatWindowService : Service() {

    companion object {
        const val ACTION_START          = "com.vcam.float.START"
        const val ACTION_STOP_FLOAT     = "com.vcam.float.STOP"
        const val ACTION_UPDATE_STATUS  = "com.vcam.float.UPDATE_STATUS"
        const val EXTRA_TARGET_NAME     = "float_target_name"
        const val EXTRA_IS_VIDEO        = "float_is_video"
        const val ACTION_ROTATE         = "com.vcam.float.ROTATE"
        const val ACTION_MIRROR         = "com.vcam.float.MIRROR"
        const val ACTION_STOP_VCAM      = "com.vcam.float.STOP_VCAM"
        const val ACTION_SWITCH_SLOT    = "com.vcam.float.SWITCH_SLOT"
        const val EXTRA_SLOT            = "slot_number"
        private const val CHANNEL_ID    = "vcam_float_channel"
        private const val NOTIF_ID      = 1002
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentRotation = 0
    private var isMirrored = false
    private var activeSlot = 1
    private var isExpanded = false

    private val slotBtnIds = listOf(
        R.id.btn_slot_1, R.id.btn_slot_2, R.id.btn_slot_3,
        R.id.btn_slot_4, R.id.btn_slot_5
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val targetName = intent.getStringExtra(EXTRA_TARGET_NAME) ?: "All Apps"
                val isVideo    = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                startForeground(NOTIF_ID, buildNotification(targetName, isVideo))
                showFloatWindow(targetName, isVideo)
            }
            ACTION_STOP_FLOAT -> {
                removeFloatWindow()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { removeFloatWindow(); super.onDestroy() }

    // ── Float window ──────────────────────────────────────────────────

    private fun showFloatWindow(targetName: String, isVideo: Boolean) {
        if (floatView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.float_window, null)

        // Status labels
        view.findViewById<TextView>(R.id.tv_float_target)?.text =
            if (targetName.length > 18) targetName.take(16) + "…" else targetName
        updateTypeLabel(view, activeSlot)

        // ── Slot buttons ──
        slotBtnIds.forEachIndexed { idx, btnId ->
            val slot = idx + 1
            view.findViewById<TextView>(btnId)?.setOnClickListener {
                switchToSlot(view, slot)
            }
        }
        updateSlotButtonVisuals(view, activeSlot)

        // ── FAB main bubble — toggle expand/collapse ──
        val fabMain = view.findViewById<ImageView>(R.id.float_fab_main)
        val panel   = view.findViewById<View>(R.id.float_expanded_panel)

        fabMain?.setOnClickListener {
            if (isExpanded) collapsePanel(fabMain, panel)
            else            expandPanel(fabMain, panel)
        }

        // ── Preview ──
        view.findViewById<ImageButton>(R.id.btn_float_preview)?.setOnClickListener {
            openPreview(activeSlot)
        }

        // ── Rotate ──
        view.findViewById<ImageButton>(R.id.btn_float_rotate)?.setOnClickListener {
            currentRotation = (currentRotation + 90) % 360
            sendBroadcast(Intent(ACTION_ROTATE).putExtra("rotation", currentRotation))
            view.findViewById<TextView>(R.id.tv_rotate_label)?.text = "${currentRotation}°"
            animateBounce(it)
        }

        // ── Mirror ──
        view.findViewById<ImageButton>(R.id.btn_float_mirror)?.let { btn ->
            btn.setOnClickListener {
                isMirrored = !isMirrored
                btn.alpha = if (isMirrored) 1f else 0.45f
                view.findViewById<TextView>(R.id.tv_mirror_label)?.apply {
                    text = if (isMirrored) "عكس ✓" else "عكس"
                    setTextColor(if (isMirrored) 0xFF4F8EF7.toInt() else 0xFF888888.toInt())
                }
                sendBroadcast(Intent(ACTION_MIRROR).putExtra("mirror", isMirrored))
                animateBounce(it)
            }
        }

        // ── Stop ──
        view.findViewById<ImageButton>(R.id.btn_float_stop)?.setOnClickListener {
            sendBroadcast(Intent(ACTION_STOP_VCAM))
            removeFloatWindow()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        // Window params
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 200 }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm; layoutParams = lp; floatView = view

        // Drag on FAB bubble
        fabMain?.setOnTouchListener(DragTouchListener(view, wm, lp) {
            // onClick after drag — toggle
            if (isExpanded) collapsePanel(fabMain, panel)
            else            expandPanel(fabMain, panel)
        })

        wm.addView(view, lp)

        // Pulse animation on the FAB to attract attention
        pulseFab(fabMain)
    }

    // ── Expand / Collapse animations ──────────────────────────────────

    private fun expandPanel(fab: ImageView?, panel: View?) {
        panel ?: return; fab ?: return
        isExpanded = true
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.scaleX = 0.8f
        panel.scaleY = 0.8f
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
        // Rotate FAB icon 45° to indicate "open"
        fab.animate().rotation(45f).setDuration(200).start()
    }

    private fun collapsePanel(fab: ImageView?, panel: View?) {
        panel ?: return; fab ?: return
        isExpanded = false
        panel.animate()
            .alpha(0f).scaleX(0.8f).scaleY(0.8f)
            .setDuration(160)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    panel.visibility = View.GONE
                    panel.animate().setListener(null)
                }
            }).start()
        fab.animate().rotation(0f).setDuration(180).start()
    }

    private fun pulseFab(fab: ImageView?) {
        fab ?: return
        val pulse = ObjectAnimator.ofFloat(fab, "scaleX", 1f, 1.15f, 1f).apply {
            duration = 700; repeatCount = 2
        }
        val pulseY = ObjectAnimator.ofFloat(fab, "scaleY", 1f, 1.15f, 1f).apply {
            duration = 700; repeatCount = 2
        }
        pulse.start(); pulseY.start()
    }

    private fun animateBounce(v: View) {
        v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80)
            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
            .start()
    }

    // ── Slot switching ────────────────────────────────────────────────

    private fun switchToSlot(view: View, slot: Int) {
        if (!MediaSlotManager.isSlotSet(this, slot)) {
            Toast.makeText(this, getString(R.string.slot_not_set, slot), Toast.LENGTH_SHORT).show()
            return
        }
        activeSlot = slot
        updateSlotButtonVisuals(view, slot)
        updateTypeLabel(view, slot)
        sendBroadcast(Intent(ACTION_SWITCH_SLOT).putExtra(EXTRA_SLOT, slot))
        Toast.makeText(this, getString(R.string.slot_switched, slot), Toast.LENGTH_SHORT).show()
    }

    private fun updateSlotButtonVisuals(view: View, active: Int) {
        slotBtnIds.forEachIndexed { idx, btnId ->
            val slot = idx + 1
            val tv = view.findViewById<TextView>(btnId) ?: return@forEachIndexed
            val isActive = slot == active
            tv.setTextColor(if (isActive) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
            tv.setBackgroundResource(
                when {
                    isActive && slot == 5 -> R.drawable.bg_slot_btn_video_active
                    isActive              -> R.drawable.bg_slot_btn_active
                    slot == 5            -> R.drawable.bg_slot_btn_video
                    else                 -> R.drawable.bg_slot_btn_inactive
                }
            )
        }
    }

    private fun updateTypeLabel(view: View, slot: Int) {
        view.findViewById<TextView>(R.id.tv_float_type)?.text =
            if (slot == 5) "🎬" else "📷$slot"
    }

    // ── Preview ───────────────────────────────────────────────────────

    private fun openPreview(slot: Int) {
        val intent = Intent(this, PreviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(PreviewActivity.EXTRA_SLOT, slot)
        }
        startActivity(intent)
    }

    private fun removeFloatWindow() {
        try { floatView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        floatView = null
    }

    // ── Drag listener ─────────────────────────────────────────────────

    private inner class DragTouchListener(
        private val view: View,
        private val wm: WindowManager,
        private val lp: WindowManager.LayoutParams,
        private val onClick: () -> Unit
    ) : View.OnTouchListener {
        private var initX = 0; private var initY = 0
        private var touchX = 0f; private var touchY = 0f
        private var hasMoved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = lp.x; initY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    hasMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        hasMoved = true
                        lp.x = initX + dx
                        lp.y = initY + dy
                        wm.updateViewLayout(view, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) onClick()
                }
            }
            return true
        }
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "VCam Float Window", NotificationManager.IMPORTANCE_MIN).let {
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(it)
            }
        }
    }

    private fun buildNotification(targetName: String, isVideo: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("VCam Float — Active")
            .setContentText("Injecting ${if (isVideo) "video" else "image"} → $targetName")
            .setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_MIN).setOngoing(true).build()
    }
}
