/*
 * Copyright (C) 2017-2022 Alexey Rochev <equeim@gmail.com>
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

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.equeim.tremotesf.R
import org.equeim.tremotesf.rpc.GlobalRpc
import org.equeim.tremotesf.rpc.GlobalServers
import org.equeim.tremotesf.common.AlphanumericComparator
import org.equeim.tremotesf.ui.utils.BaseDropdownAdapter
import org.equeim.tremotesf.ui.utils.normalizePath
import org.equeim.tremotesf.ui.utils.toNativeSeparators


class AddTorrentDirectoriesAdapter(
    private val textEdit: EditText,
    savedInstanceState: Bundle?
) : BaseDropdownAdapter(
    R.layout.download_directory_dropdown_item,
    android.R.id.text1
) {
    companion object {
        private const val STATE_KEY =
            "org.equeim.tremotesf.ui.addtorrent.AddTorrentDirectoriesAdapter.items"
    }

    private val items: ArrayList<String>

    init {
        val saved = savedInstanceState?.getStringArrayList(STATE_KEY)
        items = if (saved != null) {
            saved
        } else {
            val comparator = AlphanumericComparator()
            val sorted =
                GlobalServers.serversState.value.currentServer?.addTorrentDialogDirectories
                    ?.map { it.normalizePath().toNativeSeparators() }
                    ?.toSortedSet(comparator)
                    ?: sortedSetOf(comparator)
            for (torrent in GlobalRpc.torrents.value) {
                sorted.add(torrent.downloadDirectory.toNativeSeparators())
            }
            sorted.add(GlobalRpc.serverSettings.downloadDirectory.toNativeSeparators())
            ArrayList(sorted)
        }
    }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]

    override fun createViewHolder(view: View): BaseViewHolder = ViewHolder(view)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        (view.tag as ViewHolder).position = position
        return view
    }

    fun remove(position: Int) {
        items.removeAt(position)
        notifyDataSetChanged()
    }

    fun save() {
        val saved = ArrayList(items.map { it.normalizePath() })
        val editPath = textEdit.text.toString().normalizePath()
        if (!saved.contains(editPath)) {
            saved.add(editPath)
        }
        GlobalServers.serversState.value.currentServer?.let { current ->
            if (current.addTorrentDialogDirectories != saved) {
                GlobalServers.addOrReplaceServer(current.copy(addTorrentDialogDirectories = saved))
            }
        }
    }

    fun saveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_KEY, items)
    }

    private inner class ViewHolder(view: View) : BaseViewHolder(view) {
        var position = -1

        init {
            view.findViewById<View>(R.id.remove_button).setOnClickListener {
                if (count > 1) {
                    remove(position)
                }
            }
        }
    }
}
