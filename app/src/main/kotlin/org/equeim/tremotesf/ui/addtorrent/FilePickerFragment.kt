/*
 * Copyright (C) 2017-2020 Alexey Rochev <equeim@gmail.com>
 *
 * This file is part of Tremotesf.
 *
 * Tremotesf is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Tremotesf is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.equeim.tremotesf.ui.addtorrent

import android.Manifest
import android.app.Application
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.equeim.tremotesf.R
import org.equeim.tremotesf.data.FilesystemNavigator
import org.equeim.tremotesf.databinding.FilePickerFragmentBinding
import org.equeim.tremotesf.ui.NavigationFragment
import org.equeim.tremotesf.ui.utils.RuntimePermissionHelper
import org.equeim.tremotesf.ui.utils.savedStateFlow
import org.equeim.tremotesf.ui.utils.viewBinding
import org.equeim.tremotesf.ui.utils.collectWhenStarted
import java.io.File


class FilePickerFragment : NavigationFragment(
    R.layout.file_picker_fragment,
    R.string.select_file,
    R.menu.file_picker_fragment_menu
) {
    private val binding by viewBinding(FilePickerFragmentBinding::bind)
    private var adapter: FilePickerAdapter? = null

    private val model by viewModels<FilePickerFragmentViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        with(model.storagePermissionHelper) {
            val launcher = registerWithFragment(this@FilePickerFragment)
            if (!checkPermission(requireContext())) {
                lifecycleScope.launchWhenStarted {
                    requestPermission(this@FilePickerFragment, launcher)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = FilePickerAdapter(this, model.navigator)
        this.adapter = adapter

        binding.filesView.apply {
            this.adapter = adapter
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(
                DividerItemDecoration(
                    requireContext(),
                    DividerItemDecoration.VERTICAL
                )
            )
            (itemAnimator as DefaultItemAnimator).supportsChangeAnimations = false
        }

        model.currentDirectory.collectWhenStarted(viewLifecycleOwner) {
            binding.currentDirectoryTextView.text = it.absolutePath
        }

        model.navigator.items
            .map { it.asSequence().filterNotNull().none() }
            .distinctUntilChanged()
            .combine(model.storagePermissionHelper.permissionGranted, ::Pair)
            .collectWhenStarted(viewLifecycleOwner) { (noFiles, hasStoragePermission) ->
                binding.placeholder.text = when {
                    !hasStoragePermission -> getText(R.string.storage_permission_error)
                    noFiles -> getText(R.string.no_files)
                    else -> null
                }
            }

        model.storagePermissionHelper.permissionGranted.collectWhenStarted(viewLifecycleOwner) {
            binding.filesView.isVisible = it
        }
    }

    override fun onToolbarMenuItemClicked(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.primary_storage) {
            model.navigator.navigateToHome()
            return true
        }
        return false
    }

    fun finish(fileUri: Uri) {
        navigate(FilePickerFragmentDirections.toAddTorrentFileFragment(fileUri))
    }
}

private class FilePickerAdapter(
    private val fragment: FilePickerFragment,
    private val navigator: FilesystemNavigator
) : ListAdapter<File?, RecyclerView.ViewHolder>(ItemCallback()) {
    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    init {
        navigator.items.collectWhenStarted(fragment.viewLifecycleOwner, ::submitList)
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position) == null) {
            TYPE_HEADER
        } else {
            TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_HEADER) {
            return HeaderHolder(
                inflater.inflate(
                    R.layout.up_list_item,
                    parent,
                    false
                )
            )
        }
        return ItemHolder(
            inflater.inflate(
                R.layout.file_picker_fragment_list_item,
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder.itemViewType == TYPE_ITEM) {
            holder as ItemHolder

            val file = getItem(position)!!

            holder.file = file
            holder.textView.text = file.name
            holder.iconDrawable.level = if (file.isDirectory) 0 else 1
        }
    }

    private class ItemCallback : DiffUtil.ItemCallback<File?>() {
        override fun areItemsTheSame(oldItem: File, newItem: File): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: File, newItem: File): Boolean {
            return true
        }
    }

    private inner class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            itemView.setOnClickListener { navigator.navigateUp() }
        }
    }

    private inner class ItemHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        lateinit var file: File
        val textView = itemView as TextView
        val iconDrawable: Drawable = textView.compoundDrawablesRelative.first()

        init {
            itemView.setOnClickListener {
                if (file.isDirectory) {
                    navigator.navigateDown(file)
                } else {
                    fragment.finish(Uri.fromFile(file))
                }
            }
        }
    }
}

class FilePickerFragmentViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application) {
    @Suppress("DEPRECATION")
    val currentDirectory by savedStateFlow(savedStateHandle) { Environment.getExternalStorageDirectory() }

    val navigator = FilesystemNavigator(currentDirectory, viewModelScope)

    val storagePermissionHelper = RuntimePermissionHelper(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        R.string.storage_permission_rationale_file_picker
    )

    init {
        viewModelScope.launch {
            storagePermissionHelper.permissionGranted.first { it }
            navigator.init()
        }
    }
}
