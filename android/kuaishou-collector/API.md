# 本机接口 V1

模块数据库、App 内界面和 HTTP 接口使用同一个任务服务，不依赖第三方数据 API。
服务默认关闭。在快手的“评论数据库”入口中打开“本机接口”，开启后只监听 `127.0.0.1:8765`。
开关保存在模块私有数据库；进程重启后恢复。Android 回收模块进程时，打开管理页可重新启动服务。
密钥为随机 256 位值，只能在本机界面复制，不出现在日志中。

## 连接

请求头必须包含 `Authorization: Bearer <密钥>`。POST 只接受 UTF-8 JSON，最多 64 KiB。
推荐 `Content-Type: application/json; charset=utf-8`；省略 charset 时也按 UTF-8 读取，不接受其他字符编码。
不支持网页 Origin/CORS。网页后台应由自己的可信后端或本地桥接进程调用，不能向任意网站开放账号数据。

开发机通过指定模拟器转发端口：

```bash
node outputs/android-lab/lab.mjs adb forward tcp:28765 tcp:8765
```

真机连接与服务器部署需要独立的设备桥接，并使用认证隧道；不要将手机端口直接绑定公网。
该服务提供已保存数据和任务控制，不负责伪造平台请求，也不负责绕过登录和验证。
评论任务的分页驱动由任务参数决定：已适配的快手版本可以调用 App 内存中的原生 KMP 分页器；`ui` 模式使用界面滚动，`auto` 先走原生适配器，适配器不可用时回退 UI。HTTP 创建任务只控制本机任务，不伪造平台请求。

## 路由

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| GET | `/v1/health` | 数据库健康、评论/帖子/用户数量、平台阻塞状态 |
| GET | `/v1/runtime` | 实际安装版本、注入构建号、进程心跳、加载阶段和原生分页器绑定状态 |
| GET | `/v1/comments` | 去重评论及所属用户公开资料 |
| GET | `/v1/users` | 用户及已保存评论/帖子计数 |
| GET | `/v1/posts` | 作品平台统计快照，不是抓取数量 |
| GET | `/v1/tasks` | 最近 100 个任务 |
| POST | `/v1/tasks` | 创建任务，执行中的同目标任务返回原任务 |
| POST | `/v1/tasks/batch` | 最多 50 个任务入队，逐项返回结果/错误 |
| GET | `/v1/tasks/{id}` | 单任务原始读取、分页、回复组、资料进度 |
| GET | `/v1/tasks/{id}/events` | 最近 200 条运行事件 |
| GET | `/v1/tasks/{id}/targets` | 资料任务各用户的处理状态与错误 |
| POST | `/v1/tasks/{id}/pause` | 手动暂停 |
| POST | `/v1/tasks/{id}/resume` | 原任务续跑，保留已保存阶段 |
| POST | `/v1/tasks/{id}/retry` | 未完成任务原位续跑，不重建数据 |
| POST | `/v1/tasks/{id}/stop` | 停止当前任务或取消排队任务 |
| POST | `/v1/tasks/{id}/auto` | `{"enabled":true/false}` 切换自动加载，关闭后可手动翻页采集 |
| POST | `/v1/analysis` | 已保存公开评论的跨帖互动统计 |
| GET/POST | `/v1/settings` | `filters`、`analysis`、`ui` 设置组 |

统一返回 `{apiVersion:1, ok:true, data:...}` 或 `{ok:false, error:{code,message}}`。
列表返回 `items,total,limit,offset`；评论/用户/帖子分页 `limit` 为 1 至 200。
HTTP 401 表示缺少密钥，403 表示不允许的网页 Origin，409 表示任务/参数冲突。

## 创建任务

```json
{"kind":"comment","photoId":"5194620902990502573","auto":true,"enqueue":true,"pagingMode":"auto","ipRegion":"北京","gender":"female-or-unknown"}
```

多地区可使用 `"ipRegions":["北京","河北"]`，或 `"ipRegion":"北京、河北"`，表示任一地区。
这些条件随任务保留，结果查询时也应带上筛选条件；不会让采集端丢弃其他原始公开评论。

评论采集保存平台正常加载的原始公开评论，条件在结果查询时应用，避免未知性别直接丢弃原始数据。
同一作品同时只保留一个活跃评论任务，即使条件不同也不重复争用页面。

`pagingMode` 可取 `auto`、`native`、`ui`。`native` 仅在当前快手版本的原生分页器可用时运行；否则任务暂停并记录 `native_adapter_unavailable`。任务读取中会返回 `pagingDriver`、`nativeLoads` 和 `uiScrolls`，可据此确认是否实际走了原生加载。

0.1.2 的原生模式在任务缺少首页时调用 App 自己的 `refresh()`；回复续跑时会从现有分页器重新寻找父评论，必要时重新加载主评论页。`runtime.loadedBuild` 来自快手进程中正在运行的模块，`installedVersion/installedVersionCode` 来自已安装 APK；两者可用于判断升级后是否仍在运行旧进程。诊断不返回接口密钥、Cookie 或登录信息。
上一版构建号是 `0.1.2-native-recovery-20261025-r2`。回复动作区分网络加载、缓存展开和暂未就绪；缓存/等待动作不会虚构等待网络响应。原生游标、缓存长度或根响应对象用于恢复时的进度判断，不能仅靠已落库去重页数判断超时。`pages` 是归一化 DTO 快照数，`nativeLoads` 是原生方法调用数，都不等于平台 HTTP 请求次数。0.1.3 另外声明采集 Provider 的 Android 包可见性，修复注入到快手进程后解析不到本地 Provider 的问题。

```json
{"kind":"profile","userIds":["公开用户ID"],"auto":true,"enqueue":true}
```

资料任务支持 1 至 200 个用户。只读取正常打开的公开主页；缺失/隐藏字段保留未知，不推断性别、年龄或隐藏生日。

```json
{"kind":"discovery","keyword":"旅行","target":20,"maxPages":20,"auto":true,"enqueue":true}
```

发现任务仅关联当前关键词的原生搜索结果，不把推荐流当作搜索结果。达到目标、明确搜索末页、页数上限分别记录。

批量接口体为 `{"tasks":[上述任务对象]}`。排队任务不会争抢前一个任务的页面。
登录/验证/限流会暂停当前任务及自动推进；人工继续后才解除阻塞。

## 数据口径

- 评论：字符串 `commentId/photoId/userId/rootId`、一级/二级 `level`、`text/created/likes/replies/ipRegion/capturedAt`。
- 评论 IP 的来源为 Android 评论模型 `authorArea`，与主页 IP 分开。
- 用户：`gender/age/location/profileIpRegion/lastCommentIpRegion`；公开资料带采集时间及 `fieldSources`。未知字段不当作 0。
- 用户查询的 `ipRegion` 匹配最后评论 IP；`profileIpRegion` 单独匹配主页 IP。用户查询不支持 `taskId`；按任务筛选用户时应查询该任务评论后按 `userId` 去重。
- 帖子查询可用 `taskId` 限定某个发现任务，`keyword` 匹配标题，`searchKeyword` 匹配最近发现关键词。相同作品在不同任务中去重，任务归属分别保存。
- 评论查询：`taskId,photoId,userId,keyword,ipRegion,ipRegions,gender,minAge,maxAge,sort,limit,offset`。
- `ipRegions` 为 OR 条件。GET 可用逗号分隔字符串（Node 客户端数组会自动编码），分析 POST 可传数组；原生表单支持顿号、逗号及空白分隔，最多 20 项。两个地区参数同时提供时优先使用 `ipRegions`。
- 性别：`all/female/male/unknown/female-or-unknown`。有年龄条件时不匹配未知年龄。
- 排序：`captured_desc/captured_asc/created_desc/created_asc/likes_desc`。
- 单任务 `comments` 是该任务读取数量，不是数据库全局数量。`platformCountGap` 为 `max(0,total-comments)`，总数未知时为 null；差值并不证明存在可见漏抓。
- `rootPages` 单独统计已保存的主评论页；`pages` 包括主评论和回复。新任务尚未取得主评论首页时会重新打开目标作品；超过等待期限仍没有主评论页，记录 `root_page_not_observed`，不无限显示采集中。
- 平台 `total` 可能含不可见/删除评论，不能直接证明完整。`complete` 表示已确认正常主评论及回复流 EOF，不表示平台数量一定完全一致或折叠流已经单独核验。
- 0.1.2 不再接受本地分页器 `hasMore=false` 作为末页：折叠评论也会设置这个状态。先调用正常展开折叠入口，再要求响应自身 `hasMore=false`，记录 `rootEvidence=native_response_has_more_false`。原生回复展开仍要求每个有回复数的一级评论分别确认 EOF。EOF 写入与评论页共用同一存储队列，避免提前结束而丢弃尚未落库的页面。
- 主评论和回复分别保存 EOF。`hasMore=false`、非空游标下原生 `hasMore()` 返回 false、`no_more` 可形成明确协议证据；超时或数量接近不能。
- 当前 SDK 的回复缓存还可使用原生游标判定函数；必须同时读齐父评论快照中声明的全部独立回复 ID，才能接受其缓存终止信号。空游标预览或未知版本仍不算完成。
- 分析只统计已保存的公开评论、点赞/回复和时间；不能据此断言某人从未被联系。

`client.mjs` 提供 Node 客户端和逐页评论迭代器。私有密钥通过环境或本机配置传入，不写进网页代码、仓库或错误日志。

## 回归

```bash
node outputs/android-lab/kuaishou-collector/test.mjs
COLLECTOR_TEST_DB=outputs/android-lab/runtime/collector/capture-test.db node outputs/android-lab/kuaishou-collector/api-smoke.mjs
```

`api-smoke.mjs` 使用已保存的真实数据，不输出评论内容、昵称或密钥；中文编码测试临时写入 UI 设置并在 finally 中恢复，不创建采集任务。
`COLLECTOR_QUEUE_TEST=1` 会增加并取消一个真实用户资料排队测试任务，不删除现有数据。
`COLLECTOR_TASK_ID` 可指定评论测试任务，避免最新任务是诊断任务时使用错误基线。
HTTP/模型测试与实际完整翻页、搜索及真机验收分开记录。
2026-10-15 在任务 #10 上通过 28 项真实接口回归，包含北京/河北 OR、明确女性和 `rootPages`；任务本身仍有未确认回复组，不算完整抓取成功。
2026-10-23 任务 #11 验证了 `pagingMode=native`：53 次原生调用、0 次 UI 滑动、保存 388 条。2026-10-25 复查发现其根评论末页使用了本地分页器状态，可能被折叠入口影响；该任务的旧 `complete` 状态不能作为完整性证明，需要用 0.1.2 的响应末页规则重验。
