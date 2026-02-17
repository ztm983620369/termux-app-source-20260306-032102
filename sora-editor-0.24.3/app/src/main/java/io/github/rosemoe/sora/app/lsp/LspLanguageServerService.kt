/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2025  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.app.lsp

import android.app.Service
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread


class LspLanguageServerService : Service() {

    private var socket: LocalServerSocket? = null
    private var socketClient: LocalSocket? = null

    companion object {
        private const val TAG = "LanguageServer"
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only used in test
        thread {
            socket = LocalServerSocket("lua-lsp")

            Log.d(TAG, "Starting socket on address ${socket?.localSocketAddress}")

            socketClient = socket?.accept()

            runCatching {
            }.onFailure {
                Log.d(TAG, "Unexpected exception is thrown in the Language Server Thread.", it)
            }

            socketClient?.close()
            socketClient = null

            socket?.close()
            socket = null
        }

        return START_STICKY
    }

    override fun onDestroy() {
        socketClient?.close()
        socketClient = null
        socket?.close()
        socket = null
        super.onDestroy()
    }


}
