/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Jonas Mayer <jonas.a.mayer@gmx.net>
 * SPDX-FileCopyrightText: 2024 Alper Ozturk <alper.ozturk@nextcloud.com>
 * SPDX-FileCopyrightText: 2019 Alice Gaudon <alice@gaudon.pro>
 * SPDX-FileCopyrightText: 2012 Bartosz Przybylski <bart.p.pl@gmail.com>
 * SPDX-License-Identifier: GPL-2.0-only AND (AGPL-3.0-or-later OR GPL-2.0-only)
 */
package com.owncloud.android.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.nextcloud.client.account.User
import com.nextcloud.client.jobs.download.FileDownloadHelper
import com.nextcloud.client.jobs.upload.FileUploadHelper
import com.nextcloud.client.jobs.upload.FileUploadWorker
import com.nextcloud.client.jobs.upload.UploadNotificationManager
import com.nextcloud.model.HTTPStatusCodes
import com.nextcloud.utils.extensions.getParcelableArgument
import com.nextcloud.utils.extensions.logFileSize
import com.owncloud.android.R
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.datamodel.UploadsStorageManager
import com.owncloud.android.db.OCUpload
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile
import com.owncloud.android.ui.dialog.ConflictsResolveDialog
import com.owncloud.android.ui.dialog.ConflictsResolveDialog.Decision
import com.owncloud.android.ui.dialog.ConflictsResolveDialog.OnConflictDecisionMadeListener
import com.owncloud.android.utils.FileStorageUtils
import javax.inject.Inject

/**
 * Wrapper activity which will be launched if keep-in-sync file will be modified by external application.
 */
class ConflictsResolveActivity : FileActivity(), OnConflictDecisionMadeListener {
    @Inject
    lateinit var uploadsStorageManager: UploadsStorageManager

    @Inject
    lateinit var fileStorageManager: FileDataStorageManager

    private var conflictUploadId: Long = 0
    private var offlineOperationPath: String? = null
    private var existingFile: OCFile? = null
    private var newFile: OCFile? = null
    private var localBehaviour = FileUploadWorker.LOCAL_BEHAVIOUR_FORGET

    @JvmField
    var listener: OnConflictDecisionMadeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        getArguments(savedInstanceState)

        val upload = uploadsStorageManager.getUploadById(conflictUploadId)
        if (upload != null) {
            localBehaviour = upload.localAction
        }

        // new file was modified locally in file system
        newFile = file
        setupOnConflictDecisionMadeListener(upload)
    }

    private fun getArguments(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            conflictUploadId = savedInstanceState.getLong(EXTRA_CONFLICT_UPLOAD_ID)
            existingFile = savedInstanceState.getParcelableArgument(EXTRA_EXISTING_FILE, OCFile::class.java)
            localBehaviour = savedInstanceState.getInt(EXTRA_LOCAL_BEHAVIOUR)
            offlineOperationPath = savedInstanceState.getString(EXTRA_OFFLINE_OPERATION_PATH)
        } else {
            offlineOperationPath = intent.getStringExtra(EXTRA_OFFLINE_OPERATION_PATH)
            conflictUploadId = intent.getLongExtra(EXTRA_CONFLICT_UPLOAD_ID, -1)
            existingFile = intent.getParcelableArgument(EXTRA_EXISTING_FILE, OCFile::class.java)
            localBehaviour = intent.getIntExtra(EXTRA_LOCAL_BEHAVIOUR, localBehaviour)
        }
    }

    private fun setupOnConflictDecisionMadeListener(upload: OCUpload?) {
        listener = OnConflictDecisionMadeListener { decision: Decision? ->
            val file = newFile // local file got changed, so either upload it or replace it again by server
            // version
            val user = user.orElseThrow { RuntimeException() }
            when (decision) {
                Decision.CANCEL -> {}
                Decision.KEEP_LOCAL -> {
                    keepLocal(file, upload, user)
                }

                Decision.KEEP_BOTH -> {
                    keepBoth(file, upload, user)
                }

                Decision.KEEP_SERVER -> {
                    keepServer(file, upload)
                }

                else -> {}
            }
            finish()
        }
    }

    private fun keepLocal(file: OCFile?, upload: OCUpload?, user: User) {
        upload?.let {
            FileUploadHelper.instance().removeFileUpload(it.remotePath, it.accountName)
        }

        FileUploadHelper.instance().uploadUpdatedFile(
            user,
            arrayOf(file),
            localBehaviour,
            NameCollisionPolicy.OVERWRITE
        )
    }

    private fun keepBoth(file: OCFile?, upload: OCUpload?, user: User) {
        upload?.let {
            FileUploadHelper.instance().removeFileUpload(it.remotePath, it.accountName)
        }

        FileUploadHelper.instance().uploadUpdatedFile(
            user,
            arrayOf(file),
            localBehaviour,
            NameCollisionPolicy.RENAME
        )
    }

    private fun keepServer(file: OCFile?, upload: OCUpload?) {
        if (!shouldDeleteLocal()) {
            // Overwrite local file
            file?.let {
                FileDownloadHelper.instance().downloadFile(
                    user.orElseThrow { RuntimeException() },
                    file,
                    conflictUploadId = conflictUploadId
                )
            }
        }

        upload?.let {
            FileUploadHelper.instance().removeFileUpload(it.remotePath, it.accountName)

            UploadNotificationManager(
                applicationContext,
                viewThemeUtils
            ).dismissOldErrorNotification(it.remotePath, it.localPath)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        existingFile.logFileSize(TAG)

        outState.run {
            putLong(EXTRA_CONFLICT_UPLOAD_ID, conflictUploadId)
            putParcelable(EXTRA_EXISTING_FILE, existingFile)
            putInt(EXTRA_LOCAL_BEHAVIOUR, localBehaviour)
        }
    }

    override fun conflictDecisionMade(decision: Decision?) {
        listener?.conflictDecisionMade(decision)
    }

    @Suppress("ReturnCount")
    override fun onStart() {
        super.onStart()

        if (account == null) {
            finish()
            return
        }

        if (newFile == null) {
            Log_OC.e(TAG, "No file received")
            finish()
            return
        }

        if (existingFile == null) {
            val remotePath = fileStorageManager.retrieveRemotePathConsideringEncryption(newFile) ?: return
            val operation = ReadFileRemoteOperation(remotePath)

            @Suppress("TooGenericExceptionCaught")
            Thread {
                try {
                    val result = operation.execute(account, this)
                    if (result.isSuccess) {
                        existingFile = FileStorageUtils.fillOCFile(result.data[0] as RemoteFile)
                        existingFile?.lastSyncDateForProperties = System.currentTimeMillis()
                        startDialog(remotePath)
                    } else {
                        Log_OC.e(TAG, "ReadFileRemoteOp returned failure with code: " + result.httpCode)
                        showErrorAndFinish(result.httpCode)
                    }
                } catch (e: Exception) {
                    Log_OC.e(TAG, "Error when trying to fetch remote file", e)
                    showErrorAndFinish()
                }
            }.start()
        } else {
            val remotePath = fileStorageManager.retrieveRemotePathConsideringEncryption(existingFile) ?: return
            startDialog(remotePath)
        }
    }

    private fun startDialog(remotePath: String) {
        val userOptional = user
        if (!userOptional.isPresent) {
            Log_OC.e(TAG, "User not present")
            showErrorAndFinish()
        }

        // Check whether the file is contained in the current Account
        val prev = supportFragmentManager.findFragmentByTag("conflictDialog")
        val fragmentTransaction = supportFragmentManager.beginTransaction()
        if (prev != null) {
            fragmentTransaction.remove(prev)
        }
        if (existingFile != null && storageManager.fileExists(remotePath) && newFile != null) {
            val dialog = ConflictsResolveDialog.newInstance(
                existingFile,
                newFile!!,
                userOptional.get()
            )
            dialog.show(fragmentTransaction, "conflictDialog")
        } else {
            // Account was changed to a different one - just finish
            Log_OC.e(TAG, "Account was changed, finishing")
            showErrorAndFinish()
        }
    }

    private fun showErrorAndFinish(code: Int? = null) {
        val message = parseErrorMessage(code)
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun parseErrorMessage(code: Int?): String {
        return if (code == HTTPStatusCodes.NOT_FOUND.code) {
            getString(R.string.uploader_file_not_found_on_server_message)
        } else {
            getString(R.string.conflict_dialog_error)
        }
    }

    /**
     * @return whether the local version of the files is to be deleted.
     */
    private fun shouldDeleteLocal(): Boolean {
        return localBehaviour == FileUploadWorker.LOCAL_BEHAVIOUR_DELETE
    }

    companion object {
        /**
         * A nullable upload entry that must be removed when and if the conflict is resolved.
         */
        const val EXTRA_CONFLICT_UPLOAD_ID = "CONFLICT_UPLOAD_ID"

        /**
         * Specify the upload local behaviour when there is no CONFLICT_UPLOAD.
         */
        const val EXTRA_LOCAL_BEHAVIOUR = "LOCAL_BEHAVIOUR"
        const val EXTRA_EXISTING_FILE = "EXISTING_FILE"
        private const val EXTRA_OFFLINE_OPERATION_PATH = "EXTRA_OFFLINE_OPERATION_PATH"

        private val TAG = ConflictsResolveActivity::class.java.simpleName

        @JvmStatic
        fun createIntent(file: OCFile?, user: User?, conflictUploadId: Long, flag: Int?, context: Context?): Intent {
            return Intent(context, ConflictsResolveActivity::class.java).apply {
                if (flag != null) {
                    flags = flags or flag
                }
                putExtra(EXTRA_FILE, file)
                putExtra(EXTRA_USER, user)
                putExtra(EXTRA_CONFLICT_UPLOAD_ID, conflictUploadId)
            }
        }

        @JvmStatic
        fun createIntent(file: OCFile, offlineOperationPath: String, context: Context): Intent {
            return Intent(context, ConflictsResolveActivity::class.java).apply {
                putExtra(EXTRA_FILE, file)
                putExtra(EXTRA_OFFLINE_OPERATION_PATH, offlineOperationPath)
            }
        }
    }
}
