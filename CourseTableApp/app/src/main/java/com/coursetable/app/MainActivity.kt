package com.coursetable.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.coursetable.app.data.Store
import com.coursetable.app.engine.TermCalendar
import com.coursetable.app.model.Schedule
import com.coursetable.app.notify.DailyReminder
import com.coursetable.app.ui.DayFragment
import com.coursetable.app.ui.ImportFragment
import com.coursetable.app.ui.MonthFragment
import com.coursetable.app.ui.OverviewFragment
import com.coursetable.app.ui.SettingsFragment
import com.coursetable.app.ui.WeekFragment
import com.coursetable.app.util.CrashCatcher
import com.coursetable.app.util.CalendarSync
import com.coursetable.app.util.UpdateChecker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_DAY = "open_day"
        private const val PERM_NOTIFY = 500
    }

    lateinit var store: Store
    private var mainFragments: MutableMap<String, Fragment> = LinkedHashMap()
    private var currentTag = WeekFragment.TAG
    private var initNavFlag = false
    private var activeSubTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashCatcher.install(applicationContext)
        setContentView(R.layout.activity_main)
        store = Store(this)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = getString(R.string.app_name)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            if (initNavFlag) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.tab_week -> showMainTab(WeekFragment.TAG)
                R.id.tab_day -> { ensureMainFragment(DayFragment.TAG); showMainTab(DayFragment.TAG) }
                R.id.tab_month -> showMainTab(MonthFragment.TAG)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        if (savedInstanceState != null) {
            // 重建后丢弃被自动恢复的宿主内 Fragment，避免重复
            val mainTags = setOf(WeekFragment.TAG, DayFragment.TAG, MonthFragment.TAG)
            val ft = supportFragmentManager.beginTransaction()
            for (f in ArrayList(supportFragmentManager.fragments)) {
                if (f.isAdded || mainTags.contains(f.tag)) ft.remove(f)
            }
            ft.commitNowAllowingStateLoss()
        }
        if (savedInstanceState == null) {
            val openDay = intent.getStringExtra(EXTRA_OPEN_DAY)
            if (openDay != null) {
                runCatching { store.selectedDate = LocalDate.parse(openDay) }
            }
        }
        requestNotifPermission()
        DailyReminder.ensureChannel(this)
        DailyReminder.reschedule(this)

        // 恢复或进入默认周视图
        val fromNotif = intent.getStringExtra(EXTRA_OPEN_DAY) != null
        val tag = if (!fromNotif && savedInstanceState != null) {
            savedInstanceState.getString("tag") ?: WeekFragment.TAG
        } else if (fromNotif) {
            DayFragment.TAG
        } else {
            WeekFragment.TAG
        }
        initNavFlag = true
        showMainTab(tag)
        bottomNav.selectedItemId = when (tag) {
            DayFragment.TAG -> R.id.tab_day
            MonthFragment.TAG -> R.id.tab_month
            else -> R.id.tab_week
        }
        initNavFlag = false
        // 不弹“欢迎/导入”对话框（避免与课表界面重叠）；导入入口：周课表空白页中央按钮 + ☰ 菜单
        // 联网自检测更新（每日最多一次，仅在有新版本时提示）
        autoCheckUpdate()
        // 崩溃弹窗延后到首帧后再展示，避免与其它界面/弹窗叠加
        val crashText = CrashCatcher.readAndClear(this)
        if (crashText != null) {
            findViewById<View>(android.R.id.content).post {
                if (isFinishing || isDestroyed) return@post
                val max = crashText.length
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("上次启动崩溃（详情已供排查）")
                    .setMessage(crashText.take(6000) + (if (max > 6000) "\n…(截断，共 $max 字符)" else ""))
                    .setPositiveButton("知道了", null)
                    .setNegativeButton("复制到剪贴板") { _, _ ->
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashText))
                    }
                    .show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        if (bottomNav.visibility == android.view.View.GONE) {
            goBackToMain()
            return
        }
        super.onBackPressed()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERM_NOTIFY)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tag", currentTag)
        super.onSaveInstanceState(outState)
    }

    // ---------- navigation ----------

    private fun showMainTab(tag: String) {
        currentTag = tag
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        findViewById<BottomNavigationView>(R.id.bottom_nav).visibility = View.VISIBLE
        ensureMainFragment(tag)
        // 同步幂等挂载：每个主 Fragment 只 add 一次；未挂载的先同步提交，避免异步时序重复 add
        for ((t, f) in mainFragments) {
            if (!f.isAdded) {
                supportFragmentManager.beginTransaction()
                    .add(R.id.container, f, t)
                    .commitNowAllowingStateLoss()
            }
        }
        val ft = supportFragmentManager.beginTransaction()
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
        for ((t, f) in mainFragments) {
            if (f.isAdded) {
                if (t == tag) ft.show(f) else ft.hide(f)
            }
        }
        ft.commitAllowingStateLoss()
        // 刷新数据型界面
        val current = mainFragments[tag]
        if (currentTag == WeekFragment.TAG) (current as? WeekFragment)?.refreshData()
        if (currentTag == DayFragment.TAG) (current as? DayFragment)?.refreshData()
        if (currentTag == MonthFragment.TAG) (current as? MonthFragment)?.refreshData()
    }

    private fun ensureMainFragment(tag: String) {
        if (mainFragments.containsKey(tag)) return
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null) {
            mainFragments[tag] = existing
            return
        }
        val f = when (tag) {
            WeekFragment.TAG -> WeekFragment()
            DayFragment.TAG -> DayFragment()
            MonthFragment.TAG -> MonthFragment()
            else -> WeekFragment()
        }
        mainFragments[tag] = f
    }

    private fun openSub(f: Fragment, tag: String) {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<BottomNavigationView>(R.id.bottom_nav).visibility = View.GONE
        val fm = supportFragmentManager
        val ft = fm.beginTransaction()
        // 先移除仍在容器里的其它子页面/旧主页面，避免重叠残留
        activeSubTag?.let { old ->
            if (old != tag) fm.findFragmentByTag(old)?.let { if (it.isAdded) ft.remove(it) }
        }
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
        ft.replace(R.id.container, f, tag)
        ft.commitAllowingStateLoss()
        activeSubTag = tag
    }

    fun openOverview() = openSub(OverviewFragment(), OverviewFragment.TAG)
    fun openImport() = openSub(ImportFragment(), ImportFragment.TAG)
    fun openSettings() = openSub(SettingsFragment(), SettingsFragment.TAG)

    fun goBackToMain() {
        // 退出子页面：先真正移除，避免与主页面重叠
        val fm = supportFragmentManager
        activeSubTag?.let { old ->
            fm.findFragmentByTag(old)?.let { sub ->
                if (sub.isAdded) {
                    fm.beginTransaction().remove(sub).commitNowAllowingStateLoss()
                }
            }
        }
        activeSubTag = null
        showMainTab(currentTag)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.visibility = View.VISIBLE
        bottomNav.selectedItemId = when (currentTag) {
            DayFragment.TAG -> R.id.tab_day
            MonthFragment.TAG -> R.id.tab_month
            else -> R.id.tab_week
        }
    }

    fun openDay(date: LocalDate) {
        store.selectedDate = date
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.visibility = android.view.View.VISIBLE
        bottomNav.selectedItemId = R.id.tab_day
        ensureMainFragment(DayFragment.TAG)
        showMainTab(DayFragment.TAG)
    }

    // ---------- data actions ----------

    fun currentSchedule(): Schedule? = store.schedule()

    fun currentCalendar(s: Schedule? = currentSchedule()): TermCalendar =
        if (s != null) TermCalendar(s.week1Monday, s.weekCount)
        else TermCalendar(store.termStart, store.defaultTermWeeks)

    fun onScheduleImported(s: Schedule, sourceName: String) {
        store.saveSchedule(s, sourceName)
        if (store.termStart != s.week1Monday) store.termStart = s.week1Monday
        DailyReminder.reschedule(this)
        // 日历模式下重新同步：先彻底清掉旧事件（含标记兜底），再写入新课表
        if (store.notifyEnabled && store.notifyVia == 1) {
            Thread {
                val msg = runCatching {
                    CalendarSync.resync(this, s, store.classLeadMin)
                    "已同步 ${CalendarSync.syncedEventCount(this)} 条课程事件到系统日历"
                }.getOrElse { e -> "日历同步失败：${e.message}" }
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }.start()
        }
        refreshAll()
        Toast.makeText(this, "导入成功：${s.entries.size} 条排课", Toast.LENGTH_SHORT).show()
        goBackToMain()
    }

    fun onScheduleCleared() {
        store.clearSchedule()
        DailyReminder.cancel(this)
        Thread { CalendarSync.removeAll(this) }.start()   // 课表清除时一并清掉系统日历里的同步事件
        refreshAll()
        Toast.makeText(this, "已清除课表", Toast.LENGTH_SHORT).show()
        goBackToMain()
    }

    fun refreshAll() {
        for ((t, f) in mainFragments) {
            if (!f.isAdded) continue
            when (t) {
                WeekFragment.TAG -> (f as WeekFragment).refreshData()
                DayFragment.TAG -> (f as DayFragment).refreshData()
                MonthFragment.TAG -> (f as MonthFragment).refreshData()
            }
        }
    }

    /** 诊断信息：把关键状态拼成文本，供用户复制回报。 */
    private fun autoCheckUpdate() {
        val today = java.time.LocalDate.now().toString()
        if (store.lastAutoUpdateDay == today) return
        store.lastAutoUpdateDay = today
        UpdateChecker.checkAsync(this) { info ->
            if (info == null || !info.isNewer()) return@checkAsync
            MaterialAlertDialogBuilder(this)
                .setTitle("发现新版本 v${info.versionName}")
                .setMessage(info.promptText())
                .setPositiveButton("下载更新") { _, _ ->
                    UpdateChecker.downloadAndInstall(this, info)
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    /** 诊断信息：把关键状态拼成文本，供用户复制回报。 */
    private fun showDiag() {
        val sb = StringBuilder()
        runCatching {
            sb.append("App v").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append(")\n")
            val dm = resources.displayMetrics
            val cfg = resources.configuration
            sb.append("屏幕: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                .append("px, density ").append(dm.density)
                .append(", 系统字体缩放 fontScale=").append(cfg.fontScale)
                .append(", scaledDensity=").append(dm.scaledDensity).append('\n')
            val s = store.schedule()
            sb.append("课表: ").append(if (s == null) "无(未导入)" else "有")
            if (s != null) {
                sb.append(" 条目=").append(s.entries.size)
                    .append(" 周数=").append(s.weekCount)
                    .append(" 起点=").append(s.week1Monday)
                    .append(" 标题行数=").append(s.titleLines.size)
                sb.append("\n时间样本(名称|星期|节次|跨节|时间):\n")
                val sorted = s.entries.sortedWith(compareBy({ it.day }, { it.startSlot }, { it.endSlot() }, { it.name }))
                for (e in sorted.take(8)) {
                    val (label, range) = com.coursetable.app.engine.TimeText.slotLabelAndRange(e.startSlot, e.slotSpan)
                    sb.append("  ").append(e.name.take(8)).append(" | 周").append(e.day + 1)
                        .append(" | 节").append(e.startSlot).append("-").append(e.endSlot())
                        .append(" | ").append(label).append(" ").append(range).append('\n')
                }
                sb.append("时间轨样本: ")
                for (slot in intArrayOf(1, 3, 5, 7, 9)) {
                    sb.append(com.coursetable.app.engine.TimeText.timeRange(slot, slot + 1)).append("  ")
                }
                sb.append('\n')
            }
            sb.append("来源: ").append(store.sourceName() ?: "-")
            sb.append("\n设置: 通知开关=").append(store.notifyEnabled)
                .append(" 时间=").append(store.notifyTime.first).append(':').append(store.notifyTime.second)
                .append(" 显示周末=").append(store.showWeekend)
                .append(" 学期起点=").append(store.termStart)
            sb.append("\n选中日期: ").append(store.selectedDate)
            sb.append("\n当前Tab: ").append(currentTag)
            sb.append("\nFragment: ")
            for ((t, f) in mainFragments) {
                sb.append(t).append('=').append(if (f.isAdded) "已加" else "未加").append(' ')
            }
            sb.append("\n容器子View: ")
            val holder = findViewById<android.view.ViewGroup>(R.id.container)
            for (i in 0 until holder.childCount) {
                val child = holder.getChildAt(i)
                sb.append(child.javaClass.simpleName).append('[').append(child.width).append('x').append(child.height).append("] ")
            }
            sb.append("\n底部栏可见: ").append(findViewById<View>(R.id.bottom_nav).visibility == View.VISIBLE)
            val grid = findViewById<com.coursetable.app.ui.WeekGridView?>(com.coursetable.app.R.id.weekGrid)
            if (grid != null) {
                sb.append("\n周网格: w=").append(grid.width).append(" h=").append(grid.height)
                    .append(" shown=").append(grid.isShown)
            }
        }
        val text = sb.toString()
        MaterialAlertDialogBuilder(this)
            .setTitle("诊断信息（复制发回即可）")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .setNegativeButton("复制") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("diag", text))
            }
            .show()
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                goBackToMain()
                true
            }
            R.id.action_overview -> { openOverview(); true }
            R.id.action_import -> { openImport(); true }
            R.id.action_settings -> { openSettings(); true }
            R.id.action_about -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.pref_about)
                    .setMessage(R.string.time_rule)
                    .setPositiveButton("好", null)
                    .show()
                true
            }
            R.id.action_diag -> {
                showDiag()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
