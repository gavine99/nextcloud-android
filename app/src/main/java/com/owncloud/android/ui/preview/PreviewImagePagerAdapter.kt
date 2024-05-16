/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2023 Alper Ozturk <alper_ozturk@proton.me>
 * SPDX-FileCopyrightText: 2018 Tobias Kaminsky <tobias@kaminsky.me>
 * SPDX-FileCopyrightText: 2020 Chris Narkiewicz <hello@ezaquarii.com>
 * SPDX-FileCopyrightText: 2015 ownCloud Inc.
 * SPDX-FileCopyrightText: 2013 David A. Velasco <dvelasco@solidgear.es>
 * SPDX-License-Identifier: GPL-2.0-only AND AGPL-3.0-or-later
 */
package com.owncloud.android.ui.preview

import android.util.SparseArray
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.nextcloud.client.account.User
import com.nextcloud.client.preferences.AppPreferences
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.datamodel.VirtualFolderType
import com.owncloud.android.ui.fragment.FileFragment
import com.owncloud.android.utils.FileStorageUtils

/**
 * Adapter class that provides Fragment instances
 */
class PreviewImagePagerAdapter : FragmentStateAdapter {

    private var selectedFile: OCFile? = null
    private var imageFiles: MutableList<OCFile> = mutableListOf()
    private val user: User
    private val mObsoleteFragments: MutableSet<Any>
    private val mObsoletePositions: MutableSet<Int>
    private val mDownloadErrors: MutableSet<Int>
    private val mStorageManager: FileDataStorageManager
    private val mCachedFragments: SparseArray<FileFragment>

    /**
     * Constructor
     *
     * @param fragmentActivity [FragmentActivity] instance that will handle the [Fragment]s provided by the
     * adapter.
     * @param parentFolder    Folder where images will be searched for.
     * @param storageManager  Bridge to database.
     */
    constructor(
        fragmentActivity: FragmentActivity?,
        selectedFile: OCFile?,
        parentFolder: OCFile?,
        user: User,
        storageManager: FileDataStorageManager?,
        onlyOnDevice: Boolean,
        preferences: AppPreferences
    ) : super(fragmentActivity!!) {
        requireNotNull(parentFolder) { "NULL parent folder" }
        requireNotNull(storageManager) { "NULL storage manager" }

        this.user = user
        this.selectedFile = selectedFile
        mStorageManager = storageManager
        imageFiles = mStorageManager.getFolderImages(parentFolder, onlyOnDevice)

        val sortOrder = preferences.getSortOrderByFolder(parentFolder)
        imageFiles = sortOrder.sortCloudFiles(imageFiles.toMutableList()).toMutableList()

        mObsoleteFragments = HashSet()
        mObsoletePositions = HashSet()
        mDownloadErrors = HashSet()
        mCachedFragments = SparseArray()
    }

    /**
     * Constructor
     *
     * @param fragmentActivity [FragmentActivity] instance that will handle the [Fragment]s provided by the
     * adapter.
     * @param type            Type of virtual folder, e.g. favorite or photos
     * @param storageManager  Bridge to database.
     */
    constructor(
        fragmentActivity: FragmentActivity?,
        type: VirtualFolderType?,
        user: User,
        storageManager: FileDataStorageManager?
    ) : super(fragmentActivity!!) {
        requireNotNull(type) { "NULL parent folder" }
        require(type != VirtualFolderType.NONE) { "NONE virtual folder type" }
        requireNotNull(storageManager) { "NULL storage manager" }

        this.user = user
        mStorageManager = storageManager

        if (type == VirtualFolderType.GALLERY) {
            imageFiles = mStorageManager.allGalleryItems
            imageFiles = FileStorageUtils.sortOcFolderDescDateModifiedWithoutFavoritesFirst(imageFiles)
        } else {
            imageFiles = mStorageManager.getVirtualFolderContent(type, true)
        }

        mObsoleteFragments = HashSet()
        mObsoletePositions = HashSet()
        mDownloadErrors = HashSet()
        mCachedFragments = SparseArray()
    }

    fun delete(position: Int) {
        if (position < 0 || position >= imageFiles.size) {
            return
        }

        mCachedFragments[position]?.let {
            mObsoleteFragments.add(it)
        }

        mObsoletePositions.add(position)

        imageFiles.removeAt(position)
        mDownloadErrors.remove(position)
        mCachedFragments.remove(position)

        notifyItemRemoved(position)
    }

    /**
     * Returns the image files handled by the adapter.
     *
     * @return OCFile desired image or null if position is not in adapter
     */
    fun getFileAt(position: Int): OCFile? {
        return try {
            imageFiles!![position]
        } catch (exception: IndexOutOfBoundsException) {
            null
        }
    }

    override fun getItemId(position: Int): Long {
        return imageFiles[position].hashCode().toLong()
    }

    private fun addVideoOfLivePhoto(file: OCFile) {
        file.livePhotoVideo = selectedFile
    }

    fun getItem(i: Int): Fragment {
        val file = getFileAt(i)
        val fragment: Fragment

        if (file == null) {
            fragment = PreviewImageErrorFragment.newInstance()
        } else if (file.isDown) {
            fragment = PreviewImageFragment.newInstance(file, mObsoletePositions.contains(i), false)
        } else {
            addVideoOfLivePhoto(file)

            if (mDownloadErrors.remove(i)) {
                fragment = FileDownloadFragment.newInstance(file, user, true)
                (fragment as FileDownloadFragment).setError(true)
            } else {
                fragment = if (file.isEncrypted) {
                    FileDownloadFragment.newInstance(file, user, mObsoletePositions.contains(i))
                } else if (PreviewMediaFragment.canBePreviewed(file)) {
                    PreviewMediaFragment.newInstance(file, user, 0, false, file.livePhotoVideo != null)
                } else {
                    PreviewImageFragment.newInstance(file, mObsoletePositions.contains(i), true)
                }
            }
        }

        mObsoletePositions.remove(i)
        return fragment
    }

    fun getFilePosition(file: OCFile): Int {
        return imageFiles.indexOf(file)
    }

    fun getPageTitle(position: Int): CharSequence {
        val file = getFileAt(position)

        return if (file != null) {
            file.fileName
        } else {
            ""
        }
    }

    fun updateFile(position: Int, file: OCFile) {
        val fragmentToUpdate = mCachedFragments[position]
        if (fragmentToUpdate != null) {
            mObsoleteFragments.add(fragmentToUpdate)
        }
        mObsoletePositions.add(position)
        imageFiles[position] = file
    }

    fun updateWithDownloadError(position: Int) {
        val fragmentToUpdate = mCachedFragments[position]
        if (fragmentToUpdate != null) {
            mObsoleteFragments.add(fragmentToUpdate)
        }
        mDownloadErrors.add(position)
    }

    fun pendingErrorAt(position: Int): Boolean {
        return mDownloadErrors.contains(position)
    }

    override fun createFragment(position: Int): Fragment {
        return getItem(position)
    }

    override fun getItemCount(): Int {
        return imageFiles.size
    }
}
