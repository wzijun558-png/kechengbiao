# 课程表（CourseTable）— Android 源码工程

基于 `课表.xls`（2026-2027 学年第 1 学期 · 王梓俊）分析结果实现的课程表 App。
本目录为**可直接编译成 APK** 的 Android Studio 工程（Kotlin + Material 3 + XML 视图，无第三方运行时依赖）。

## 功能 → 需求对照

| 需求 | 实现 |
|---|---|
| UI 类似市面课程表 App | 色块卡片周课表、日程列表、月历、课程总览；底部导航 + 顶栏菜单；Material 3 |
| 按日期排 18 周日程 | 学期第 1 周周一默认 **2026-08-31**（用户确认），任意日期自动换算周次；设置内可改起点 |
| 月 / 周 / 日 三层显示 | 底部三页签切换：周课表（1-10 节 × 天网格，已取消节次·时间显示）、日程（单日卡片）、月视图（学期逐月日历 + 当天课程圆点）；「全部课程」总览按课程聚合去重 |
| 每天发送当天课程通知 | 通知**锁屏可见**；设置里任选「**时钟（本机闹钟）**」或「**手机日历（同步系统日历）**」两种提醒方式；时钟方式支持每日汇总/每节课前（默认 07:30 / 课前 10 分钟，可改；AlarmManager + 开机/升级重排 + Android 13+ 通知权限） |
| 课表内容详尽呈现、避免无意义重复 | ① 源文件中把同一课程文本逐小节重复的行自动竖向合并为一个课程块（`slotSpan`）；② 每个格子多课程块（含原文 `raw`）完整保留；③ 周视图/日视图/总览三处互为补充 |
| 导入同类型不同内容的课表文件 | 仅支持 **`.xls` / `.xlsx`** 两种格式：内置原生 BIFF8(.xls/OLE2) 与 OOXML(.xlsx) 两个解析器，无任何第三方运行时依赖；自动识别 横排/竖排(转置) 表头、单/双周写法、教师【周次】、教室等；同一工作簿取排课最多的表 |
| 不内置个人信息 | App 无任何硬编码个人数据；首启为空，姓名/年级/院系/专业等随导入文件进入页眉展示 |
| 联网自检测更新 | 启动时每日一次 + 设置内「检查更新」；更新清单为远端 JSON（`UpdateChecker.DEFAULT_UPDATE_URL`）。**当前版本 1.0（versionCode 1）** |

## 目录结构

```
app/src/main/java/com/coursetable/app/
  model/         Schedule/CourseEntry/TimePair 数据模型（org.json 编解码）
  parser/        XlsReader(原生 .xls) · XlsxReader(.xlsx) · GridScheduleParser(通用识别) · ImportEngine
  engine/        WeekPattern(周次表达式) · TermCalendar(日期↔周次)
  data/          Store(SharedPreferences 持久化)
  notify/        DailyReminder + 每日广播 + 开机重排
  ui/            WeekGridView/MonthCalendarView(自绘) + 六个 Fragment
app/src/test/    纯 JVM 单测（xls/xlsx 解析一致性/周次/日期），样例文件在 test/resources/
samples(上级目录) 课表.xls 原文与内容等价的 xlsx（均为测试/预览数据，不随 APK 安装）
```

## 构建 APK

### 方式一：Android Studio（推荐）
1. 安装 Android Studio（Ladybug 或更新，自带 JDK 17+）。
2. `File → Open` 选择本目录 `CourseTableApp`（首次同步会自动下载 Gradle 8.7 与依赖，需联网）。
3. 连接手机/模拟器后点 ▶ Run；或 `Build → Build APK(s)` 得到 `app/build/outputs/apk/debug/app-debug.apk`。

### 方式二：命令行
```bash
# 需要 JDK 17+ 与 Android SDK
sdkmanager --install "platforms;android-34" "build-tools;34.0.0"
gradle wrapper --gradle-version 8.7   # 或由 Android Studio 生成 wrapper
./gradlew :app:assembleDebug
```

### 已在本机构建（产物）
本机已用 JDK 17 + Android SDK + Gradle 8.7 成功编译并跑通全部 15 项 JVM 单测；
可安装包：仓库根目录 `课程表App-debug.apk`（≈6.69 MB，Android Debug 签名，版本 1.0）。
构建环境位于 `../buildenv/`（jdk / android-sdk / gradle），可随时用
`gradle -p . :app:assembleDebug` 复现。构建脚本在 `settings.gradle.kts` 中加入了阿里云镜像，离线/公网环境自动回退官方仓库。

### 运行测试
```bash
./gradlew :app:testDebugUnitTest
```
覆盖：周次表达式、日期→周次边界、真实 `.xls`/`.xlsx` 解析一致性（21 条排课）、劳动课第 17 周合并、单双周交替、第 16 周单小节补课等。

## 使用说明
1. 首次启动为空（无任何内置示例）→ 「☰ → 导入课表」选择文件（仅 .xls / .xlsx）。
2. 解析成功后预览（表名、标题行、排课数、周数、异常提示），确认后替换课表。
3. 「设置」：学期起点（默认 2026-08-31）、课程通知开关与「时钟/手机日历」提醒方式、周末列显示、「检查更新」。
4. 「时间说明」：节次每两节为一课的时间规则（12 小时制换算表）。

## 时间规则（来自课表文件注释，App 内置换算）
1-2 节 08:20–10:00 ｜ 3-4 节 10:20–12:00 ｜ 5-6 节 13:20–15:00
7-8 节 15:20–17:00 ｜ 9-10 节 18:00–19:30；11-12 节无课程亦无时间。

## 说明与已知取舍
- 解析器均为纯 Kotlin/标准库实现（xls 为从 Python 验证版逐行移植的 OLE2+BIFF8 读取器），对教务「打印版」同族模板通用；遇到无法解析的周次片段会标记并提示，同时按全集处理并保留原文。
- 通知采用「每日早晨汇总」模式（按你的选择）；闹钟在无精确闹钟权限时自动退化为不精确每日触发。
- 若要在编译前快速预览 UI 与交互，打开仓库根目录 `demo-web/index.html`（在线演示，同款数据与逻辑）。
