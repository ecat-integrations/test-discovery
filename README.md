# integration-test-discovery

**多类自发现测试桩**——一个模块、**两类 discovery 全自包含**验证（不依赖外部集成）：

- **IMPORT_FLOW**：经同进程 SDK 触发**本集成自己的** import-flow，创建本集成设备（`source=IMPORT_FLOW`）。payload 携带账号密码（`model|sn|name|account|password`）。
- **ZEROCONF**：广播 mDNS → 被 `integration-zeroconf` 发现 → **probe 步真实 HTTP 探活**（GET `/identify` 校验 model/sn）→ **credentials 步用户输入账号密码（当场验证：真连 `/data` BasicAuth 校验，错误凭证不放行入网）** → confirm 汇总入网 → 仿真设备**用账号密码访问 `/data` 拉数据**上报（`source=ZEROCONF`）。**reconfigure 只改凭证**（错误凭证入网后可改对，框架重建设备读新凭证）。真实认证入网示例。

> 需求文档 `require/ecat-core/25-discovery-flow.md`（R8）活体验证。discovery 机制设计见
> `ecat-core/src/main/java/com/ecat/core/ConfigFlow/DISCOVERY.md`。

## 工作机制（事件驱动，无就绪轮询）

入口 `TestDiscoveryIntegration`（`IntegrationDeviceBase`）在 `onLoad` 订阅 `INTEGRATIONS_ALL_LOADED`。
该事件发出时所有集成已加载（core service 就绪 + integration-zeroconf 就绪），handler 起两个线程分别跑两类 E2E。

### ConfigFlow 按发现类型拆文件

`TestDiscoveryConfigFlow` 只做**编排**（注册 handler + 注册 probe/confirm 步），每种 discovery 的逻辑在独立文件，便于单独调整：

| 文件 | 职责 |
|---|---|
| `TestDiscoveryConfigFlow` | 编排：注册 IMPORT_FLOW/ZEROCONF handler + `probe`/`probe_failed`/`credentials`/`confirm` 步 + `reconfigure` 改凭证步（ZEROCONF 走 probe→credentials(当场验证)→confirm；探活失败走 probe_failed；IMPORT_FLOW 直达 confirm；reconfigure 只改 account/password） |
| `importflow/ImportFlowDiscoveryHandler` | IMPORT_FLOW handler：解析 `payload.data(model\|sn\|name\|account\|password)` → 预填（含凭证/地址）→ 直达 confirm |
| `zeroconf/ZeroconfDiscoveryHandler` | ZEROCONF handler：解析 TXT(model/sn) → 预填 ip/port/model/sn → 落 probe 步；并构建各步 schema（probe 成功/凭证/确认/探活失败） |
| `zeroconf/ProbeHttpServer` | **被发现的"设备"侧**：`GET /identify`（无认证，探活）+ `GET /data`（HTTP Basic Auth admin/123，回传感器数据）|
| `zeroconf/ProbeHttpClient` | 探活客户端（`GET /identify` 校验 model/sn）+ 凭证拉数据客户端（`GET /data` BasicAuth 拉温度/湿度/rssi）|
| `zeroconf/TestDiscoverySimulatedDevice` | 仿真设备：读 entry 凭证 → 周期 `GET /data`(BasicAuth) 拉数据上报；401/不可达→OFFLINE |

两类 discovery 步骤结构不同：
- **IMPORT_FLOW**：discovery → 共享 **confirm**（2 步；payload 可信带凭证，无需探活/凭证步）。
- **ZEROCONF**：discovery → **probe**（真实 HTTP 探活 + 设备信息展示）→ **credentials**（用户输入账号密码，**提交时当场验证**）→ 共享 **confirm**（汇总确认）（4 步）。探活失败 → **probe_failed** 步（用户点【下一步】→ abort 结束流程）。

`createEntry` 是 flow 内部方法，handler 独立文件无法直调，故统一在 confirm 步入网。各步用 `userInput==null` 区分 **status 重放**（getStatus，展示）与 **用户提交**（submitStep，推进/校验）——重开向导不会误推进/误结束。

### ① IMPORT_FLOW 自包含 E2E（`importflow/ImportFlowTestDriver`）

1. `core.getConfigFlowService().startDiscoveryFlow(coordinate: integration-test-discovery, source: IMPORT_FLOW, payload: ImportFlowPayload{version:1, data:"TestImportFlowDevice|IMPORTFLOW001|test-discovery import-flow IMPORTFLOW001|admin|123"})`
   → 本集成 flow 的 IMPORT_FLOW handler 解析 5 段（model|sn|name|account|password）→ 预填含凭证/地址 → `SHOW_FORM confirm`。（**discovery 统一入口**：IMPORT_FLOW/ZEROCONF/MQTT 都走 `startDiscoveryFlow`，source 参数区分类型）
2. `submitStep(confirm, {confirmed:true})` → `CREATE_ENTRY`（`source=IMPORT_FLOW`）。
3. `createDeviceFromEntry` 加载仿真设备 → 设备读 entry 凭证 → 周期 `GET /data`(BasicAuth) 拉数据上报。
4. uniqueId=`testdiscovery_importflow_IMPORTFLOW001`。

### ② ZEROCONF 演示 pending（`zeroconf/ZeroconfDemoDriver`，**发现 ≠ 添加**）

被"发现"的设备即入口在 `onLoad` 起的 `ProbeHttpServer`（监听 18081，`GET /identify` 回 `{model,sn,vendor}`）——故 probe 步真连必达。

1. 入口 `onLoad` 起 `ProbeHttpServer`（**必须在广播前就绪**，否则 probe 不可达）。
2. jmdns 广播 `_ecat-test._tcp.local.`，端口/TXT 取自 `ProbeHttpServer` 常量（`port=18081`、`model=TestDiscoveryDevice`/`sn=zeroconf001`）。
3. `integration-zeroconf` serviceResolved → `startDiscoveryFlow(test-discovery, ZEROCONF, payload)` → flow ZEROCONF handler 解析 TXT + 挑 IPv4 + 预填 ip/port/model/sn → 落 **probe** 步 SHOW_FORM → **进 pending 等用户**（不自动 submitStep）。
4. demo driver 仅轮询确认 pending 建立并日志提示；**真人**通过 web `/discoveries` 点【添加】→ 向导三步（每步 `userInput==null`=getStatus 重放展示 / 非 null=submitStep 推进）：
   - **probe**（探活+设备信息）：`getStatus` 重跑 `GET /identify` → 通过则只读展示「探活通过 · model · sn · ip:port」+【下一步】；不可达/不符 → 落 **probe_failed** 步（「设备无法访问」+【下一步】→ abort → 向导**失败结束屏**「流程已结束」）。
   - **credentials**（凭证）：只读提示「默认账号 admin / 密码 123」+ account/password 输入 →【下一步】。**提交时当场验证**：真连 `/data`(BasicAuth) 校验凭证，错误/不可达 → 带 errors 重放（**不放行错误凭证入网**，避免入网后设备无法通讯）；空值带 errors 重放。
   - **confirm**（汇总）：只读展示「model · sn · ip:port · 账号」（密码不回显）→【下一步】→ `createEntry`（`source=ZEROCONF`）。
5. 入网后仿真设备读 entry 凭证 → 周期 `GET /data`(BasicAuth) 拉温度/湿度/rssi 上报；凭证无效/不可达 → deviceStatus=OFFLINE。
6. **周期"换 name 重新广播"**（`ZeroconfServiceBroadcaster`，每 60s 一次）—— 关键机制：每周期 `unregister 旧 name → 等 2s（GOODBYE_WAIT_MS）→ register 新 name`（`TestDiscovery-zeroconf001-<seq>`）。
   - **为什么必须换 name**：jmdns 对"持续在线 / 同 name 重注册"的服务**不重复触发 `serviceResolved`**（listener cache 命中，只在首次出现触发）。实测瞬间 reannounce / `unregister+sleep+register` / `close+create+register` 在**同 name** 下都**不稳定**（只在 listener cache 未稳定期偶发触发）。换 name 让 listener 当**新服务**必然重新 resolve；TXT `sn` 不变 → uniqueId 不变 → 去重/进 pending 逻辑与 name 无关。
   - **效果**：每个周期重新 `serviceResolved → startDiscoveryFlow`：
     - 设备**已添加** → 重复发现走 **R5/R12 去重**（兑现"已添加设备重复发现"的去重压测意图）。
     - 设备**已删除**（用户删 entry 后）→ 下个周期重新进 **pending**，设备发现页重新可见（**解决"删除后无法重新发现"**）。
     - **IGNORE 抑制**：已忽略设备不进 pending。
   - 实测：每周期 register 后 ~1.6-2s listener 完成 resolve，core 端到端连续多周期每周期稳定触发；完整闭环（首次发现→添加→删 entry→下周期）验证重新进 pending ✓。
7. uniqueId=`testdiscovery_zeroconf001`（mDNS service name 每周期变 `...-<seq>`，但 uniqueId 由 TXT `sn` 派生，固定）。

> **发现 ≠ 添加**：zeroconf 是被动发现，只进 pending 等用户决策；import_flow 是程序内调用，由调用方显式推进跑完（见 ①）。
> **2s goodbye 窗口**：每周期 unregister→register 之间设备短暂"消失"（2s），此窗口内偶发探活会失败（每 60s 一次，影响可接受）。

### ③ reconfigure 改凭证（修复"两头堵"）

**问题**：若用户在 credentials 步输错凭证——旧实现只校验非空，错误凭证照样入网 → 设备 `GET /data` 被 401 拒 → `deviceStatus=OFFLINE`、无数据；用户想去 reconfigure 改对，但本集成**未注册 reconfigure 步** → `reconfigureEntry` 报「无可供 reconfigure 的流程」。两头堵（进不去、改不了）。

**两处修复**：

1. **credentials 当场验证**（见 ② credentials 步）：提交时真连 `/data`(BasicAuth) 校验，错误凭证**重放凭证步带 errors**、不放行入网——从源头杜绝"错凭证入网"。
2. **reconfigure 只改凭证**：`TestDiscoveryConfigFlow` 注册 `reconfigure` 步（`registerStepReconfigure`）。用户在 entries 视图点【重新配置】→ 向导单步：account 预填当前值（可改）+ password 重新输入 → 提交时**当场验证新凭证**（同 credentials）→ 通过则 `createEntry()`（`sourceType=RECONFIGURE` 自动转 `updateEntry`，保留原 entryId、保留 ip/port/model/sn 原值，只覆盖 account/password）。
   - **设备如何用上新凭证**：`IntegrationDeviceBase.reconfigureEntry` **重建设备**（stop/release 旧设备 → `createDeviceFromEntry(newEntry)` → `start()`），新设备 `start()` 从更新后的 entry.data 读新凭证拉数据——OFFLINE→NORMAL 自愈，无需设备侧特殊处理。
   - **只改凭证**：不动设备类型/连接地址（ip/port/model/sn 保留），符合 reconfigure 语义（改类型应删后重建）。

## 成功判据

- **import-flow**：`CREATE_ENTRY` + entry `source=IMPORT_FLOW` + 设备 `testdiscovery_importflow_IMPORTFLOW001` 在线 + 凭证拉数据（temperature 有值）。
- **zeroconf**：向导三步走完（probe 探活通过 → credentials 输入 admin/123 → confirm 汇总）→ 设备 `testdiscovery_zeroconf001` 在线、`deviceStatus=NORMAL`、凭证拉 `/data` 上报 temperature/humidity/rssi、`source=ZEROCONF`。**错误凭证**（如密码输 wrong）在 credentials 步被当场拒绝（重放 errors，不入网）。
- **reconfigure**：已入网 zeroconf 设备点【重新配置】→ 单步改凭证（account 预填 + password 重输）→ 输对（admin/123）当场验证通过 → 设备重建后 `deviceStatus` 从 OFFLINE 恢复 NORMAL、数据恢复上报；输错则重放 errors 不更新。
- import-flow 结果文件 `status: SUCCESS`。

## 结果查看

- 日志：`logs/core-api.log`，grep `[test-discovery]`（线程 `[test-discovery-importflow]` / `[test-discovery-zeroconf]`）。
- import-flow 结果：`test-discovery-importflow-e2e-result.txt`。
- 持久化 entry：coordinate=integration-test-discovery。

## 如何运行

1. 构建并安装相关模块（无需 saimosen——import-flow 自包含；httpserver 是 probe 受探方依赖）：
   ```bash
   mvnd -pl ecat-core,ecat-integrations/zeroconf,ecat-integrations/httpserver,ecat-integrations/test-discovery install
   ```
2. 确保 `.ecat-data/core/integrations.yml` 含 `com.ecat:integration-test-discovery`、`com.ecat:integration-zeroconf`、`com.ecat:integration-httpserver`（enabled: true）。
3. 启动 core：
   ```bash
   bash .claude/skills/core-integration-test/scripts/start-core.sh "$PWD"
   ```
4. 等待 `INTEGRATIONS_ALL_LOADED`（启动后数秒），查看两个结果文件与日志。

## 重要：重新运行需先清理旧 entry

E2E 用固定 uniqueId。**重复 fresh 运行前需先删对应 entry**，否则 R5（uniqueId 去重）会拦截（去重的正确行为，非 bug）：

- import-flow：`testdiscovery_importflow_IMPORTFLOW001`。
- zeroconf：`testdiscovery_zeroconf001`。

不删则走幂等 already-imported/discovered 路径（直接验设备）。经 core API `DELETE /core-api/config-flow/entries/{entryId}` 删除。

## 模块目录

```
src/main/java/com/ecat/integration/testdiscovery/
├── TestDiscoveryIntegration.java            # 入口(IntegrationDeviceBase)，onLoad 起 ProbeHttpServer + 订阅 INTEGRATIONS_ALL_LOADED
├── TestDiscoveryConfigFlow.java             # ConfigFlow 编排（注册两 handler + probe/probe_failed/credentials/confirm 步）
├── importflow/
│   ├── ImportFlowDiscoveryHandler.java      # IMPORT_FLOW handler（解析 model|sn|name|account|password → 直达 confirm）
│   └── ImportFlowTestDriver.java            # IMPORT_FLOW 自包含 E2E 驱动（payload 带 admin/123 → confirm → 验设备拉数据）
└── zeroconf/
    ├── ZeroconfDiscoveryHandler.java        # ZEROCONF handler（解析 TXT + 挑 IPv4 → 落 probe 步）+ 各步 schema builder
    ├── ProbeHttpServer.java                 # 被发现的"设备"侧：GET /identify（探活）+ GET /data（BasicAuth admin/123，传感器数据）
    ├── ProbeHttpClient.java                 # 探活客户端（/identify 校验 model/sn）+ 凭证拉数据客户端（/data BasicAuth）
    ├── TestDiscoverySimulatedDevice.java    # 仿真设备（读 entry 凭证 → 周期 GET /data 拉数据上报）
    ├── ZeroconfServiceBroadcaster.java      # SRP：mDNS 广播方（jmdns 生命周期 + ServiceInfo）
    └── ZeroconfDemoDriver.java              # zeroconf 演示：发现→进 pending 等用户（不 submitStep、不验设备）
```

## 依赖

- `ecat-core`（provided）：ConfigFlow 机制（**discovery 统一入口 `ConfigFlowService.startDiscoveryFlow`**）/ `ImportFlowPayload` / `INTEGRATIONS_ALL_LOADED` / `IntegrationDeviceBase`。
- `integration-zeroconf`（provided）：`ZeroconfDiscoveryIntegration` / `ZeroconfSubscription` / `ZeroconfDiscoveryPayload`。
- `integration-httpserver`（provided）：`EasyHttpServer`——zeroconf 闭环受探 HTTP server（`ProbeHttpServer`）起监听、注册 `GET /identify`。
- `fastjson2`（provided）：`ProbeHttpClient` 解析 `/identify` JSON 应答。
- `jmdns` 3.5.9（compile，进 fatjar）：zeroconf 闭环广播测试服务用。
- **不依赖** saimosen / modbus / serial（import-flow 自包含，不驱动外部集成）。
