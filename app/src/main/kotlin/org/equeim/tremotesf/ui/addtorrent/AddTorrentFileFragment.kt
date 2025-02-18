// SPDX-FileCopyrightText: 2017-2022 Alexey Rochev <equeim@gmail.com>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package org.equeim.tremotesf.ui.addtorrent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.text.trimmedLength
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.withStarted
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import org.equeim.libtremotesf.IntVector
import org.equeim.libtremotesf.RpcConnectionState
import org.equeim.libtremotesf.StringMap
import org.equeim.tremotesf.R
import org.equeim.tremotesf.databinding.*
import org.equeim.tremotesf.rpc.GlobalRpc
import org.equeim.tremotesf.rpc.GlobalServers
import org.equeim.tremotesf.rpc.statusString
import org.equeim.tremotesf.ui.*
import org.equeim.tremotesf.ui.utils.*
import timber.log.Timber


class AddTorrentFileFragment : AddTorrentFragment(
    R.layout.add_torrent_file_fragment,
    R.string.add_torrent_file,
    0
) {
    companion object {
        fun setupDownloadDirectoryEdit(
            binding: DownloadDirectoryEditBinding,
            fragment: Fragment,
            savedInstanceState: Bundle?
        ): AddTorrentDirectoriesAdapter {
            val downloadDirectoryEdit = binding.downloadDirectoryEdit
            val downloadDirectoryLayout = binding.downloadDirectoryLayout
            downloadDirectoryEdit.doAfterTextChanged {
                val path = it?.toString()?.normalizePath()
                when {
                    path.isNullOrEmpty() -> {
                        downloadDirectoryLayout.helperText = null
                    }
                    GlobalRpc.serverSettings.canShowFreeSpaceForPath() -> {
                        GlobalRpc.nativeInstance.getFreeSpaceForPath(path)
                    }
                    path == GlobalRpc.serverSettings.downloadDirectory -> {
                        GlobalRpc.nativeInstance.getDownloadDirFreeSpace()
                    }
                    else -> {
                        downloadDirectoryLayout.helperText = null
                    }
                }
            }

            if (savedInstanceState == null) {
                fragment.lifecycleScope.launch {
                    val downloadDirectory = if (Settings.rememberDownloadDirectory.get()) {
                        GlobalServers.serversState.value.currentServer?.lastDownloadDirectory?.takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }

                    if (downloadDirectory != null) {
                        downloadDirectoryEdit.setText(downloadDirectory.toNativeSeparators())
                    } else {
                        GlobalRpc.isConnected.launchAndCollectWhenStarted(fragment.viewLifecycleOwner) {
                            downloadDirectoryEdit.setText(GlobalRpc.serverSettings.downloadDirectory.toNativeSeparators())
                        }
                    }
                }
            }

            val directoriesAdapter = AddTorrentDirectoriesAdapter(
                downloadDirectoryEdit,
                fragment.viewLifecycleOwner.lifecycleScope,
                savedInstanceState
            )
            downloadDirectoryEdit.setAdapter(directoriesAdapter)

            GlobalRpc.gotDownloadDirFreeSpaceEvents.launchAndCollectWhenStarted(fragment.viewLifecycleOwner) { bytes ->
                val text = downloadDirectoryEdit.text?.toString()?.normalizePath()
                if (text == GlobalRpc.serverSettings.downloadDirectory) {
                    downloadDirectoryLayout.helperText = fragment.getString(
                        R.string.free_space,
                        FormatUtils.formatByteSize(fragment.requireContext(), bytes)
                    )
                }
            }

            GlobalRpc.gotFreeSpaceForPathEvents.launchAndCollectWhenStarted(fragment.viewLifecycleOwner) { (path, success, bytes) ->
                val text = downloadDirectoryEdit.text?.toString()?.normalizePath()
                if (!text.isNullOrEmpty() && path.contentEquals(text)) {
                    downloadDirectoryLayout.helperText = if (success) {
                        fragment.getString(
                            R.string.free_space,
                            FormatUtils.formatByteSize(fragment.requireContext(), bytes)
                        )
                    } else {
                        fragment.getString(R.string.free_space_error)
                    }
                }
            }

            return directoriesAdapter
        }
    }

    private val args: AddTorrentFileFragmentArgs by navArgs()
    private val model: AddTorrentFileModel by viewModels<AddTorrentFileModelImpl> {
        viewModelFactory {
            initializer {
                AddTorrentFileModelImpl(
                    args,
                    checkNotNull(get(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY)),
                    createSavedStateHandle()
                )
            }
        }
    }

    private val binding by viewLifecycleObject(AddTorrentFileFragmentBinding::bind)
    private var connectSnackbar: Snackbar? by viewLifecycleObjectNullable()

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("onCreate: arguments = $arguments")

        with(model.storagePermissionHelper) {
            val launcher = registerWithFragment(this@AddTorrentFileFragment)
            if (model.needStoragePermission) {
                if (!checkPermission(requireContext())) {
                    lifecycleScope.launch {
                        lifecycle.withStarted {
                            requestPermission(this@AddTorrentFileFragment, launcher)
                        }
                    }
                }
            }
        }

        TorrentFileRenameDialogFragment.setFragmentResultListener(this) { (_, filePath, newName) ->
            model.renamedFiles[filePath] = newName
            model.filesTree.renameFile(filePath, newName)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        binding.pager.adapter = PagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
            tab.setText(PagerAdapter.getTitle(position))
        }.attach()

        binding.addButton.setOnClickListener { addTorrentFile() }
        binding.addButton.extendWhenImeIsHidden(requiredActivity.windowInsets, viewLifecycleOwner)

        requireActivity().onBackPressedDispatcher.addCustomCallback(viewLifecycleOwner) {
            !done &&
                    binding.pager.currentItem == PagerAdapter.Tab.Files.ordinal &&
                    model.filesTree.navigateUp()
        }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private var previousPage = -1

            override fun onPageSelected(position: Int) {
                if (previousPage != -1) {
                    findFragment<FilesFragment>()?.adapter?.selectionTracker?.clearSelection()
                    hideKeyboard()
                }
                previousPage = position
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            if (Settings.quickReturn.get()) {
                toolbar.setOnClickListener {
                    Timber.d("onViewStateRestored: clicked, current tab = ${PagerAdapter.tabs[binding.pager.currentItem]}")
                    if (PagerAdapter.tabs[binding.pager.currentItem] == PagerAdapter.Tab.Files) {
                        childFragmentManager.fragments
                            .filterIsInstance<FilesFragment>()
                            .singleOrNull()
                            ?.onToolbarClicked()
                    }
                }
            }
        }

        model.viewUpdateData.launchAndCollectWhenStarted(viewLifecycleOwner, ::updateView)
    }

    override fun onStart() {
        super.onStart()
        with(model) {
            if (needStoragePermission && !storagePermissionHelper.permissionGranted.value) {
                storagePermissionHelper.checkPermission(requireContext())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        with(binding.pager) {
            if (isVisible) {
                model.rememberedPagerItem = currentItem
            }
        }
    }

    private fun addTorrentFile() {
        val infoFragment = findFragment<InfoFragment>()
        if (infoFragment?.check() != true) return
        val priorities = model.getFilePriorities()
        val fd = model.detachFd() ?: return
        GlobalRpc.nativeInstance.addTorrentFile(
            fd,
            infoFragment.binding.downloadDirectoryLayout.downloadDirectoryEdit.text.toString()
                .normalizePath(),
            IntVector(priorities.unwantedFiles),
            IntVector(priorities.highPriorityFiles),
            IntVector(priorities.lowPriorityFiles),
            StringMap().apply { putAll(model.renamedFiles) },
            priorityItemEnums[priorityItems.indexOf(infoFragment.binding.priorityView.text.toString())],
            infoFragment.binding.startDownloadingCheckBox.isChecked
        )
        infoFragment.directoriesAdapter.save()
        done = true
        requiredActivity.onBackPressedDispatcher.onBackPressed()
    }

    private fun updateView(viewUpdateData: AddTorrentFileModel.ViewUpdateData) {
        val (parserStatus, rpcStatus, hasStoragePermission) = viewUpdateData

        with(binding) {
            if (rpcStatus.isConnected && parserStatus == AddTorrentFileModel.ParserStatus.Loaded) {
                this@AddTorrentFileFragment.toolbar.apply {
                    (layoutParams as AppBarLayout.LayoutParams).scrollFlags =
                        AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                                AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP or
                                AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
                    subtitle = model.torrentName
                }

                tabLayout.visibility = View.VISIBLE
                pager.visibility = View.VISIBLE

                placeholderLayout.visibility = View.GONE

                addButton.show()

                if (model.rememberedPagerItem != -1) {
                    pager.setCurrentItem(model.rememberedPagerItem, false)
                    model.rememberedPagerItem = -1
                }
            } else {
                placeholder.text = if (!hasStoragePermission && model.needStoragePermission) {
                    getString(R.string.storage_permission_error)
                } else {
                    when (parserStatus) {
                        AddTorrentFileModel.ParserStatus.Loading -> getString(R.string.loading)
                        AddTorrentFileModel.ParserStatus.FileIsTooLarge -> getString(R.string.file_is_too_large)
                        AddTorrentFileModel.ParserStatus.ReadingError -> getString(R.string.file_reading_error)
                        AddTorrentFileModel.ParserStatus.ParsingError -> getString(R.string.file_parsing_error)
                        AddTorrentFileModel.ParserStatus.Loaded -> rpcStatus.statusString
                        else -> null
                    }
                }

                progressBar.visibility =
                    if (parserStatus == AddTorrentFileModel.ParserStatus.Loading ||
                        (rpcStatus.connectionState == RpcConnectionState.Connecting && parserStatus == AddTorrentFileModel.ParserStatus.Loaded)
                    ) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                placeholderLayout.visibility = View.VISIBLE

                addButton.hide()

                this@AddTorrentFileFragment.toolbar.apply {
                    (layoutParams as AppBarLayout.LayoutParams).scrollFlags = 0
                    subtitle = null
                }

                hideKeyboard()

                tabLayout.visibility = View.GONE
                pager.visibility = View.GONE
                pager.setCurrentItem(0, false)
                placeholder.visibility = View.VISIBLE

                if (parserStatus == AddTorrentFileModel.ParserStatus.Loaded) {
                    if (rpcStatus.connectionState == RpcConnectionState.Disconnected) {
                        connectSnackbar = coordinatorLayout.showSnackbar(
                            message = "",
                            duration = Snackbar.LENGTH_INDEFINITE,
                            actionText = R.string.connect,
                            action = GlobalRpc.nativeInstance::connect,
                            onDismissed = { snackbar, _ ->
                                if (connectSnackbar == snackbar) {
                                    connectSnackbar = null
                                }
                            }
                        )
                    } else {
                        connectSnackbar?.dismiss()
                        connectSnackbar = null
                    }
                }
            }
        }
    }

    class PagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        companion object {
            val tabs = Tab.values()

            @StringRes
            fun getTitle(position: Int): Int {
                return when (tabs[position]) {
                    Tab.Info -> R.string.information
                    Tab.Files -> R.string.files
                }
            }
        }

        enum class Tab {
            Info,
            Files
        }

        override fun getItemCount() = tabs.size

        override fun createFragment(position: Int): Fragment {
            return when (tabs[position]) {
                Tab.Info -> InfoFragment()
                Tab.Files -> FilesFragment()
            }
        }
    }

    class InfoFragment : Fragment(R.layout.add_torrent_file_info_fragment) {
        val binding by viewLifecycleObject(AddTorrentFileInfoFragmentBinding::bind)
        var directoriesAdapter: AddTorrentDirectoriesAdapter by viewLifecycleObject()

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            with(binding) {
                priorityView.setText(R.string.normal_priority)
                priorityView.setAdapter(ArrayDropdownAdapter((requireParentFragment() as AddTorrentFileFragment).priorityItems))

                directoriesAdapter = setupDownloadDirectoryEdit(
                    downloadDirectoryLayout,
                    this@InfoFragment,
                    savedInstanceState
                )

                if (savedInstanceState == null) {
                    GlobalRpc.isConnected.launchAndCollectWhenStarted(viewLifecycleOwner) {
                        startDownloadingCheckBox.isChecked =
                            GlobalRpc.serverSettings.startAddedTorrents
                    }
                }
            }

            applyNavigationBarBottomInset()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            directoriesAdapter.saveInstanceState(outState)
        }

        fun check(): Boolean {
            val ret: Boolean
            with(binding.downloadDirectoryLayout) {
                downloadDirectoryLayout.error =
                    if (downloadDirectoryEdit.text.trimmedLength() == 0) {
                        ret = false
                        getString(R.string.empty_field_error)
                    } else {
                        ret = true
                        null
                    }
            }
            return ret
        }
    }

    class FilesFragment : Fragment(R.layout.add_torrent_file_files_fragment) {
        private val mainFragment: AddTorrentFileFragment
            get() = requireParentFragment() as AddTorrentFileFragment

        private val binding by viewLifecycleObject(AddTorrentFileFilesFragmentBinding::bind)
        val adapter: Adapter by viewLifecycleObject {
            Adapter(mainFragment.model, this, requireActivity() as NavigationActivity)
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            binding.filesView.apply {
                adapter = this@FilesFragment.adapter
                layoutManager = LinearLayoutManager(activity)
                addItemDecoration(
                    DividerItemDecoration(
                        requireContext(),
                        DividerItemDecoration.VERTICAL
                    )
                )
                (itemAnimator as DefaultItemAnimator).supportsChangeAnimations = false
            }

            mainFragment.model.filesTree.items.launchAndCollectWhenStarted(
                viewLifecycleOwner,
                adapter::update
            )

            applyNavigationBarBottomInset()
        }

        fun onToolbarClicked() {
            binding.filesView.scrollToPosition(0)
        }

        class Adapter(
            private val model: AddTorrentFileModel,
            fragment: Fragment,
            private val activity: NavigationActivity
        ) : BaseTorrentFilesAdapter(model.filesTree, fragment) {
            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int
            ): RecyclerView.ViewHolder {
                if (viewType == TYPE_ITEM) {
                    return ItemHolder(
                        this,
                        selectionTracker,
                        LocalTorrentFileListItemBinding.inflate(
                            LayoutInflater.from(parent.context),
                            parent,
                            false
                        )
                    )
                }
                return super.onCreateViewHolder(parent, viewType)
            }

            override fun navigateToRenameDialog(path: String, name: String) {
                activity.navigate(
                    AddTorrentFileFragmentDirections.toTorrentFileRenameDialog(
                        path,
                        name
                    )
                )
            }

            private class ItemHolder(
                private val adapter: Adapter,
                selectionTracker: SelectionTracker<Int>,
                val binding: LocalTorrentFileListItemBinding
            ) : BaseItemHolder(adapter, selectionTracker, binding.root) {
                override fun update() {
                    super.update()
                    bindingAdapterPositionOrNull?.let(adapter::getItem)?.let { item ->
                        binding.sizeTextView.apply {
                            text = FormatUtils.formatByteSize(context, item.size)
                        }
                    }
                }
            }
        }
    }
}

