package com.v2ray.ang.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.dto.ServerConfig
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app: AngApplication
        get() = getApplication<Application>() as AngApplication

    val servers = MmkvManager.decodeServerList()

    fun reloadServerList() {
        servers.clear()
        servers.addAll(MmkvManager.decodeServerList())
    }

    fun removeServer(guid: String?) {
        if (guid == null) return
        MmkvManager.removeServer(guid)
        reloadServerList()
    }

    fun swapServer(fromPosition: Int, toPosition: Int) {
        MmkvManager.swapServer(fromPosition, toPosition)
        reloadServerList()
    }

    fun selectServer(guid: String?) {
        if (guid == null) return
        MmkvManager.setSelectServer(guid)
        reloadServerList()
    }

    fun getServers(): MutableList<ServerConfig> {
        return servers
    }

    fun exportAllServer(): String? {
        try {
            val list = MmkvManager.decodeServerList()
            val sb = StringBuilder()
            for (config in list) {
                sb.append(config.toUri()).append("\n")
            }
            return Utils.encode(sb.toString().trim())
        } catch (e: Exception) {
            e.printStackTrace()
            app.toast(R.string.toast_failure)
        }
        return null
    }

    fun removeAllServer() {
        try {
            val list = MmkvManager.decodeServerList()
            for (config in list) {
                MmkvManager.removeServer(config.guid)
            }
            reloadServerList()
            app.toast(R.string.toast_success)
        } catch (e: Exception) {
            e.printStackTrace()
            app.toast(R.string.toast_failure)
        }
    }

    fun importBatchConfig(serverList: String?): Int {
        if (serverList.isNullOrEmpty()) {
            return 0
        }
        var count = 0
        try {
            val subList = serverList.lines()
            for (str in subList) {
                if (str.isBlank()) continue
                val config = ServerConfig.create(str)
                if (config != null) {
                    MmkvManager.encodeServerConfig("", config)
                    count++
                }
            }
            reloadServerList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return count
    }
}
