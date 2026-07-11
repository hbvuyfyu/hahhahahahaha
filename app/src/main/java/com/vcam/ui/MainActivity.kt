package com.vcam.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.vcam.R
import com.vcam.databinding.ActivityMainBinding
import com.vcam.service.ConnectServer
import com.vcam.service.VCamService
import com.vcam.utils.LicenseChecker
import com.vcam.utils.MediaSlotManager
import com.vcam.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var pendingSlot = 1

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val slot    = pendingSlot
        val isVideo = slot >= 5
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MediaSlotManager.setSlot(this@MainActivity, slot, uri, isVideo)
            }
            refreshSlotUI(slot)
            binding.btnStartStop.isEnabled = MediaSlotManager.isSlotSet(this@MainActivity, 1)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        lifecycleScope.launch {
            viewModel.initRoot()
            if (!allGranted) showSnack(getString(R.string.permissions_required))
        }
    }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupObservers()
        setupSlotPickers()
        setupDeleteButtons()
        setupRotateButtons()
        setupStartStop()
        setupLinkSwitch()
        requestPermissions()
        (1..8).forEach { refreshSlotUI(it) }
        binding.btnStartStop.isEnabled = MediaSlotManager.isSlotSet(this, 1)
    }

    override fun onResume() {
        super.onResume()
        val savedCode = LicenseChecker.getSavedCode(this)
        if (savedCode == null) { logoutToCodeScreen(); return }
        lifecycleScope.launch {
            val result = LicenseChecker.verifyCode(savedCode)
            if (result == LicenseChecker.VerifyResult.INVALID ||
                result == LicenseChecker.VerifyResult.SERVER_EMPTY) {
                LicenseChecker.clearCode(this@MainActivity)
                logoutToCodeScreen()
            }
        }
        refreshLinkUI()
    }

    private fun logoutToCodeScreen() {
        startActivity(Intent(this, CodeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ── Observers ─────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.isServiceRunning.observe(this) { running ->
            binding.btnStartStop.text = if (running) getString(R.string.stop_vcam)
                                         else getString(R.string.start_vcam)
            val color = if (running) R.color.color_stop else R.color.color_start
            binding.btnStartStop.backgroundTintList =
                androidx.core.content.res.ResourcesCompat.getColorStateList(resources, color, theme)
            binding.btnStartStop.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        }

        viewModel.rootStatus.observe(this) { ok ->
            binding.tvRootStatus.text = if (ok) getString(R.string.root_granted)
                                         else getString(R.string.root_denied)
            binding.tvRootStatus.setTextColor(
                ContextCompat.getColor(this, if (ok) R.color.color_root_ok else R.color.color_root_fail)
            )
        }

        viewModel.errorMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) { showSnack(msg); viewModel.clearError() }
        }
    }

    // ── Slot pickers (1-4 images, 5-8 videos) ─────────────────────────────

    private fun setupSlotPickers() {
        // Image slots 1-4
        listOf(
            R.id.btn_pick_slot_1 to 1,
            R.id.btn_pick_slot_2 to 2,
            R.id.btn_pick_slot_3 to 3,
            R.id.btn_pick_slot_4 to 4,
        ).forEach { (btnId, slot) ->
            binding.root.findViewById<View>(btnId)?.setOnClickListener {
                pendingSlot = slot
                pickMedia.launch("image/*")
            }
        }
        // Video slots 5-8
        listOf(
            R.id.btn_pick_slot_5 to 5,
            R.id.btn_pick_slot_6 to 6,
            R.id.btn_pick_slot_7 to 7,
            R.id.btn_pick_slot_8 to 8,
        ).forEach { (btnId, slot) ->
            binding.root.findViewById<View>(btnId)?.setOnClickListener {
                pendingSlot = slot
                pickMedia.launch("video/*")
            }
        }
    }

    // ── Delete buttons ─────────────────────────────────────────────────────

    private fun setupDeleteButtons() {
        listOf(
            R.id.btn_delete_slot_1 to 1,
            R.id.btn_delete_slot_2 to 2,
            R.id.btn_delete_slot_3 to 3,
            R.id.btn_delete_slot_4 to 4,
            R.id.btn_delete_slot_5 to 5,
            R.id.btn_delete_slot_6 to 6,
            R.id.btn_delete_slot_7 to 7,
            R.id.btn_delete_slot_8 to 8,
        ).forEach { (btnId, slot) ->
            binding.root.findViewById<ImageButton>(btnId)?.setOnClickListener {
                MediaSlotManager.clearSlot(this, slot)
                refreshSlotUI(slot)
                if (slot == 1) binding.btnStartStop.isEnabled = false
                showSnack("تم حذف الحقل $slot")
            }
        }
    }

    // ── Rotate buttons (images only: 1-4) ──────────────────────────────────

    private fun setupRotateButtons() {
        listOf(
            R.id.btn_rotate_slot_1 to 1,
            R.id.btn_rotate_slot_2 to 2,
            R.id.btn_rotate_slot_3 to 3,
            R.id.btn_rotate_slot_4 to 4,
        ).forEach { (btnId, slot) ->
            val btn = binding.root.findViewById<ImageButton>(btnId) ?: return@forEach
            btn.setOnClickListener {
                if (!MediaSlotManager.isSlotSet(this, slot)) return@setOnClickListener
                if (btn.tag == "rotating") return@setOnClickListener
                btn.tag = "rotating"

                // Animate the slot ImageView rotation (not the button)
                val ivId = when (slot) {
                    1 -> R.id.iv_slot_1; 2 -> R.id.iv_slot_2
                    3 -> R.id.iv_slot_3; 4 -> R.id.iv_slot_4
                    else -> null
                }
                val iv = ivId?.let { binding.root.findViewById<ImageView>(it) }
                val currentDeg = MediaSlotManager.getSlotRotation(this, slot).toFloat()
                val targetDeg = currentDeg + 90f

                if (iv != null) {
                    iv.animate()
                        .rotation(targetDeg)
                        .setDuration(250)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction {
                            iv.rotation = 0f
                            btn.tag = null
                        }
                        .start()
                } else {
                    btn.tag = null
                }

                // Persist rotation (fast, no disk I/O for the image)
                lifecycleScope.launch {
                    val newDeg = withContext(Dispatchers.IO) {
                        MediaSlotManager.rotateSlot(this@MainActivity, slot)
                    }
                    showSnack("الحقل $slot — تدوير ${newDeg}°")
                }
            }
        }
    }

    // ── Link switch ───────────────────────────────────────────────────────

    private fun setupLinkSwitch() {
        val sw = binding.root.findViewById<SwitchMaterial>(R.id.switch_link) ?: return
        sw.isChecked = ConnectServer.isEnabled(this)
        sw.setOnCheckedChangeListener { _, isChecked ->
            ConnectServer.setEnabled(this, isChecked)
            val action = if (isChecked) VCamService.ACTION_ENABLE_LINK
                         else           VCamService.ACTION_DISABLE_LINK
            try {
                startService(Intent(this, VCamService::class.java).apply { this.action = action })
            } catch (_: Exception) {}
            refreshLinkUI()
            showSnack(
                if (isChecked) getString(R.string.link_enabled_msg)
                else           getString(R.string.link_disabled_msg)
            )
        }
    }

    private fun refreshLinkUI() {
        val enabled    = ConnectServer.isEnabled(this)
        val sw         = binding.root.findViewById<SwitchMaterial>(R.id.switch_link) ?: return
        val infoLayout = binding.root.findViewById<View>(R.id.layout_link_info) ?: return
        val tvPort     = binding.root.findViewById<TextView>(R.id.tv_link_port)
        val tvToken    = binding.root.findViewById<TextView>(R.id.tv_link_token)

        sw.isChecked = enabled
        infoLayout.visibility = if (enabled) View.VISIBLE else View.GONE

        if (enabled) {
            tvPort?.text  = getString(R.string.link_port, ConnectServer.PORT)
            tvToken?.text = getString(R.string.link_token, ConnectServer.getToken(this))
        }
    }

    // ── Refresh slot thumbnail ────────────────────────────────────────────

    private fun refreshSlotUI(slot: Int) {
        val ivId = when (slot) {
            1 -> R.id.iv_slot_1; 2 -> R.id.iv_slot_2; 3 -> R.id.iv_slot_3
            4 -> R.id.iv_slot_4; 5 -> R.id.iv_slot_5; 6 -> R.id.iv_slot_6
            7 -> R.id.iv_slot_7; 8 -> R.id.iv_slot_8; else -> return
        }
        val placeholderId = when (slot) {
            1 -> R.id.placeholder_slot_1; 2 -> R.id.placeholder_slot_2
            3 -> R.id.placeholder_slot_3; 4 -> R.id.placeholder_slot_4
            5 -> R.id.placeholder_slot_5; 6 -> R.id.placeholder_slot_6
            7 -> R.id.placeholder_slot_7; 8 -> R.id.placeholder_slot_8
            else -> return
        }
        val deleteBtnId = when (slot) {
            1 -> R.id.btn_delete_slot_1; 2 -> R.id.btn_delete_slot_2
            3 -> R.id.btn_delete_slot_3; 4 -> R.id.btn_delete_slot_4
            5 -> R.id.btn_delete_slot_5; 6 -> R.id.btn_delete_slot_6
            7 -> R.id.btn_delete_slot_7; 8 -> R.id.btn_delete_slot_8
            else -> return
        }
        val rotateBtnId = when (slot) {
            1 -> R.id.btn_rotate_slot_1; 2 -> R.id.btn_rotate_slot_2
            3 -> R.id.btn_rotate_slot_3; 4 -> R.id.btn_rotate_slot_4
            else -> null
        }

        val iv          = binding.root.findViewById<ImageView>(ivId)          ?: return
        val placeholder = binding.root.findViewById<LinearLayout>(placeholderId) ?: return
        val delBtn      = binding.root.findViewById<ImageButton>(deleteBtnId)  ?: return
        val rotBtn      = rotateBtnId?.let { binding.root.findViewById<ImageButton>(it) }

        if (MediaSlotManager.isSlotSet(this, slot)) {
            placeholder.visibility = View.GONE
            iv.visibility    = View.VISIBLE
            delBtn.visibility = View.VISIBLE
            rotBtn?.visibility = if (slot <= 4) View.VISIBLE else View.GONE

            lifecycleScope.launch {
                val bmp: Bitmap? = withContext(Dispatchers.IO) {
                    MediaSlotManager.getThumbnail(this@MainActivity, slot)
                }
                if (bmp != null) iv.setImageBitmap(bmp)
            }
        } else {
            placeholder.visibility = View.VISIBLE
            iv.visibility    = View.GONE
            delBtn.visibility = View.GONE
            rotBtn?.visibility = View.GONE
        }
    }

    // ── Start / Stop ──────────────────────────────────────────────────────

    private fun setupStartStop() {
        binding.btnStartStop.setOnClickListener {
            if (viewModel.isServiceRunning.value == true) stopVCamService()
            else handleStart()
        }
    }

    private fun handleStart() {
        if (!MediaSlotManager.isSlotSet(this, 1)) {
            showSnack(getString(R.string.select_media_first)); return
        }
        checkOverlayThenStart()
    }

    private fun checkOverlayThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_msg)
                .setPositiveButton(R.string.grant) { _, _ ->
                    overlayPermLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    doStartService()
                }
                .setNegativeButton(R.string.skip) { _, _ -> doStartService() }
                .show()
        } else {
            doStartService()
        }
    }

    private fun doStartService() {
        val slot1Path = MediaSlotManager.getSlotPath(this, 1) ?: return
        val intent = Intent(this, VCamService::class.java).apply {
            action = VCamService.ACTION_START
            putExtra(VCamService.EXTRA_MEDIA_PATH, slot1Path)
            putExtra(VCamService.EXTRA_IS_VIDEO, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        viewModel.setServiceRunning(true)
        showSnack(getString(R.string.injection_active))
    }

    private fun stopVCamService() {
        startService(Intent(this, VCamService::class.java).apply { action = VCamService.ACTION_STOP })
        viewModel.setServiceRunning(false)
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private fun requestPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(android.Manifest.permission.CAMERA)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
        else lifecycleScope.launch { viewModel.initRoot() }
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
