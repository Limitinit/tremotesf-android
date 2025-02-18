// SPDX-FileCopyrightText: 2017-2022 Alexey Rochev <equeim@gmail.com>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package org.equeim.tremotesf.torrentfile.rpc

import org.equeim.libtremotesf.*
import org.threeten.bp.Instant

class Torrent(
    val data: TorrentData,
    private val rpc: Rpc,
    prevTorrent: Torrent?
) {
    val id = data.id
    val hashString: String = data.hashString

    val name: String = data.name

    val status: TorrentData.Status = data.status
    val isDownloadingStalled: Boolean = data.isDownloadingStalled
    val isSeedingStalled: Boolean = data.isSeedingStalled
    val hasError: Boolean = data.error != TorrentData.Error.None
    val errorString: String = data.errorString

    val totalSize = data.totalSize
    val completedSize = data.completedSize
    val sizeWhenDone = data.sizeWhenDone
    val percentDone = data.percentDone
    val isFinished = data.leftUntilDone == 0L
    val recheckProgress = data.recheckProgress
    val eta = data.eta

    val downloadSpeed = data.downloadSpeed
    val uploadSpeed = data.uploadSpeed

    val totalDownloaded = data.totalDownloaded
    val totalUploaded = data.totalUploaded
    val ratio = data.ratio

    val peersSendingToUsCount = data.peersSendingToUsCount
    val webSeedersSendingToUsCount = data.webSeedersSendingToUsCount
    val peersGettingFromUsCount = data.peersGettingFromUsCount

    val addedDate: Instant? = data.addedDate

    val downloadDirectory: String = data.downloadDirectory

    val trackers: List<Tracker> = data.trackers
    val trackerSites: List<String> = trackers.map { it.site() }

    var filesEnabled: Boolean = prevTorrent?.filesEnabled ?: false
        @Synchronized get
        @Synchronized set(value) {
            if (value != field) {
                field = value
                rpc.nativeInstance.setTorrentFilesEnabled(data, value)
            }
        }

    var peersEnabled: Boolean = prevTorrent?.peersEnabled ?: false
        @Synchronized get
        @Synchronized set(value) {
            if (value != field) {
                field = value
                rpc.nativeInstance.setTorrentPeersEnabled(data, value)
            }
        }

    fun setDownloadSpeedLimited(limited: Boolean) {
        rpc.nativeInstance.setTorrentDownloadSpeedLimited(data, limited)
    }

    fun setDownloadSpeedLimit(limit: Int) {
        rpc.nativeInstance.setTorrentDownloadSpeedLimit(data, limit)
    }

    fun setUploadSpeedLimited(limited: Boolean) {
        rpc.nativeInstance.setTorrentUploadSpeedLimited(data, limited)
    }

    fun setUploadSpeedLimit(limit: Int) {
        rpc.nativeInstance.setTorrentUploadSpeedLimit(data, limit)
    }

    fun setRatioLimitMode(mode: TorrentData.RatioLimitMode) {
        rpc.nativeInstance.setTorrentRatioLimitMode(data, mode)
    }

    fun setRatioLimit(limit: Double) {
        rpc.nativeInstance.setTorrentRatioLimit(data, limit)
    }

    fun setPeersLimit(limit: Int) {
        rpc.nativeInstance.setTorrentPeersLimit(data, limit)
    }

    fun setHonorSessionLimits(honor: Boolean) {
        rpc.nativeInstance.setTorrentHonorSessionLimits(data, honor)
    }

    fun setBandwidthPriority(priority: TorrentData.Priority) {
        rpc.nativeInstance.setTorrentBandwidthPriority(data, priority)
    }

    fun setIdleSeedingLimitMode(mode: TorrentData.IdleSeedingLimitMode) {
        rpc.nativeInstance.setTorrentIdleSeedingLimitMode(data, mode)
    }

    fun setIdleSeedingLimit(limit: Int) {
        rpc.nativeInstance.setTorrentIdleSeedingLimit(data, limit)
    }

    fun setFilesWanted(files: IntArray, wanted: Boolean) {
        rpc.nativeInstance.setTorrentFilesWanted(data, IntVector(files), wanted)
    }

    fun setFilesPriority(files: IntArray, priority: TorrentFile.Priority) {
        rpc.nativeInstance.setTorrentFilesPriority(data, IntVector(files), priority)
    }

    fun addTrackers(announceUrls: List<String>) {
        val vector = StringsVector()
        vector.reserve(announceUrls.size.toLong())
        vector.addAll(announceUrls)
        rpc.nativeInstance.torrentAddTrackers(data, vector)
        vector.delete()
    }

    fun setTracker(trackerId: Int, announce: String) {
        rpc.nativeInstance.torrentSetTracker(data, trackerId, announce)
    }

    fun removeTrackers(ids: IntArray) {
        rpc.nativeInstance.torrentRemoveTrackers(data, IntVector(ids))
    }
}
