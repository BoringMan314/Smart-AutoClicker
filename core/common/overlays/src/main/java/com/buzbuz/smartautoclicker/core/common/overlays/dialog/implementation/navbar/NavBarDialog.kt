/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.common.overlays.dialog.implementation.navbar

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager

import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle

import com.buzbuz.smartautoclicker.core.common.overlays.databinding.DialogBaseNavBarBinding
import com.buzbuz.smartautoclicker.core.common.overlays.databinding.ViewBottomNavBarBinding
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.OverlayDialog
import com.buzbuz.smartautoclicker.core.ui.bindings.dialogs.DialogNavigationButton
import com.buzbuz.smartautoclicker.core.ui.databinding.IncludeDialogNavigationTopBarBinding
import com.buzbuz.smartautoclicker.core.ui.databinding.IncludeFloatingActionButtonsBinding

import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.navigation.NavigationBarView

import kotlin.math.max

abstract class NavBarDialog(@StyleRes theme: Int) : OverlayDialog(theme) {

    /** Map of navigation bar item id to their content view. */
    private val contentMap: MutableMap<Int, NavBarDialogContent> = mutableMapOf()

    private lateinit var baseViewBinding: DialogBaseNavBarBinding
    protected lateinit var navBarView: NavigationBarView
    lateinit var floatingActionButtons: IncludeFloatingActionButtonsBinding
    lateinit var topBarBinding: IncludeDialogNavigationTopBarBinding

    private var lastImeLiftPx: Int = 0
    private var imeAnimationRunning: Boolean = false
    private var imeLiftAnimator: ValueAnimator? = null

    private val imeLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        // Accessibility overlays often skip inset animations; animate as fallback to avoid a hard jump.
        if (!imeAnimationRunning) updateImeLiftAnimated()
    }

    abstract fun inflateMenu(navBarView: NavigationBarView)

    abstract fun onCreateContent(navItemId: Int): NavBarDialogContent

    abstract fun onDialogButtonPressed(buttonType: DialogNavigationButton)

    open fun onContentViewChanged(navItemId: Int) = Unit

    /**
     * NavBarDialog cannot use [WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE] like trigger OverlayDialogs:
     * the portrait bottom bar sits on the CoordinatorLayout and resize parks the sheet mid-screen.
     * Keep window size and lift sheet + bottom bar with the IME animation (same feel as adjustResize).
     */
    override fun applySoftInputMode(window: Window) {
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        )
    }
    override fun onCreateView(): ViewGroup {
        baseViewBinding = DialogBaseNavBarBinding.inflate(LayoutInflater.from(context)).apply {
            layoutTopBar.apply {
                buttonSave.setDebouncedOnClickListener { handleButtonClick(DialogNavigationButton.SAVE) }
                buttonDismiss.setDebouncedOnClickListener { handleButtonClick(DialogNavigationButton.DISMISS) }
                buttonDelete.setDebouncedOnClickListener { handleButtonClick(DialogNavigationButton.DELETE) }
            }
        }
        topBarBinding = baseViewBinding.layoutTopBar

        // In portrait, we need to inject the navigation view as a child of the dialog's CoordinatorLayout in order to
        // correctly handle the dialog scrolling behaviour without moving the navigation view from the bottom.
        // This issue does not occurs in landscape mode, as the NavigationBar is replaced by a NavigationRail, which
        // is sticky to the dialog start.
        if (displayConfigManager.displayConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            navBarView = ViewBottomNavBarBinding.inflate(LayoutInflater.from(context)).root
            floatingActionButtons = IncludeFloatingActionButtonsBinding.inflate(LayoutInflater.from(context))
        } else {
            navBarView = baseViewBinding.navBar
                ?: throw IllegalStateException("Landscape layout must contains a NavigationRailView")
            floatingActionButtons = baseViewBinding.floatingActionButtons
                ?: throw IllegalStateException("Landscape layout must contains a floating action buttons layout")
        }

        // Generic setup of the navigation
        navBarView.apply {
            inflateMenu(this)
            setOnItemSelectedListener { item ->
                updateContentView(item.itemId)
                true
            }
        }

        return baseViewBinding.root
    }

    @CallSuper
    override fun onDialogCreated(dialog: BottomSheetDialog) {
        // Setup dialog views. We need to do it here as it is the first place where the dialog is created and where we
        // can access its views.
        if (displayConfigManager.displayConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            setupPortraitViews()
        }

        installImeLift()

        updateContentView(
            itemId = navBarView.selectedItemId,
            forceUpdate = true,
        )
    }

    override fun onStart() {
        super.onStart()
        contentMap[navBarView.selectedItemId]?.resume()
    }

    override fun onStop() {
        super.onStop()
        contentMap[navBarView.selectedItemId]?.pause()
        imeLiftAnimator?.cancel()
        dialogCoordinatorLayout?.let { applyImeLift(it, 0) }
    }

    override fun onDestroy() {
        imeLiftAnimator?.cancel()
        imeLiftAnimator = null
        dialog?.window?.decorView?.viewTreeObserver?.removeOnGlobalLayoutListener(imeLayoutListener)
        dialog?.window?.decorView?.let { ViewCompat.setWindowInsetsAnimationCallback(it, null) }
        contentMap.values.forEach { content ->
            content.destroy()
        }
        contentMap.clear()
        super.onDestroy()
    }

    protected fun setMissingInputBadge(navItemId: Int, haveMissingInput: Boolean) {
        navBarView.getOrCreateBadge(navItemId).isVisible = haveMissingInput
    }

    private fun installImeLift() {
        val coordinator = dialogCoordinatorLayout ?: return
        val decor = dialog?.window?.decorView ?: return

        ViewCompat.setWindowInsetsAnimationCallback(
            decor,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimationRunning = true
                        imeLiftAnimator?.cancel()
                    }
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    applyImeLift(coordinator, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        imeAnimationRunning = false
                        updateImeLiftAnimated()
                    }
                }
            },
        )

        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
            if (!imeAnimationRunning) {
                applyImeLift(coordinator, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            }
            insets
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(imeLayoutListener)
        ViewCompat.requestApplyInsets(decor)
    }

    private fun updateImeLiftAnimated() {
        val coordinator = dialogCoordinatorLayout ?: return
        val decor = dialog?.window?.decorView ?: return
        val insetIme = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.ime())
            ?.bottom
            ?: 0
        val lift = max(insetIme, inputMethodVisibleHeight())
        val threshold = (decor.resources.displayMetrics.heightPixels * 0.12f).toInt()
        val target = if (lift > threshold) lift else 0
        if (target == lastImeLiftPx) return

        imeLiftAnimator?.cancel()
        val start = lastImeLiftPx
        imeLiftAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = 180L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                applyImeLift(coordinator, animator.animatedValue as Int)
            }
            start()
        }
    }

    private fun inputMethodVisibleHeight(): Int =
        try {
            val imm = context.getSystemService(InputMethodManager::class.java)
            val method = InputMethodManager::class.java.getMethod("getInputMethodWindowVisibleHeight")
            (method.invoke(imm) as? Int) ?: 0
        } catch (_: Throwable) {
            0
        }

    private fun applyImeLift(coordinator: CoordinatorLayout, imeBottomPx: Int) {
        if (imeBottomPx == lastImeLiftPx) return
        lastImeLiftPx = imeBottomPx
        for (i in 0 until coordinator.childCount) {
            coordinator.getChildAt(i).translationY = -imeBottomPx.toFloat()
        }
    }

    private fun setupPortraitViews() {
        dialogCoordinatorLayout?.apply {
            // Add the navigation bar.
            addView(
                navBarView,
                CoordinatorLayout.LayoutParams(
                    CoordinatorLayout.LayoutParams.MATCH_PARENT,
                    CoordinatorLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM
                }
            )

            // Add create/copy floating action buttons.
            addView(
                floatingActionButtons.root,
                CoordinatorLayout.LayoutParams(
                    CoordinatorLayout.LayoutParams.WRAP_CONTENT,
                    CoordinatorLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                }
            )
        }
    }

    private fun createContentView(itemId: Int): NavBarDialogContent =
        onCreateContent(itemId).apply {
            create(this@NavBarDialog, baseViewBinding.dialogContent, itemId)
        }

    private fun updateContentView(itemId: Int, forceUpdate: Boolean = false) {
        if (!forceUpdate && navBarView.selectedItemId == itemId) return

        // Get the current content and stop it, if any.
        contentMap[navBarView.selectedItemId]?.apply {
            pause()
            stop()
        }

        // Get new content. If it does not exist yet, create it.
        var content = contentMap[itemId]
        if (content == null) {
            content = createContentView(itemId)
            contentMap[itemId] = content
        }

        content.start()
        onContentViewChanged(itemId)

        floatingActionButtons.root.visibility =
            if (content.floatingActionButtonsAreAvailable()) View.VISIBLE
            else View.GONE

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) content.resume()
    }

    internal fun debounceInteraction(interaction: () -> Unit) {
        debounceUserInteraction(interaction)
    }

    private fun handleButtonClick(buttonType: DialogNavigationButton) {
        // First notify the contents.
        contentMap.values.forEach { contentInfo ->
            contentInfo.onDialogButtonClicked(buttonType)
        }

        // Then, notify the dialog
        onDialogButtonPressed(buttonType)
    }
}
