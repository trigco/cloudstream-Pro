package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.DialogIconPackBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe

/**
 * Launcher icon pack (Phase 8.11-F).
 *
 * The app has one real launcher component, [LAUNCHER_ACTIVITY], plus one
 * disabled-by-default <activity-alias> per alternative icon in the
 * manifest. Switching means enabling exactly one of those components and
 * disabling the rest, via [PackageManager.setComponentEnabledSetting]
 * with DONT_KILL_APP so the running app is not torn down mid-settings.
 *
 * Note: even with DONT_KILL_APP, some launchers/OEMs close or redraw the
 * app when the launcher component set changes — that is platform
 * behaviour, not a crash, and we deliberately do not restart the app
 * programmatically.
 *
 * The choice is persisted with the app's DataStore getKey/setKey store
 * so the picker can show the current selection (and so an app update
 * does not lose track of which component is supposed to be active).
 */
object AppIconHelper {
    const val APP_ICON_KEY = "app_icon_variant"

    private const val LAUNCHER_ACTIVITY =
        "com.lagradost.cloudstream3.ui.account.AccountSelectActivity"

    enum class AppIcon(
        val id: String,
        @DrawableRes val iconRes: Int,
        @StringRes val nameRes: Int,
        val componentClass: String
    ) {
        DEFAULT(
            "default",
            R.mipmap.ic_launcher,
            R.string.app_icon_default,
            LAUNCHER_ACTIVITY
        ),
        MIDNIGHT(
            "midnight",
            R.mipmap.ic_launcher_midnight,
            R.string.app_icon_midnight,
            "com.lagradost.cloudstream3.icon.IconMidnight"
        ),
        INVERSE(
            "inverse",
            R.mipmap.ic_launcher_inverse,
            R.string.app_icon_inverse,
            "com.lagradost.cloudstream3.icon.IconInverse"
        ),
        CRIMSON(
            "crimson",
            R.mipmap.ic_launcher_crimson,
            R.string.app_icon_crimson,
            "com.lagradost.cloudstream3.icon.IconCrimson"
        ),
        CARBON(
            "carbon",
            R.mipmap.ic_launcher_carbon,
            R.string.app_icon_carbon,
            "com.lagradost.cloudstream3.icon.IconCarbon"
        )
    }

    fun Context.getSelectedAppIcon(): AppIcon {
        val stored = try {
            getKey<String>(APP_ICON_KEY)
        } catch (e: Exception) {
            logError(e)
            null
        }
        return AppIcon.values().firstOrNull { it.id == stored } ?: AppIcon.DEFAULT
    }

    fun Context.applyAppIcon(icon: AppIcon) {
        try {
            setKey(APP_ICON_KEY, icon.id)
        } catch (e: Exception) {
            logError(e)
        }
        try {
            val pm = packageManager
            // Enable the chosen component first, so the app never sits in
            // a state with zero enabled launcher entries.
            pm.setComponentEnabledSetting(
                ComponentName(packageName, icon.componentClass),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            AppIcon.values().filter { it != icon }.forEach { other ->
                pm.setComponentEnabledSetting(
                    ComponentName(packageName, other.componentClass),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun showAppIconPicker(activity: Activity, onPicked: (AppIcon) -> Unit = {}) {
        val binding = DialogIconPackBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(binding.root)

        // Phase 8.89: standard sheet close button.
        binding.closeBtt.setOnClickListener {
            dialog.dismissSafe()
        }

        val cells: List<Pair<LinearLayout, ImageView>> = listOf(
            binding.iconCellDefault to binding.iconPreviewDefault,
            binding.iconCellMidnight to binding.iconPreviewMidnight,
            binding.iconCellInverse to binding.iconPreviewInverse,
            binding.iconCellCrimson to binding.iconPreviewCrimson,
            binding.iconCellCarbon to binding.iconPreviewCarbon
        )
        val icons = AppIcon.values()
        val current = activity.getSelectedAppIcon()

        cells.zip(icons).forEach { (cellAndPreview, icon) ->
            val (cell, preview) = cellAndPreview
            preview.background = if (icon == current) {
                ContextCompat.getDrawable(activity, R.drawable.bg_icon_pack_selected)
            } else {
                null
            }
            cell.setOnClickListener {
                activity.applyAppIcon(icon)
                CommonActivity.showToast(activity, R.string.app_icon_changed)
                dialog.dismissSafe()
                onPicked(icon)
            }
        }
        dialog.show()
    }
}
