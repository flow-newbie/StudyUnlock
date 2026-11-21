package com.example.studyunlock

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = StudyStore.get(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Accent, onPrimary = Color.White, background = Paper, onBackground = Ink,
                surface = Color(0xfffafaff), onSurface = Ink, onSurfaceVariant = Muted, outline = Line
            )) { StudyApp(store) }
        }
    }

    @Composable
    private fun StudyApp(store: StudyStore) {
        val revision by store.changes.collectAsState()
        val settlement by store.settlement.collectAsState()
        var balancePosition by remember { mutableStateOf<Offset?>(null) }
        val connected by StudyMonitor.connected.collectAsState()
        var usage by remember { mutableStateOf(hasUsageAccess(this)) }
        var accessibility by remember { mutableStateOf(monitorEnabled(this)) }
        var page by rememberSaveable { mutableIntStateOf(0) }
        var dialog by rememberSaveable { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        val shortcuts = remember { getSharedPreferences("query_shortcuts", MODE_PRIVATE) }
        var dictionary by remember { mutableStateOf(shortcuts.getString("dictionary", null)) }
        var ai by remember { mutableStateOf(shortcuts.getString("ai", null)) }
        fun openShortcut(key: String, app: String?) {
            if (app != null) {
                val launched = runCatching {
                    val intent = packageManager.getLaunchIntentForPackage(app) ?: return@runCatching false
                    startActivity(intent)
                    true
                }.getOrDefault(false)
                if (launched) return
                message = "应用无法打开，请重新选择"
            }
            dialog = "shortcut/$key"
        }
        val scope = rememberCoroutineScope()
        val wallpaperFile = remember { WallpaperFile(this) }
        var wallpaper by remember { mutableStateOf<Bitmap?>(null) }
        var candidate by remember { mutableStateOf<Bitmap?>(null) }
        var wallpaperBusy by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            wallpaper = withContext(Dispatchers.IO) { wallpaperFile.load() }
            wallpaperBusy = false
        }
        val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                wallpaperBusy = true
                scope.launch {
                    candidate = withContext(Dispatchers.IO) { runCatching { wallpaperFile.decode(uri) } }.getOrNull()
                    if (candidate == null) message = "无法打开这张图片"
                    wallpaperBusy = false
                }
            }
        }
        val refresh = {
            usage = hasUsageAccess(this)
            accessibility = monitorEnabled(this)
            if (!usage) store.incomplete("使用情况访问未授权")
            if (!accessibility) store.incomplete("无障碍服务未开启")
            if (!StudyMonitor.connected.value) {
                store.markMonitoringGap(getSharedPreferences("monitor", MODE_PRIVATE).getLong("last", 0L))
            }
            store.settle()
        }
        DisposableEffect(Unit) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                val snapshot = store.export()
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        requireNotNull(contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8)
                            .use { it.write(snapshot) }
                    } }
                    message = if (result.isSuccess) "已导出备份" else "无法写入备份文件"
                }
            }
        }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                        val bytes = input.readNBytes(8 * 1024 * 1024 + 1)
                        require(bytes.size <= 8 * 1024 * 1024)
                        String(bytes, Charsets.UTF_8)
                    }
                } }
                message = if (runCatching { store.import(result.getOrThrow()); refresh() }.isSuccess)
                    "已恢复备份" else "无法导入，请选择有效的 StudyUnlock 备份"
            }
        }
        LaunchedEffect(message) {
            if (message.isNotEmpty()) {
                kotlinx.coroutines.delay(3500)
                message = ""
            }
        }

        var date by remember { mutableStateOf(store.date()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                date = store.date()
            }
        }
        val day = remember(revision, date) { store.today()?.let { JSONObject(it.toString()) } }
        val template = remember(revision) { store.settings()?.let { JSONObject(it.toString()) } }
        val balance = remember(revision) { store.balance() }
        val ready = usage && accessibility && connected
        val titles = listOf("今日", "记录", "设置")
        Box(Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to if (page == 0) Color(0xfff3dfe9) else Paper,
                .36f to Color(0xffe7e8fa),
                .68f to Color(0xffe7effa),
                1f to Color(0xfffaf9ff)
            )
        )) {
            wallpaper?.let {
                Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(
                    if (page == 0) androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Paper.copy(alpha = .48f), .35f to Paper.copy(alpha = .54f), .74f to Paper.copy(alpha = .89f)
                    ) else androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Paper.copy(alpha = .66f), Paper.copy(alpha = .66f)))
                ))
            }
        CompositionLocalProvider(WallpaperActive provides (wallpaper != null)) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { Box(Modifier.navigationBarsPadding().imePadding()) {
                CapsuleNav(page, openAi = { openShortcut("ai", ai) }) { page = it }
            } },
            snackbarHost = {
                if (message.isNotEmpty()) Snackbar(modifier = Modifier.padding(horizontal = 24.dp)) { Text(message) }
            }
        ) { inset ->
            Column(Modifier.padding(inset).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (page != 0) Text(titles[page], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Ink)
                when (page) {
                    0 -> {
                        TodayOverview(
                            done = (if (day?.optString("status") == "unlocked") 1 else 0) +
                                (day?.optJSONArray("tasks")?.let { tasks ->
                                    (0 until tasks.length()).count { tasks.optJSONObject(it)?.optString("status") == "unlocked" }
                                } ?: 0),
                            balance = balance,
                            onBalancePosition = { balancePosition = it },
                            openDictionary = { openShortcut("dictionary", dictionary) },
                            openPoints = { page = 1 }
                        )
                        if (template == null) Glass {
                            Section("学习目标")
                            Button(onClick = { dialog = "rules" }) { Text("设置目标") }
                        } else {
                            val target = day?.getInt("minutes") ?: template.getInt("minutes")
                            val seconds = day?.getInt("seconds") ?: 0
                            val completion = day?.getInt("completion") ?: 0
                            val unlocked = day?.optString("status") == "unlocked"
                            val ended = day?.optBoolean("endedEarly") == true
                            Glass {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Section(if (ended) "已结束学习" else if (unlocked) "任务已完成" else "学习时长")
                                    if (day?.optBoolean("practice") == true) Text("试用日", color = Muted)
                                }
                                Text(formatTime(seconds), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                                LinearProgressIndicator(progress = { (seconds / (target * 60f)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).padding(end = 1.dp))
                                Entry("目标", "$target 分钟")
                                Entry("完成度", "$completion%")
                                if (day?.isLearning() == true) {
                                    val limits = day.passLimits()
                                    Entry("今日紧急放行", "剩 ${(limits.daily - day.optInt("passes")).coerceAtLeast(0)} 次")
                                    if (store.remainingPass(day) > 0) Entry("本次可再打开", "${limits.opens - day.optInt("opensUsed")} 次")
                                    Button(onClick = { dialog = "completion" }) { Text("更新完成度") }
                                    if (getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure) {
                                        TextButton(onClick = { startActivity(StudyExitActivity.intent(this@MainActivity, end = true)) }) {
                                            Text("结束本次学习")
                                        }
                                    }
                                }
                                if (day?.isLearning() != true) {
                                    Button(onClick = { dialog = "start" }, enabled = ready) { Text("开始新任务") }
                                }
                                if (day?.optBoolean("incomplete") == true && !unlocked) Text("今日记录不完整", color = Muted)
                            }
                        }
                        if (template != null) {
                            if (ready) Text("监测正常", color = Muted)
                            else OutlinedButton(onClick = { page = 2 }) {
                                Text(when {
                                    !usage -> "开启使用情况访问"
                                    !accessibility -> "开启无障碍服务"
                                    else -> "无障碍服务尚未连接"
                                })
                            }
                        }
                    }
                    1 -> {
                        val rows = store.days().keys().asSequence().sortedDescending().take(30).toList()
                        Glass {
                            Section("学习记录")
                            if (rows.isEmpty()) Text("暂无记录", color = Muted)
                            rows.forEach { dayKey ->
                                val item = store.days().getJSONObject(dayKey)
                                val status = when (item.getString("status")) {
                                    "unlocked" -> "已达标"
                                    "failed" -> "未完成"
                                    "incomplete" -> "记录不完整"
                                    "practice" -> "试用"
                                    else -> "进行中"
                                }
                                TextButton(onClick = { dialog = "day/$dayKey" }, modifier = Modifier.fillMaxWidth()) {
                                    Entry(dayKey, "${formatTime(item.getInt("seconds"))} · $status")
                                }
                            }
                        }
                        Glass {
                            Section("积分流水")
                            Entry("当前余额", balance.toString())
                            val ledger = store.ledger()
                            for (index in ledger.length() - 1 downTo (ledger.length() - 15).coerceAtLeast(0)) {
                                val row = ledger.getJSONObject(index)
                                val delta = row.getInt("delta")
                                Entry("${row.getString("date")}  ${row.getString("note")}", if (delta > 0) "+$delta" else "$delta")
                            }
                            TextButton(onClick = { dialog = "redeem" }, enabled = balance > 0) { Text("核销积分") }
                            TextButton(onClick = { dialog = "adjust" }, enabled = template != null) { Text("纠正积分") }
                        }
                        Glass {
                            Section("异常记录")
                            val events = store.events()
                            if (events.length() == 0) Text("暂无异常", color = Muted)
                            for (index in events.length() - 1 downTo (events.length() - 10).coerceAtLeast(0)) {
                                val event = events.getJSONObject(index)
                                Entry(event.getString("note"), store.date(event.optLong("time")).toString())
                            }
                        }
                    }
                    else -> {
                        Glass {
                            Section("查询入口")
                            ShortcutRow("查词", dictionary) { dialog = "shortcut/dictionary" }
                            ShortcutRow("AI 查询", ai) { dialog = "shortcut/ai" }
                        }
                        Glass(beam = true) {
                            Section("权限管理")
                            PermissionRow("使用情况访问", usage, Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            PermissionRow("无障碍服务", accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            if (accessibility && !connected) Text("等待系统连接服务", color = Muted)
                            if (ready) Text("监测正常", color = Muted)
                        }
                        Glass {
                            Section("学习规则")
                            if (template != null) {
                                Entry("每日时长", "${template.getInt("minutes")} 分钟")
                                Entry("奖惩", "+${template.getInt("reward")} / −${template.getInt("penalty")}")
                                val effective = template.getString("effective")
                                if (effective > store.date().toString()) Entry("生效日期", effective)
                            }
                            TextButton(onClick = { dialog = "rules" }) { Text(if (template == null) "设置目标" else "编辑规则") }
                        }
                        Glass {
                            Section("壁纸")
                            wallpaper?.let { WallpaperPreview(it) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pickWallpaper.launch("image/*") }, enabled = !wallpaperBusy) {
                                    Text(if (wallpaper == null) "选择照片" else "更换照片")
                                }
                                if (wallpaper != null) TextButton(enabled = !wallpaperBusy, onClick = {
                                    wallpaperBusy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { runCatching { wallpaperFile.reset() } }
                                        if (result.isSuccess) wallpaper = null else message = "无法恢复默认壁纸"
                                        wallpaperBusy = false
                                    }
                                }) { Text("恢复默认") }
                            }
                        }
                        Glass {
                            Section("备份")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { export.launch("StudyUnlock-${store.date()}.json") }) { Text("导出") }
                                OutlinedButton(onClick = { dialog = "restore" }) { Text("导入") }
                            }
                        }
                    }
                }
            }
        }
        }
        SettlementEffect(settlement, balancePosition.takeIf { page == 0 }) {
            if (store.settlement.value === it) store.settlement.value = null
        }
        }

        candidate?.let { photo ->
            AlertDialog(
                onDismissRequest = { if (!wallpaperBusy) candidate = null },
                title = { Text("预览壁纸") },
                text = { WallpaperPreview(photo, ratio = .7f) },
                confirmButton = { TextButton(enabled = !wallpaperBusy, onClick = {
                    wallpaperBusy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { wallpaperFile.save(photo) } }
                        if (result.isSuccess) {
                            wallpaper = photo
                            candidate = null
                        } else message = "无法保存壁纸"
                        wallpaperBusy = false
                    }
                }) { Text("应用") } },
                dismissButton = { TextButton(enabled = !wallpaperBusy, onClick = { candidate = null }) { Text("取消") } }
            )
        }

        when {
            dialog.startsWith("shortcut/") -> {
                val key = dialog.substringAfter('/')
                val isAi = key == "ai"
                ShortcutPicker(
                    title = if (isAi) "选择 AI 应用" else "选择查词应用",
                    selected = if (isAi) ai else dictionary,
                    close = { dialog = "" },
                    choose = { app ->
                        shortcuts.edit().putString(key, app).apply()
                        if (isAi) ai = app else dictionary = app
                        dialog = ""
                    }
                )
            }
            dialog == "rules" -> RulesDialog(template, { dialog = "" }) { minutes, reward, penalty, initial, study, entertainment, limits ->
                store.saveSettings(minutes, reward, penalty, initial, study, entertainment, limits)
                dialog = ""
                page = 0
            }
            dialog == "start" -> NumberDialog("开始新任务", "学习时长（分钟）",
                template?.optInt("minutes")?.toString().orEmpty(), 1..1440, false, { dialog = "" }) { value, _ ->
                if (ready) store.startTask(value)
                dialog = ""
            }
            dialog == "completion" -> NumberDialog("完成度", "完成度（0–100%）", day?.optInt("completion")?.toString().orEmpty(),
                0..100, false, { dialog = "" }) { value, _ ->
                store.changeCompletion(value, ready)
                dialog = ""
            }
            dialog == "redeem" || dialog == "adjust" -> {
                val redeem = dialog == "redeem"
                NumberDialog(if (redeem) "核销积分" else "纠正积分", if (redeem) "扣除积分" else "积分变化",
                    "", if (redeem) 1..balance else -balance..(1000 - balance), true, { dialog = "" }, nonzero = true) { value, note ->
                    store.adjust(if (redeem) -value else value, note)
                    dialog = ""
                }
            }
            dialog == "restore" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("导入备份") },
                text = { Text("导入会覆盖当前规则、记录和积分。") },
                confirmButton = { TextButton(onClick = { dialog = ""; import.launch(arrayOf("application/json", "text/plain")) }) { Text("选择文件") } },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("取消") } })
            dialog.startsWith("day/") -> {
                val key = dialog.substringAfter('/')
                val item = store.days().optJSONObject(key)
                AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(key) },
                    text = {
                        Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item != null) {
                                Entry("完成度", "${item.getInt("completion")}%")
                                item.optJSONArray("tasks")?.let { tasks ->
                                    Section("同日已结束任务")
                                    for (index in tasks.length() - 1 downTo 0) {
                                        val previous = tasks.optJSONObject(index) ?: continue
                                        Entry(if (previous.optString("status") == "unlocked") "已完成" else "已结束",
                                            formatTime(previous.optInt("seconds")))
                                    }
                                }
                                for ((label, field) in listOf("学习" to "studyUsage", "娱乐" to "usage")) {
                                    Section(label)
                                    val stats = item.optJSONObject(field)
                                    if (stats == null) Text("暂无记录", color = Muted)
                                    else for (id in stats.keys()) Entry(appName(id), formatTime(stats.getInt(id)))
                                }
                            }
                        }
                    }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("关闭") } })
            }
        }
    }

    private fun appName(id: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(id, 0)).toString()
    }.getOrDefault(id)

    @Composable
    private fun ShortcutRow(label: String, app: String?, choose: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Ink)
            TextButton(onClick = choose) { Text(app?.let(::appName) ?: "选择应用", maxLines = 1) }
        }
    }

    @Composable
    private fun ShortcutPicker(title: String, selected: String?, close: () -> Unit, choose: (String?) -> Unit) {
        val apps = remember {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
                .filterNot { it.first == packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }
        var query by rememberSaveable { mutableStateOf("") }
        val matches = apps.filter { (id, label) -> label.contains(query, true) || id.contains(query, true) }
        AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("搜索应用") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 310.dp)) {
                    items(matches, key = { it.first }) { (id, label) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { choose(id) },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = id == selected, onClick = null, Modifier.padding(end = 12.dp))
                            Text(label, color = Ink)
                        }
                    }
                }
                if (matches.isEmpty()) Text("没有匹配的应用", Modifier.padding(top = 12.dp), color = Muted)
            }
        }, confirmButton = { TextButton(onClick = close) { Text("关闭") } },
            dismissButton = if (selected != null) {
                { TextButton(onClick = { choose(null) }) { Text("清除绑定") } }
            } else null)
    }

    @Composable
    private fun PermissionRow(name: String, granted: Boolean, action: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f))
            TextButton(onClick = { startActivity(Intent(action)) }) { Text(if (granted) "已开启" else "开启") }
        }
    }

    @Composable
    private fun RulesDialog(template: JSONObject?, close: () -> Unit,
        save: (Int, Int, Int, Int, Set<String>, Set<String>, PassLimits) -> Unit) {
        var minutes by rememberSaveable { mutableStateOf(template?.getInt("minutes")?.toString() ?: "30") }
        var reward by rememberSaveable { mutableStateOf(template?.getInt("reward")?.toString() ?: "10") }
        var penalty by rememberSaveable { mutableStateOf(template?.getInt("penalty")?.toString() ?: "5") }
        var initial by rememberSaveable { mutableStateOf(if (template == null) "" else "0") }
        val defaultLimits = template?.passLimits() ?: PassLimits()
        var passMinutes by rememberSaveable { mutableStateOf(defaultLimits.minutes.toString()) }
        var passDaily by rememberSaveable { mutableStateOf(defaultLimits.daily.toString()) }
        var passOpens by rememberSaveable { mutableStateOf(defaultLimits.opens.toString()) }
        var study by rememberSaveable { mutableStateOf(ArrayList(template?.packages("study").orEmpty())) }
        var entertainment by rememberSaveable { mutableStateOf(ArrayList(template?.packages("entertainment").orEmpty())) }
        var picker by rememberSaveable { mutableStateOf("") }
        val valid = minutes.toIntOrNull() in 1..1440 && listOf(reward, penalty, initial).all { it.toIntOrNull() in 0..1000 } &&
            study.isNotEmpty() && entertainment.isNotEmpty() && study.intersect(entertainment.toSet()).isEmpty() &&
            passMinutes.toIntOrNull() in 1..60 && passDaily.toIntOrNull() in 0..20 && passOpens.toIntOrNull() in 1..20
        if (picker.isNotEmpty()) {
            val pickingStudy = picker == "学习应用"
            AppPicker(picker, if (pickingStudy) study else entertainment, if (pickingStudy) entertainment else study,
                { picker = "" }) {
                if (pickingStudy) study = ArrayList(it) else entertainment = ArrayList(it)
                picker = ""
            }
        } else AlertDialog(onDismissRequest = close, title = { Text(if (template == null) "设置目标" else "编辑规则") },
            text = {
                Column(Modifier.heightIn(max = 425.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Numeric("每日目标（分钟）", minutes) { minutes = it }
                    Numeric("奖励积分", reward) { reward = it }
                    Numeric("惩罚积分", penalty) { penalty = it }
                    if (template == null) Numeric("初始积分（0–1000）", initial) { initial = it }
                    OutlinedButton(onClick = { picker = "学习应用" }, Modifier.fillMaxWidth()) {
                        Text("学习应用 · ${study.size}")
                    }
                    OutlinedButton(onClick = { picker = "娱乐应用" }, Modifier.fillMaxWidth()) {
                        Text("娱乐应用 · ${entertainment.size}")
                    }
                    Section("紧急放行")
                    Numeric("每次放行分钟数（1–60）", passMinutes) { passMinutes = it }
                    Numeric("每日放行次数（0–20）", passDaily) { passDaily = it }
                    Numeric("每次最多打开娱乐 App（1–20 次）", passOpens) { passOpens = it }
                }
            },
            confirmButton = { TextButton(enabled = valid, onClick = {
                save(minutes.toInt(), reward.toInt(), penalty.toInt(), initial.toInt(), study.toSet(), entertainment.toSet(),
                    PassLimits(passMinutes.toInt(), passDaily.toInt(), passOpens.toInt()))
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = close) { Text("取消") } })
    }

    @Composable
    private fun AppPicker(title: String, initial: List<String>, unavailable: List<String>, close: () -> Unit,
        save: (List<String>) -> Unit) {
        val apps = remember {
            val protected = protectedApps(this)
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
                .filterNot { it.first in protected }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }
        var selected by rememberSaveable { mutableStateOf(ArrayList(initial)) }
        var query by rememberSaveable { mutableStateOf("") }
        val visible = apps.filter { (id, name) -> id !in unavailable &&
            (name.contains(query, true) || id.contains(query, true)) }
        val visibleIds = visible.map { it.first }.toSet()
        val selectedIds = selected.toSet()
        AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索应用") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !selectedIds.containsAll(visibleIds),
                        onClick = { selected = ArrayList(selectedIds + visibleIds) }) {
                        Text(if (query.isBlank()) "全选" else "全选结果")
                    }
                    TextButton(enabled = visibleIds.any { it in selectedIds },
                        onClick = { selected = ArrayList(selectedIds - visibleIds) }) {
                        Text(if (query.isBlank()) "取消全选" else "取消选中结果")
                    }
                }
                LazyColumn(Modifier.heightIn(max = 310.dp)) {
                    items(visible, key = { it.first }) { (id, name) ->
                        val checked = id in selectedIds
                        Row(Modifier.fillMaxWidth().clickable {
                            selected = ArrayList(if (checked) selected - id else selected + id)
                        }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                            Text(name, color = Ink)
                        }
                    }
                }
                if (visible.isEmpty()) Text("没有匹配的应用", Modifier.padding(top = 12.dp), color = Muted)
            }
        }, confirmButton = { TextButton(onClick = { save(selected) }) { Text("确定") } },
            dismissButton = { TextButton(onClick = close) { Text("取消") } })
    }
}

@Composable
private fun TodayOverview(
    done: Int,
    balance: Int,
    onBalancePosition: (Offset) -> Unit,
    openDictionary: () -> Unit,
    openPoints: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).height(52.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = .76f)).border(1.dp, Color.White, CircleShape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = openDictionary)
            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Canvas(Modifier.size(18.dp)) {
                val radius = size.width * .32f
                val lens = Offset(radius + 1.dp.toPx(), radius + 1.dp.toPx())
                val width = 1.7.dp.toPx()
                drawCircle(Accent, radius, lens, style = androidx.compose.ui.graphics.drawscope.Stroke(width))
                drawLine(Accent, lens + Offset(radius * .72f, radius * .72f),
                    Offset(size.width - width / 2, size.height - width / 2), width,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Text("查词", color = Ink, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.height(52.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = .76f)).border(1.dp, Color.White, CircleShape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = openPoints)
            .padding(horizontal = 13.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("积分", color = Muted)
            Text(balance.toString(), Modifier.onGloballyPositioned { coordinates ->
                onBalancePosition(coordinates.positionInRoot() +
                    Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
            }, color = Accent, fontWeight = FontWeight.Bold)
        }
    }
    Column(Modifier.padding(start = 6.dp, top = 26.dp, bottom = 22.dp)) {
        Text(done.toString(), color = Ink, fontSize = 68.sp, lineHeight = 74.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
        Text("今日完成任务", color = Muted, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Ink)
}

@Composable
private fun Entry(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Ink)
        Text(value, color = Muted)
    }
}

@Composable
private fun Numeric(label: String, value: String, update: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 6 && (it.isEmpty() || it == "-" || it.toIntOrNull() != null)) update(it) },
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NumberDialog(title: String, label: String, initial: String, range: IntRange,
    noteRequired: Boolean, close: () -> Unit, nonzero: Boolean = false, save: (Int, String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var note by rememberSaveable { mutableStateOf("") }
    val number = value.toIntOrNull()
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Numeric(label, value) { value = it }
                if (noteRequired) OutlinedTextField(note, { note = it.take(200) }, label = { Text("原因") })
            }
        },
        confirmButton = {
            TextButton(enabled = number in range && (!nonzero || number != 0) && (!noteRequired || note.isNotBlank()),
                onClick = { save(requireNotNull(number), note) }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
