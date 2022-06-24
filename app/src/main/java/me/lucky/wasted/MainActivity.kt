package me.lucky.wasted

import android.app.job.JobScheduler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.*
import kotlin.concurrent.timerTask

import me.lucky.wasted.databinding.ActivityMainBinding

open class MainActivity : AppCompatActivity() {
    companion object {
        private const val CLIPBOARD_CLEAR_DELAY = 30_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences
    private lateinit var admin: DeviceAdminManager
    private val shortcut by lazy { ShortcutManager(this) }
    private val job by lazy { WipeJobManager(this) }
    private var clipboardManager: ClipboardManager? = null
    private var clipboardClearTask: Timer? = null

    private val registerForDeviceAdmin =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            when (it.resultCode) {
                RESULT_OK -> setOn()
                else -> binding.toggle.isChecked = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()
        setup()
    }

    override fun onStart() {
        super.onStart()
        update()
    }

    private fun update() {
        if (prefs.isEnabled && !admin.isActive())
            Snackbar.make(
                binding.toggle,
                R.string.service_unavailable_popup,
                Snackbar.LENGTH_SHORT,
            ).show()
    }

    private fun init() {
        prefs = Preferences(this)
        admin = DeviceAdminManager(this)
        clipboardManager = getSystemService(ClipboardManager::class.java)
        NotificationManager(this).createNotificationChannels()
        if (prefs.authenticationCode.isEmpty()) prefs.authenticationCode = makeAuthenticationCode()
        updateCodeColorState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) hideEmbeddedSim()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN))
                hideSecureLockScreenRequired()
        binding.apply {
            authenticationCode.text = prefs.authenticationCode
            wipeData.isChecked = prefs.isWipeData
            wipeEmbeddedSim.isChecked = prefs.isWipeEmbeddedSim
            wipeEmbeddedSim.isEnabled = wipeData.isChecked
            wipeOnInactivitySwitch.isChecked = prefs.isWipeOnInactivity
            toggle.isChecked = prefs.isEnabled
        }
    }

    private fun hideEmbeddedSim() {
        binding.wipeSpace.visibility = View.GONE
        binding.wipeEmbeddedSim.visibility = View.GONE
    }

    private fun hideSecureLockScreenRequired() {
        binding.apply {
            divider.visibility = View.GONE
            wipeOnInactivitySwitch.visibility = View.GONE
            wipeOnInactivityDescription.visibility = View.GONE
        }
    }

    private fun setup() {
        binding.apply {
            authenticationCode.setOnClickListener {
                showTriggersSettings()
            }
            authenticationCode.setOnLongClickListener {
                clipboardManager
                    ?.setPrimaryClip(ClipData.newPlainText("", prefs.authenticationCode))
                if (clipboardManager != null) {
                    scheduleClipboardClear()
                    Snackbar.make(
                        authenticationCode,
                        R.string.copied_popup,
                        Snackbar.LENGTH_SHORT,
                    ).show()
                }
                true
            }
            wipeData.setOnCheckedChangeListener { _, isChecked ->
                prefs.isWipeData = isChecked
                wipeEmbeddedSim.isEnabled = isChecked
            }
            wipeEmbeddedSim.setOnCheckedChangeListener { _, isChecked ->
                prefs.isWipeEmbeddedSim = isChecked
            }
            wipeOnInactivitySwitch.setOnCheckedChangeListener { _, isChecked ->
                setWipeOnInactivityState(prefs.isEnabled && isChecked)
                prefs.isWipeOnInactivity = isChecked
            }
            wipeOnInactivitySwitch.setOnLongClickListener {
                showWipeOnInactivitySettings()
                true
            }
            toggle.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) requestAdmin() else setOff()
            }
        }
    }

    private fun scheduleClipboardClear() {
        clipboardClearTask?.cancel()
        clipboardClearTask = Timer()
        clipboardClearTask?.schedule(timerTask {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboardManager?.clearPrimaryClip()
            } else {
                clipboardManager?.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, CLIPBOARD_CLEAR_DELAY)
    }

    private fun showTriggersSettings() {
        var triggers = prefs.triggers
        val values = Trigger.values().toMutableList()
        val strings = resources.getStringArray(R.array.triggers).toMutableList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            strings.removeAt(values.indexOf(Trigger.TILE))
            values.remove(Trigger.TILE)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.triggers)
            .setMultiChoiceItems(
                strings.toTypedArray(),
                values.map { triggers.and(it.value) != 0 }.toBooleanArray(),
            ) { _, index, isChecked ->
                val flag = values[index]
                triggers = when (isChecked) {
                    true -> triggers.or(flag.value)
                    false -> triggers.and(flag.value.inv())
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.triggers = triggers
                setTriggersState(prefs.isEnabled)
            }
            .show()
    }

    private fun showWipeOnInactivitySettings() {
        val items = resources.getStringArray(R.array.wipe_on_inactivity_days)
        var days = prefs.wipeOnInactivityCount / 24 / 60
        var checked = items.indexOf(days.toString())
        if (checked == -1) checked = items
            .indexOf((Preferences.DEFAULT_WIPE_ON_INACTIVITY_COUNT / 24 / 60).toString())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wipe_on_inactivity_days)
            .setSingleChoiceItems(items, checked) { _, which ->
                days = items[which].toInt()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.wipeOnInactivityCount = days * 24 * 60
                if (prefs.isEnabled && prefs.isWipeOnInactivity) {
                    if (job.schedule() == JobScheduler.RESULT_FAILURE)
                        showWipeJobScheduleFailedPopup()
                }
            }
            .show()
    }

    private fun updateCodeColorState() {
        binding.authenticationCode.setBackgroundColor(getColor(
            if (prefs.triggers != 0) R.color.code_on else R.color.code_off
        ))
    }

    private fun setOn() {
        prefs.isEnabled = true
        setWipeOnInactivityState(prefs.isWipeOnInactivity)
        setTriggersState(true)
    }

    private fun setTriggersState(value: Boolean) {
        if (value) {
            val triggers = prefs.triggers
            setPanicKitState(triggers.and(Trigger.PANIC_KIT.value) != 0)
            setTileState(triggers.and(Trigger.TILE.value) != 0)
            shortcut.setState(triggers.and(Trigger.SHORTCUT.value) != 0)
            setTriggerReceiverState(triggers.and(Trigger.BROADCAST.value) != 0)
            setNotificationListenerState(triggers.and(Trigger.NOTIFICATION.value) != 0)
        } else {
            setPanicKitState(false)
            setTileState(false)
            shortcut.setState(false)
            setTriggerReceiverState(false)
            setNotificationListenerState(false)
        }
        updateCodeColorState()
    }

    private fun showWipeJobScheduleFailedPopup() {
        Snackbar.make(
            binding.toggle,
            R.string.wipe_job_schedule_failed_popup,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private fun setOff() {
        prefs.isEnabled = false
        setWipeOnInactivityState(false)
        setTriggersState(false)
        try {
            admin.remove()
        } catch (exc: SecurityException) {}
    }

    private fun requestAdmin() = registerForDeviceAdmin.launch(admin.makeRequestIntent())
    private fun makeAuthenticationCode() = UUID.randomUUID().toString()
    private fun setTriggerReceiverState(value: Boolean) =
        setComponentState(TriggerReceiver::class.java, value)
    private fun setRestartReceiverState(value: Boolean) =
        setComponentState(RestartReceiver::class.java, value)
    private fun setTileState(value: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            setComponentState(TileService::class.java, value)
    }
    private fun setNotificationListenerState(value: Boolean) =
        setComponentState(NotificationListenerService::class.java, value)

    private fun setPanicKitState(value: Boolean) {
        setComponentState(PanicConnectionActivity::class.java, value)
        setComponentState(PanicResponderActivity::class.java, value)
    }

    private fun setWipeOnInactivityState(value: Boolean) {
        job.setState(value)
        setForegroundState(value)
    }

    private fun setComponentState(cls: Class<*>, value: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, cls),
            if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun setForegroundServiceState(value: Boolean) {
        Intent(this.applicationContext, ForegroundService::class.java).also {
            if (value) ContextCompat.startForegroundService(this.applicationContext, it)
            else stopService(it)
        }
    }

    private fun setForegroundState(value: Boolean) {
        setForegroundServiceState(value)
        setRestartReceiverState(value)
    }
}
