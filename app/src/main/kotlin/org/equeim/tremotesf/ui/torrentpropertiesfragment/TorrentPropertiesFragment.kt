// SPDX-FileCopyrightText: 2017-2022 Alexey Rochev <equeim@gmail.com>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package org.equeim.tremotesf.ui.torrentpropertiesfragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.FloatingWindow
import androidx.navigation.NavDestination
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.equeim.libtremotesf.IntVector
import org.equeim.libtremotesf.RpcConnectionState
import org.equeim.libtremotesf.TorrentData
import org.equeim.tremotesf.R
import org.equeim.tremotesf.databinding.TorrentPropertiesFragmentBinding
import org.equeim.tremotesf.rpc.GlobalRpc
import org.equeim.tremotesf.rpc.statusString
import org.equeim.tremotesf.torrentfile.rpc.Rpc
import org.equeim.tremotesf.torrentfile.rpc.Torrent
import org.equeim.tremotesf.ui.NavigationFragment
import org.equeim.tremotesf.ui.Settings
import org.equeim.tremotesf.ui.TorrentFileRenameDialogFragment
import org.equeim.tremotesf.ui.applyNavigationBarBottomInset
import org.equeim.tremotesf.ui.torrentpropertiesfragment.TorrentPropertiesFragmentViewModel.Companion.hasTorrent
import org.equeim.tremotesf.ui.utils.*


class TorrentPropertiesFragment : NavigationFragment(
    R.layout.torrent_properties_fragment,
    0,
    R.menu.torrent_properties_fragment_menu
) {
    private val args: TorrentPropertiesFragmentArgs by navArgs()
    private val model by TorrentPropertiesFragmentViewModel.lazy(this)

    val binding by viewLifecycleObject(TorrentPropertiesFragmentBinding::bind)
    private var snackbar: Snackbar? by viewLifecycleObjectNullable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TorrentFileRenameDialogFragment.setFragmentResultListenerForRpc(this)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        toolbar.title = args.name

        viewLifecycleOwner.lifecycleScope.launch {
            if (Settings.quickReturn.get()) {
                toolbar.setOnClickListener {
                    val tab = PagerAdapter.tabs[binding.pager.currentItem]
                    childFragmentManager.fragments
                        .asSequence()
                        .filterIsInstance<PagerFragment>()
                        .find { it.tab == tab }
                        ?.onToolbarClicked()
                }
            }
        }

        binding.pager.adapter = PagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
            tab.setText(PagerAdapter.getTitle(position))
        }.attach()

        val torrentFilesModel: TorrentFilesFragmentViewModel by viewModels {
            viewModelFactory {
                initializer {
                    TorrentFilesFragmentViewModel(model.torrent, createSavedStateHandle())
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCustomCallback(viewLifecycleOwner) {
            binding.pager.currentItem == PagerAdapter.Tab.Files.ordinal &&
                    torrentFilesModel.filesTree.navigateUp()
        }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private var previousPage = -1

            override fun onPageSelected(position: Int) {
                if (previousPage != -1) {
                    requiredActivity.apply {
                        actionMode?.finish()
                        hideKeyboard()
                    }
                }
                if (position == PagerAdapter.Tab.Trackers.ordinal) {
                    binding.fab.show()
                } else {
                    binding.fab.hide()
                }
                previousPage = position
            }
        })

        binding.fab.apply {
            TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener {
                navigate(TorrentPropertiesFragmentDirections.toEditTrackerDialog())
            }
        }

        model.showTorrentRemovedMessage.handleAndReset(::showTorrentRemovedMessage)
            .launchAndCollectWhenStarted(viewLifecycleOwner)

        GlobalRpc.connectionState.launchAndCollectWhenStarted(viewLifecycleOwner, ::onConnectionStateChanged)

        combine(GlobalRpc.status, model.torrent, ::Pair)
            .launchAndCollectWhenStarted(viewLifecycleOwner) { (status, torrent) ->
                updatePlaceholderText(status, torrent)
            }

        model.torrent.hasTorrent().launchAndCollectWhenStarted(viewLifecycleOwner, ::onHasTorrentChanged)
        model.torrent.launchAndCollectWhenStarted(viewLifecycleOwner, ::onTorrentChanged)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        with(binding.pager) {
            if (isVisible) {
                model.rememberedPagerItem = currentItem
            }
        }
    }

    override fun onNavigatedFrom(newDestination: NavDestination) {
        if (newDestination !is FloatingWindow) {
            for (fragment in childFragmentManager.fragments) {
                (fragment as? PagerFragment)?.onNavigatedFromParent()
            }
        }
    }

    override fun onToolbarMenuItemClicked(menuItem: MenuItem): Boolean {
        val torrent = model.torrent.value ?: return false
        when (menuItem.itemId) {
            R.id.start -> GlobalRpc.nativeInstance.startTorrents(IntVector(listOf(torrent.id)))
            R.id.pause -> GlobalRpc.nativeInstance.pauseTorrents(IntVector(listOf(torrent.id)))
            R.id.check -> GlobalRpc.nativeInstance.checkTorrents(IntVector(listOf(torrent.id)))
            R.id.start_now -> GlobalRpc.nativeInstance.startTorrentsNow(IntVector(listOf(torrent.id)))
            R.id.reannounce -> GlobalRpc.nativeInstance.reannounceTorrents(IntVector(listOf(torrent.id)))
            R.id.set_location -> navigate(
                TorrentPropertiesFragmentDirections.toTorrentSetLocationDialog(
                    intArrayOf(torrent.id),
                    torrent.downloadDirectory
                )
            )
            R.id.rename -> navigate(
                TorrentPropertiesFragmentDirections.toTorrentFileRenameDialog(
                    torrent.name,
                    torrent.name,
                    torrent.id
                )
            )
            R.id.remove -> navigate(
                TorrentPropertiesFragmentDirections.toRemoveTorrentDialog(
                    intArrayOf(torrent.id),
                    true
                )
            )
            R.id.share -> Utils.shareTorrents(listOf(torrent.data.magnetLink), requireContext())
            else -> return false
        }
        return true
    }

    private fun showTorrentRemovedMessage() {
        snackbar?.dismiss()
        snackbar = null
        snackbar = binding.coordinatorLayout.showSnackbar(
            message = R.string.torrent_removed,
            duration = Snackbar.LENGTH_INDEFINITE,
            onDismissed = { snackbar, _ ->
                if (snackbar == this.snackbar) {
                    this.snackbar = null
                }
            }
        )
    }

    private fun onConnectionStateChanged(connectionState: RpcConnectionState) {
        snackbar?.dismiss()
        snackbar = null
        if (connectionState == RpcConnectionState.Disconnected) {
            snackbar = binding.coordinatorLayout.showSnackbar(
                message = "",
                duration = Snackbar.LENGTH_INDEFINITE,
                actionText = R.string.connect,
                action = GlobalRpc.nativeInstance::connect,
                onDismissed = { snackbar, _ ->
                    if (snackbar == this.snackbar) {
                        this.snackbar = null
                    }
                }
            )
        }

        binding.progressBar.isVisible = connectionState == RpcConnectionState.Connecting
    }

    private fun updatePlaceholderText(status: Rpc.Status, torrent: Torrent?) {
        with(binding.placeholder) {
            when (status.connectionState) {
                RpcConnectionState.Disconnected -> {
                    text = status.statusString
                }
                RpcConnectionState.Connecting -> {
                    text = getText(R.string.connecting)
                }
                RpcConnectionState.Connected -> {
                    if (torrent == null) {
                        text = getText(R.string.torrent_not_found)
                    }
                }
            }
        }
    }

    private fun onHasTorrentChanged(hasTorrent: Boolean) {
        updateViewVisibility(hasTorrent)
        if (!hasTorrent) {
            navController.popDialog()
        }
    }

    private fun updateViewVisibility(hasTorrent: Boolean) {
        with(binding) {
            if (hasTorrent) {
                (toolbar.layoutParams as AppBarLayout.LayoutParams?)?.scrollFlags =
                    AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                            AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP or
                            AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
                tabLayout.visibility = View.VISIBLE
                pager.visibility = View.VISIBLE
                placeholderLayout.visibility = View.GONE

                if (model.rememberedPagerItem != -1) {
                    pager.setCurrentItem(model.rememberedPagerItem, false)
                    model.rememberedPagerItem = -1
                }
            } else {
                (toolbar.layoutParams as AppBarLayout.LayoutParams?)?.scrollFlags = 0
                tabLayout.visibility = View.GONE
                pager.visibility = View.GONE
                pager.currentItem = 0
                placeholderLayout.visibility = View.VISIBLE
            }
        }
    }

    private fun onTorrentChanged(torrent: Torrent?) {
        updateMenu(torrent)
        if (torrent != null) {
            toolbar.title = torrent.name
        }
    }

    private fun updateMenu(torrent: Torrent?) {
        val menu = toolbar.menu
        if (torrent == null) {
            toolbar.hideOverflowMenu()
            menu.setGroupVisible(0, false)
        } else {
            menu.setGroupVisible(0, true)
            if (torrent.status == TorrentData.Status.Paused) {
                intArrayOf(R.id.pause)
            } else {
                intArrayOf(R.id.start, R.id.start_now)
            }.forEach { menu.findItem(it).isVisible = false }
        }
    }

    class PagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        companion object {
            val tabs = Tab.values()

            @StringRes
            fun getTitle(position: Int): Int {
                return when (tabs[position]) {
                    Tab.Details -> R.string.details
                    Tab.Files -> R.string.files
                    Tab.Trackers -> R.string.trackers
                    Tab.Peers -> R.string.peers
                    Tab.WebSeeders -> R.string.web_seeders
                    Tab.Limits -> R.string.limits
                }
            }
        }

        enum class Tab {
            Details,
            Files,
            Trackers,
            Peers,
            WebSeeders,
            Limits
        }

        override fun getItemCount(): Int {
            return tabs.size
        }

        override fun createFragment(position: Int): Fragment {
            return when (tabs[position]) {
                Tab.Details -> TorrentDetailsFragment()
                Tab.Files -> TorrentFilesFragment()
                Tab.Trackers -> TrackersFragment()
                Tab.Peers -> PeersFragment()
                Tab.WebSeeders -> WebSeedersFragment()
                Tab.Limits -> TorrentLimitsFragment()
            }
        }
    }

    abstract class PagerFragment(@LayoutRes contentLayoutId: Int, val tab: PagerAdapter.Tab) : Fragment(contentLayoutId) {
        open fun onNavigatedFromParent() = Unit
        open fun onToolbarClicked() = Unit

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)
            applyNavigationBarBottomInset()
        }
    }
}
