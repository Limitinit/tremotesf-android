package org.equeim.tremotesf.ui.utils

import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.marginBottom
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import org.equeim.tremotesf.ui.applyNavigationBarBottomInset

fun CoordinatorLayout.showSnackbar(
    message: CharSequence,
    duration: Int,
    @IdRes anchorViewId: Int = 0,
    @StringRes actionText: Int = 0,
    action: (() -> Unit)? = null,
    onDismissed: ((Snackbar) -> Unit)? = null
) = Snackbar.make(this, message, duration).apply {
    if (actionText != 0 && action != null) {
        setAction(actionText) { action() }
    }
    val insetsJob = if (anchorViewId != 0) {
        setAnchorView(anchorViewId)
        null
    } else {
        findFragment<Fragment>().applyNavigationBarBottomInset(paddingViews = emptyMap(), marginViews = mapOf(view to view.marginBottom))
    }
    addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
        override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
            insetsJob?.cancel()
            onDismissed?.invoke(transientBottomBar)
        }
    })
    show()
}

fun CoordinatorLayout.showSnackbar(
    @StringRes message: Int,
    duration: Int,
    @IdRes anchorViewId: Int = 0,
    @StringRes actionText: Int = 0,
    action: (() -> Unit)? = null,
    onDismissed: ((Snackbar) -> Unit)? = null
) = showSnackbar(resources.getString(message), duration, anchorViewId, actionText, action, onDismissed)
