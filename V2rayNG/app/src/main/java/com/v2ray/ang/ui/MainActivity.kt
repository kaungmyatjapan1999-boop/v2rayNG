package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    // Developer Permanent Direct Subscription URL
    private val devSubUrl = "https://gist.githubusercontent.com/kaungmyatjapan1999-boop/a59c4a6cb6716e500964bb5ee9f3e757/raw/servers.txt"

    // Real VPN Connection Timer Management
    private val timerHandler = Handler(Looper.getMainLooper())
    private var startTime = 0L
    private var isTimerRunning = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            val millis = System.currentTimeMillis() - startTime
            val seconds = (millis / 1000).toInt() % 60
            val minutes = (millis / (1000 * 60)).toInt() % 60
            val hours = (millis / (1000 * 60 * 60)).toInt()

            binding.tvTimer.text = String.format("%02d : %02d : %02d", hours, minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private fun startVpnTimer() {
        if (!isTimerRunning) {
            startTime = System.currentTimeMillis()
            timerHandler.post(timerRunnable)
            isTimerRunning = true
        }
    }

    private fun stopVpnTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        isTimerRunning = false
        binding.tvTimer.text = "00 : 00 : 00"
    }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }

    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        // Setup ViewPager and TabLayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // Setup Navigation Drawer
        setupNavigationDrawer()

        // Event Listeners
        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }

        // Setup Developer Subscription Automatically
        setupDeveloperSubscription()

        // Setup UI Data & Observers
        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        // Auto download servers in background on app launch
        autoFetchServersOnStart()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    private fun setupDeveloperSubscription() {
        val currentSubs = MmkvManager.decodeSubscriptions()
        val exists = currentSubs.any { it.second.url == devSubUrl }
        if (!exists) {
            val subId = Utils.getUuid()
            val subItem = com.v2ray.ang.dto.SubscriptionItem().apply {
                remarks = "Official DTAC VIP Servers"
                url = devSubUrl
            }
            MmkvManager.encodeSubscription(subId, subItem)
        }
    }

    private fun autoFetchServersOnStart() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = mainViewModel.updateConfigViaSubAll()
                if (result.configCount > 0) {
                    withContext(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Offline mode: using cached servers", e)
            }
        }
    }

    // Manual Config Update trigger for UI button
    fun checkAndFetchDeveloperUpdates() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val previousCount = mainViewModel.serversCache.size
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)

            withContext(Dispatchers.Main) {
                hideLoading()
                if (result.successCount > 0) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()

                    val currentCount = mainViewModel.serversCache.size
                    if (currentCount > previousCount || result.configCount > 0) {
                        toast("Servers updated successfully!")
                    } else {
                        toast("No new updates from developer yet.")
                    }
                } else {
                    toast("Connection failed or no updates available.")
                }
            }
        }
    }

    private fun setupNavigationDrawer() {
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(isLoading = false, isRunning = isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1
        refreshGroupTabTitles(refreshAll = true)
    }

    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        val groupsToRefresh = if (refreshAll || mainViewModel.subscriptionId.isEmpty()) {
            groupPagerAdapter.groups
        } else {
            groupPagerAdapter.groups.filter { it.id == mainViewModel.subscriptionId }
        }

        groupsToRefresh.forEach { group ->
            if (group.id.isNotEmpty()) {
                val tabIndex = groupPagerAdapter.groups.indexOfFirst { it.id == group.id }
                if (tabIndex >= 0) {
                    val count = MmkvManager.decodeServerList(group.id).size
                    binding.tabGroup.getTabAt(tabIndex)?.text = "${group.remarks} ($count)"
                }
            }
        }
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }

        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            binding.fab.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
            binding.tvTestState.text = getString(R.string.connection_test_testing)
            binding.tvTestState.setTextColor(Color.parseColor("#00E5FF"))
            binding.progressBar.isVisible = true
            return
        }

        binding.progressBar.isVisible = false

        if (isRunning) {
            startVpnTimer()
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00FF87"))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.tvTestState.setTextColor(Color.parseColor("#00FF87"))
            binding.layoutTest.isFocusable = true
        } else {
            stopVpnTimer()
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF007F"))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.tvTestState.setTextColor(Color.parseColor("#FF5555"))
            binding.layoutTest.isFocusable = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.import_qrcode -> importQRcode()
            R.id.import_clipboard -> importClipboard()
            R.id.import_local -> importConfigLocal()
            R.id.import_manually_policy_group -> importManually(EConfigType.POLICYGROUP.value)
            R.id.import_manually_proxy_chain -> importManually(EConfigType.PROXYCHAIN.value)
            R.id.import_manually_vmess -> importManually(EConfigType.VMESS.value)
            R.id.import_manually_vless -> importManually(EConfigType.VLESS.value)
            R.id.import_manually_ss -> importManually(EConfigType.SHADOWSOCKS.value)
            R.id.import_manually_socks -> importManually(EConfigType.SOCKS.value)
            R.id.import_manually_http -> importManually(EConfigType.HTTP.value)
            R.id.import_manually_trojan -> importManually(EConfigType.TROJAN.value)
            R.id.import_manually_wireguard -> importManually(EConfigType.WIREGUARD.value)
            R.id.import_manually_hysteria2 -> importManually(EConfigType.HYSTERIA2.value)
            R.id.export_all -> { exportAll(); true }
            R.id.real_ping_all -> {
                toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                mainViewModel.testAllRealPing()
                true
            }
            R.id.service_restart -> { restartV2Ray(); true }
            R.id.del_all_config -> { delAllConfig(); true }
            R.id.del_duplicate_config -> { delDuplicateConfig(); true }
            R.id.del_invalid_config -> { delInvalidConfig(); true }
            R.id.sort_by_test_results -> { sortByTestResults(); true }
            R.id.sub_update -> { checkAndFetchDeveloperUpdates(); true }
            R.id.locate_selected_config -> { locateSelectedServer(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun importManually(createConfigType: Int): Boolean {
        val targetActivity = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN.value -> ServerProxyChainActivity::class.java
            else -> ServerActivity::class.java
        }

        val intent = Intent(this, targetActivity).apply {
            putExtra("subscriptionId", mainViewModel.subscriptionId)
            if (targetActivity == ServerActivity::class.java) {
                putExtra("createConfigType", createConfigType)
            }
        }
        startActivity(intent)
        return true
    }

    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    private fun importClipboard(): Boolean {
        return try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            false
        }
    }

    private fun importBatchConfig(server: String?) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            refreshGroupTabTitles()
                        }
                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    private fun importConfigLocal(): Boolean {
        return try {
            showFileChooser()
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            false
        }
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            withContext(Dispatchers.Main) {
                if (ret > 0) {
                    toast(getString(R.string.title_export_config_count, ret))
                } else {
                    toastError(R.string.toast_failure)
                }
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        showConfirmDialog(R.string.del_config_comfirm) {
            showLoading()
            lifecycleScope.launch(Dispatchers.IO) {
                val ret = mainViewModel.removeAllServer()
                withContext(Dispatchers.Main) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                    toast(getString(R.string.title_del_config_count, ret))
                    hideLoading()
                }
            }
        }
    }

    private fun delDuplicateConfig() {
        showConfirmDialog(R.string.del_config_comfirm) {
            showLoading()
            lifecycleScope.launch(Dispatchers.IO) {
                val ret = mainViewModel.removeDuplicateServer()
                withContext(Dispatchers.Main) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                    toast(getString(R.string.title_del_duplicate_config_count, ret))
                    hideLoading()
                }
            }
        }
    }

    private fun delInvalidConfig() {
        showConfirmDialog(R.string.del_invalid_config_comfirm) {
            showLoading()
            lifecycleScope.launch(Dispatchers.IO) {
                val ret = mainViewModel.removeInvalidServer()
                withContext(Dispatchers.Main) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                    toast(getString(R.string.title_del_config_count, ret))
                    hideLoading()
                }
            }
        }
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            withContext(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    private fun showFileChooser() {
        launchFileChooser { uri ->
            uri?.let { readContentFromUri(it) }
        }
    }

    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                importBatchConfig(input.bufferedReader().readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    private inline fun showConfirmDialog(messageResId: Int, crossinline onPositive: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(messageResId)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPositive() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        stopVpnTimer()
        tabMediator?.detach()
        super.onDestroy()
    }
}

