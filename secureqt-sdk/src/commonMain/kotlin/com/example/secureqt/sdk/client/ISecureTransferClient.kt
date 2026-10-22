package com.example.secureqt.sdk.client

import com.example.secureqt.sdk.model.SdkRemoteFile
import java.io.File

/**
 * High-level client API for interacting with a secure transfer host.
 * This encapsulates all the file chunking, directory zipping, and command protocol routing
 * so that consumers (like Android or Desktop apps) can just call these methods directly.
 */
interface ISecureTransferClient {
    /**
     * Lists files in the remote directory.
     */
    suspend fun listRemoteFiles(path: String): List<SdkRemoteFile>

    /**
     * Sends a local file to the remote path.
     */
    suspend fun sendFile(localFile: File, remotePath: String, remoteFileName: String? = null, onProgress: ((Long, Long) -> Unit)? = null): Boolean

    /**
     * Sends a local directory (as a ZIP stream) to the remote path.
     */
    suspend fun sendDirectory(localDir: File, remotePath: String, onProgress: ((Long, Long) -> Unit)? = null): Boolean

    /**
     * Fetches a file from the remote path to the local directory.
     */
    suspend fun fetchFile(remotePath: String, localSaveDir: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean

    /**
     * Fetches a directory (as a ZIP stream) from the remote path and unzips it into the local directory.
     */
    suspend fun fetchRemoteDirectory(remotePath: String, localSaveDir: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean

    /**
     * Deletes a remote file or folder.
     */
    suspend fun deleteFile(remotePath: String): Boolean

    /**
     * Renames a remote file or folder.
     */
    suspend fun renameFile(remotePath: String, newName: String): Boolean

    /**
     * Creates a new folder on the remote host.
     */
    suspend fun createFolder(remotePath: String): Boolean

    /**
     * Sends the host-assigned device ID to the client.
     */
    suspend fun sendDeviceId(deviceId: String): Boolean

    /**
     * Sends a disconnect command and closes the channel.
     */
    suspend fun sendDisconnect()

    /**
     * Aborts an ongoing file transfer instantly.
     */
    fun cancelTransfer()
}
