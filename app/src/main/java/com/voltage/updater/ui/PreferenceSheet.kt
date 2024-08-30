package com.voltage.updater.ui

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemProperties
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.voltage.updater.R
import com.voltage.updater.UpdatesActivity
import com.voltage.updater.UpdateImporter
import com.voltage.updater.UpdatesCheckReceiver
import com.voltage.updater.UpdateView
import com.voltage.updater.controller.UpdaterController
import com.voltage.updater.controller.UpdaterService
import com.voltage.updater.misc.Constants
import com.voltage.updater.misc.Utils
import com.voltage.updater.model.Update
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

@SuppressLint("UseSwitchCompatOrMaterialCode")
class PreferenceSheet : BottomSheetDialogFragment(), UpdateImporter.Callbacks {

    private var prefs: SharedPreferences? = null

    private var mUpdaterService: UpdaterService? = null

    private var mDownloadIds: MutableList<String>? = null

    private lateinit var mUpdateImporter: UpdateImporter

    private lateinit var updatesActivity: UpdatesActivity

    private lateinit var updateView: UpdateView

    private lateinit var preferencesAbPerfMode: Switch
    private lateinit var preferencesAutoDeleteUpdates: Switch
    private lateinit var preferencesMeteredNetworkWarning: Switch
    private lateinit var preferencesUpdateRecovery: Switch
    private lateinit var preferencesAutoUpdatesCheckInterval: Spinner
    private lateinit var buttonLocalUpdate: Button

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.preferences_dialog, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updatesActivity = activity as UpdatesActivity
        with(view) {
            preferencesAbPerfMode = requireViewById(R.id.preferences_ab_perf_mode)
            preferencesAutoDeleteUpdates = requireViewById(R.id.preferences_auto_delete_updates)
            preferencesMeteredNetworkWarning = requireViewById(R.id.preferences_metered_network_warning)
            preferencesUpdateRecovery = requireViewById(R.id.preferences_update_recovery)
            preferencesAutoUpdatesCheckInterval = requireViewById(R.id.preferences_auto_updates_check_interval)
            buttonLocalUpdate = requireViewById(R.id.button_local_update)
        }

        if (!Utils.isABDevice() || Utils.isABPerfModeForceEnabled(requireContext())) {
            preferencesAbPerfMode.visibility = View.GONE
        }

        if (Utils.isDeleteUpdatesForceEnabled(requireContext())) {
            preferencesAutoDeleteUpdates.visibility = View.GONE
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        preferencesAutoUpdatesCheckInterval.setSelection(Utils.getUpdateCheckSetting(requireContext()))
        preferencesAutoDeleteUpdates.isChecked =
            prefs!!.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false)
        preferencesMeteredNetworkWarning.isChecked =
            prefs!!.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs!!.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true))
        preferencesAbPerfMode.isChecked =
            prefs!!.getBoolean(Constants.PREF_AB_PERF_MODE, false)

        if (resources.getBoolean(R.bool.config_hideRecoveryUpdate)) {
            // Hide the update feature if explicitly requested.
            // Might be the case of A-only devices using prebuilt vendor images.
            preferencesUpdateRecovery.visibility = View.GONE
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            preferencesUpdateRecovery.isChecked =
                SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false)
        } else {
            // There is no recovery updater script in the device, so the feature is considered
            // forcefully enabled, just to avoid users to be confused and complain that
            // recovery gets overwritten. That's the case of A/B and recovery-in-boot devices.
            preferencesUpdateRecovery.isChecked = true
            preferencesUpdateRecovery.setOnTouchListener(object : View.OnTouchListener {
                private var forcedUpdateToast: Toast? = null
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    if (forcedUpdateToast != null) {
                        forcedUpdateToast!!.cancel()
                    }
                    forcedUpdateToast = Toast.makeText(
                        requireContext(),
                        getString(R.string.toast_forced_update_recovery), Toast.LENGTH_SHORT
                    )
                    forcedUpdateToast?.show()
                    return true
                }
            })
        }

        mUpdateImporter = UpdateImporter(requireActivity() as UpdatesActivity, this)

        buttonLocalUpdate.setOnClickListener {
            mUpdateImporter.openImportPicker()
        }
    }

    override fun onPause() {
        updatesActivity.onPause()
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        updatesActivity.onActivityResult(requestCode, resultCode, data)
    }

    override fun onImportStarted() {
        updatesActivity.onImportStarted()
    }

    override fun onImportCompleted(update: Update?) {
        updatesActivity.onImportCompleted(update)
    }

    override fun onDismiss(dialog: DialogInterface) {
        prefs!!.edit()
            .putInt(
                Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                preferencesAutoUpdatesCheckInterval.selectedItemPosition
            )
            .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, preferencesAutoDeleteUpdates.isChecked)
            .putBoolean(Constants.PREF_METERED_NETWORK_WARNING, preferencesMeteredNetworkWarning.isChecked)
            .putBoolean(Constants.PREF_AB_PERF_MODE, preferencesAbPerfMode.isChecked)
            .apply()

        if (Utils.isUpdateCheckEnabled(requireContext())) {
            UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(requireContext())
        } else {
            UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(requireContext())
            UpdatesCheckReceiver.cancelUpdatesCheck(requireContext())
        }

        if (Utils.isABDevice()) {
            val enableABPerfMode: Boolean = preferencesAbPerfMode.isChecked
            mUpdaterService?.updaterController?.setPerformanceMode(enableABPerfMode)
        }
        if (Utils.isRecoveryUpdateExecPresent()) {
            val enableRecoveryUpdate: Boolean = preferencesUpdateRecovery.isChecked
            SystemProperties.set(
                Constants.UPDATE_RECOVERY_PROPERTY,
                enableRecoveryUpdate.toString()
            )
        }
        super.onDismiss(dialog)
    }

    fun setupPreferenceSheet(updaterService: UpdaterService, updateView: UpdateView): PreferenceSheet {
        this.mUpdaterService = updaterService
        this.updateView = updateView
        return this
    }

    fun setUpdateImporter(updateImporter: UpdateImporter): PreferenceSheet {
        this.mUpdateImporter = updateImporter
        return this
    }

    fun addItem(downloadId: String) {
        if (mDownloadIds == null) {
            mDownloadIds = ArrayList()
        }
        mDownloadIds!!.add(0, downloadId)
        updateView.addItem(downloadId)
    }
}

