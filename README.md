# integration-test-discovery

**多类自发现测试桩**——一个模块、**两类 discovery 全自包含**验证（不依赖外部集成）：

- **IMPORT_FLOW**：经同进程 SDK 触发**本集成自己的** import-flow，创建本集成设备（`source=IMPORT_FLOW`）。
- **ZEROCONF**：广播 mDNS → 被 `integration-zeroconf` 发现 → **probe 步真实 HTTP 探活**（GET `/identify` 校验 model/sn）→ confirm 入网 → 仿真设备上报（`source=ZEROCONF`）。

> 需求文档 `require/ecat-core/25-discovery-flow.md`（R8）活体验证。discovery 机制设计见
> `ecat-core/src/main/java/com/ecat/core/ConfigFlow/DISCOVERY.md`。

## 工作机制（事件驱动，无就绪轮询）

入口 `TestDiscoveryIntegration`（`IntegrationDeviceBase`）在 `onLoad` 订阅 `INTEGRATIONS_ALL_LOADED`。
该事件发出时所有集成已加载（core service 就绪 + integration-zeroconf 就绪），handler 起两个线程分别跑两类 E2E。

### ConfigFlow 按发现类型拆文件

`TestDiscoveryConfigFlow` 只做**编排**（注册 handler + 注册 probe/confirm 步），每种 discovery 的逻辑在独立文件，便于单独调整：

| 文件 | 职责 |
|---|---|
| `TestDiscoveryConfigFlow` | 编排：注册 IMPORT_FLOW/ZEROCONF handler + `probe` 步（ZEROCONF 专用，真实 HTTP 探活）+ 共享 `confirm` 步（`createEntry()` 收尾） |
| `importflow/ImportFlowDiscoveryHandler` | IMPORT_FLOW handler：解析 `payload.data(model\|sn)` → 预填 → 直达 confirm（payload 可信，无 probe） |
| `zeroconf/ZeroconfDiscoveryHandler` | ZEROCONF handler：解析 TXT(model/sn) → 预填 ip/port/model/sn → 落 probe 步 |
| `zeroconf/ProbeHttpServer` | **被发现的"设备"侧**：integration-httpserver 起监听，`GET /identify` 回 `{model,sn,vendor}`（探活受探方） |
| `zeroconf/ProbeHttpClient` | probe 步的探活客户端：真连 `http://ip:port/identify`，校验应答 model/sn 与 TXT 声明一致 |

两类 discovery 步骤结构不同：
- **IMPORT_FLOW**：discovery → 共享 **confirm**（2 步）。
- **ZEROCONF**：discovery → **probe**（真实 HTTP 探活）→ 共享 **confirm**（3 步）。

`createEntry` 是 flow 内部方法，handler 独立文件无法直调，故统一在 confirm 步入网；probe 步在 flow 内（用 `getContext()` 读预填的连接信息），通过后才落 confirm。

### ① IMPORT_FLOW 自包含 E2E（`importflow/ImportFlowTestDriver`）

1. `core.getConfigFlowService().startDiscoveryFlow(coordinate: integration-test-discovery, source: IMPORT_FLOW, payload: ImportFlowPayload{version:1, data:"TestImportFlowDevice|IMPORTFLOW001|..."})`
   → 本集成 flow 的 IMPORT_FLOW handler 解析 → `SHOW_FORM confirm`。（**discovery 统一入口**：IMPORT_FLOW/ZEROCONF/MQTT 都走 `startDiscoveryFlow`，source 参数区分类型）
2. `submitStep(confirm, {confirmed:true})` → `CREATE_ENTRY`（`source=IMPORT_FLOW`）。
3. `createDeviceFromEntry` 加载仿真设备 → verifier 验设备在线 + 属性有值。
4. uniqueId=`testdiscovery_importflow_IMPORTFLOW001`。

### ② ZEROCONF 自包含 E2E（`zeroconf/ZeroconfTestLoop`，**含真实 HTTP probe**）

被"发现"的设备即入口在 `onLoad` 起的 `ProbeHttpServer`（监听 18081，`GET /identify` 回 `{model,sn,vendor}`）——故 probe 步真连必达。

1. 入口 `onLoad` 起 `ProbeHttpServer`（**必须在广播前就绪**，否则 probe 不可达）。
2. jmdns 广播 `_ecat-test._tcp.local.`，端口/TXT 取自 `ProbeHttpServer` 常量（`port=18081`、`model=TestDiscoveryDevice`/`sn=zeroconf001`）。
3. `integration-zeroconf` serviceResolved → `triggerDiscovery(test-discovery, ZEROCONF, payload)` → flow ZEROCONF handler 解析 TXT + 挑 IPv4 + 预填 ip/port/model/sn → 落 **probe** 步。
4. verifier 定位 ZEROCONF 活跃 flow（`getContext().getCurrentStep()` 纯读判步）→ `submitStep(probe)`：flow 真连 `http://<ip>:18081/identify`，校验应答 model/sn 与 TXT 一致 → 通过落 confirm；不符/不可达 ABORT（不入网）。
5. verifier `submitStep(confirm)` → `createEntry`（`source=ZEROCONF`）→ 仿真设备加载上报 → verifier 经 `getAllDevices()` 验设备。
6. uniqueId=`testdiscovery_zeroconf001`。

> 两条 E2E 都创建 `TestDiscoverySimulatedDevice`（temperature/humidity/rssi，5s 轮询，NORMAL）。
> "抓 probe/confirm + 等设备"保留轻量轮询（异步追赶）；**就绪轮询已由 `INTEGRATIONS_ALL_LOADED` 事件替代**。

## 成功判据

- **import-flow**：`CREATE_ENTRY` + entry `source=IMPORT_FLOW` + 设备 `testdiscovery_importflow_IMPORTFLOW001` 在线。
- **zeroconf**：probe 步真实 HTTP 探活通过（`GET /identify` model/sn 校验）→ 设备 `testdiscovery_zeroconf001` 在线、`deviceStatus=NORMAL`、temperature 有值、`source=ZEROCONF`。
- 两结果文件 `status: SUCCESS`。

## 结果查看

- 日志：`logs/core-api.log`，grep `[test-discovery]`（线程 `[test-discovery-importflow]` / `[test-discovery-zeroconf]`）。
- import-flow 结果：`test-discovery-importflow-e2e-result.txt`。
- zeroconf 结果：`test-discovery-zeroconf-e2e-result.txt`。
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
├── TestDiscoveryConfigFlow.java             # ConfigFlow 编排（注册两 handler + probe 步 + 共享 confirm 步）
├── importflow/
│   ├── ImportFlowDiscoveryHandler.java      # IMPORT_FLOW handler（解析 payload → 直达 confirm）
│   └── ImportFlowTestDriver.java            # IMPORT_FLOW 自包含 E2E 驱动（init → confirm → 验设备）
└── zeroconf/
    ├── ZeroconfDiscoveryHandler.java        # ZEROCONF handler（解析 TXT + 挑 IPv4 → 落 probe 步）
    ├── ProbeHttpServer.java                 # 被发现的"设备"侧：httpserver 起 18081，GET /identify 回 {model,sn,vendor}
    ├── ProbeHttpClient.java                 # probe 步探活客户端（真连 /identify 校验 model/sn）
    ├── TestDiscoverySimulatedDevice.java    # 仿真设备（temp/hum/rssi，两种 discovery 共用）
    ├── ZeroconfServiceBroadcaster.java      # SRP：mDNS 广播方（jmdns 生命周期 + ServiceInfo）
    ├── ZeroconfFlowDriver.java              # SRP：推进 ZEROCONF 活跃 flow 的指定步（单次尝试）
    ├── SimDeviceVerifier.java               # SRP：等待仿真设备加载并上报数据
    ├── E2EResultWriter.java                 # SRP：写 E2E 结果文件
    └── ZeroconfTestLoop.java                # SRP：编排器（幂等+轮询节奏+E2E序列，组合上面 4 个）
```

> 旧文件 `zeroconf/TestDiscoveryConfigFlow.java`（合并期的 zeroconf-only flow）已上移到父包并中和为标记文件，待人工删。

## 依赖

- `ecat-core`（provided）：ConfigFlow 机制（**discovery 统一入口 `ConfigFlowService.startDiscoveryFlow`**）/ `ImportFlowPayload` / `INTEGRATIONS_ALL_LOADED` / `IntegrationDeviceBase`。
- `integration-zeroconf`（provided）：`ZeroconfDiscoveryIntegration` / `ZeroconfSubscription` / `ZeroconfDiscoveryPayload`。
- `integration-httpserver`（provided）：`EasyHttpServer`——zeroconf 闭环受探 HTTP server（`ProbeHttpServer`）起监听、注册 `GET /identify`。
- `fastjson2`（provided）：`ProbeHttpClient` 解析 `/identify` JSON 应答。
- `jmdns` 3.5.9（compile，进 fatjar）：zeroconf 闭环广播测试服务用。
- **不依赖** saimosen / modbus / serial（import-flow 自包含，不驱动外部集成）。
