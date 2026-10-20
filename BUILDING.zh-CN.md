# 打包与验证说明

本说明适用于各 `port/<Minecraft版本>` 分支。必须将 LFM 和 EXIT 的 Minecraft 版本配对；不要直接用默认分支代替版本分支。1.20.4 的本次修复版本为 **1.0.1+1.20.4**。

## 构建环境和顺序

使用仓库附带的 Gradle Wrapper，以 **JDK 25** 运行 Gradle（Loom 1.18.1 的要求）。这与游戏/输出字节码要求不同：1.16.5–1.17.1 使用 Java 16，1.18.2–1.20.4 使用 Java 17，1.20.6–1.21.x 使用 Java 21；EXIT 的 toolchain 也需要相应 JDK。不要通过把 `release` 改为 25 来解决 Gradle 启动问题。

EXIT 尚未发布到公共 Maven，LFM 使用 `mavenLocal()` 获取它。因此，必须先构建并发布匹配的 EXIT，再构建 LFM。每个分支的 `.github/workflows/build.yml` 都固定了本次配对的 **EXIT commit**；需要精确重建时，用该 commit，而不是未来可能变化的分支头。

以下为 PowerShell 中构建 1.20.4 的示例，在一个新的工作目录操作。`JAVA_HOME` 应已指向本机 JDK 25，并安装 JDK 17（或允许 Gradle toolchain 下载）。

```powershell
git clone --branch port/1.20.4 https://github.com/hotpad100c/litematicafastmove.git lfm-src
git clone --branch port/1.20.4 https://github.com/hotpad100c/emergency-exit.git exit-src
Set-Location exit-src
# 重建本次配对版本：将下面的 SHA 换成 LFM workflow 中 EXIT checkout 的 ref
git checkout 8638c89aa33175de0b26fa19b9e2ed98b97c0fef
.\gradlew.bat :fabric:publishToMavenLocal --init-script ..\lfm-src\scripts\exit-publication.init.gradle --console=plain
if ($LASTEXITCODE -ne 0) { throw 'EXIT build failed' }
Set-Location ..\lfm-src
.\gradlew.bat build --console=plain
if ($LASTEXITCODE -ne 0) { throw 'LFM build failed' }
python scripts/verify-package.py
if ($LASTEXITCODE -ne 0) { throw 'Package verification failed' }
```

Linux/macOS 使用 `./gradlew` 和 `python3`。首次构建需要联网下载依赖；`--offline` 只适用于已完整缓存依赖的机器。旧版 `scripts/build-all.sh` 仍要求提前把所有 EXIT 版本发布到本地 Maven，缺少依赖会跳过并返回失败。

`scripts/exit-publication.init.gradle` 为旧 EXIT 分支区分 named 与 remapped 源码包的 classifier，避免发布时出现两个 `sources` artifact。该兼容处理只影响开发源码包分类，不改变成品代码；直接发布某些旧分支而省略此参数会失败。

## 哪个 jar 可以分发

选择当前 `gradle.properties` 中 `version` 对应的 `build/libs/litematicafastmove-<version>.jar`。1.20.4 应为 `litematicafastmove-1.0.1+1.20.4.jar`。`-sources.jar`、`-dev.jar` 和 `build/devlibs/` 中的文件用于开发，不能代替成品；不要根据文件修改时间选择旧版本同目录 jar。

`python scripts/verify-package.py` 会检查成品的版本、MC 范围、intermediary 名称、嵌套 EXIT 版本和 ZIP 完整性，并生成 SHA256 与配对记录。EXIT 已通过 Jar-in-Jar 内嵌到 LFM，玩家通常无需重复安装独立 EXIT；Fabric API、对应版本 Litematica 和 malilib 仍需自行安装。Iris/Sodium 是开发对照依赖，不应把整个开发运行目录打包给玩家。

Actions 按上述顺序构建固定 commit 的 EXIT、发布本地 Maven、构建 LFM、核验成品，然后上传当前版本的 jar、`checksums.sha256` 和 `package-verification.json`。在对应分支的绿色 `build` 运行页面下载 `LFM-<MC版本>` artifact；GitHub 自动生成的 Source code ZIP 是源码。CI 构建成功只证明编译与包结构，不代替实际游戏渲染验证。

## 本次修复与游戏验证

- 1.16.5/1.17.1：处理空 chunk section；旧版本覆盖层复用相机旋转矩阵。
- 1.16.5：修正半透明 alpha uniform 的程序状态以及覆盖层纹理/alpha test 状态。
- 1.16.5–1.20.6：透明排序上传时绑定对应 VertexBuffer。
- 1.19.3–1.20.4：保持 shader 在各 section 绘制期间有效，使用上传时已建立的 VAO。
- 1.20.4：在 EXIT portal 内设置当前 layer 状态后选取 shader，修复只有蓝框、缺面或白色材质的问题；旧 1.0.0 的此前通过记录被此修复替代。
- 1.20.6：覆盖层补全 buffer 绑定。
- 1.21.1/1.21.4：EXIT 0.1.1 保存并恢复 Iris 的扩展顶点格式开关；渲染线程上传普通格式时使用匹配布局，后台构建线程不修改全局渲染状态。
- 1.21.8：保留调用方 BufferSource，修复光影路径中错误强转的问题。

已在 1.16.5、1.17.1、1.18.2、1.19.3、1.20.1、1.20.2、1.20.4、1.20.6、1.21.1、1.21.4、1.21.8、1.21.11 的普通 Fabric 成品客户端检查关闭光影和开启 BSL 的多角度与移动画面；1.20.4 追加验证使用本次 1.0.1。以上是指定依赖组合和场景的证据，不代表所有光影包、第三方 mod 组合都已覆盖。同步远端后额外合并的改动以本轮构建记录为准；原游戏实测 jar 的 SHA 见性能报告。

## 零延迟设置与性能

已有配置不会因为更新 jar 而自动重置。关闭游戏后，合并以下内容到 `config/litematica.json` 的 `FastMove` 对象，保留其他字段：

```json
{
  "fastMoveCommitDelay": 0,
  "fastMoveRenderDelay": 0
}
```

性能对照还将 `fastMoveSmoothing` 设为 `0`，用于排除移动动画；日常使用可自行保留动画。源码默认 CommitDelay 仍为 1500、RenderDelay 为 0，这里提供的是零延迟配置方法。

1.20.4、24 逻辑 CPU、实际 60FPS、关闭光影的三个正式重复中位数：百万体积实心图首次全部就绪约 **434→234ms**；52.4 万独立方块/314.6 万面约 **559→479ms**；后者移动后的首次完整画面约 **500→17ms**。小高面数图首次加载 **148→168ms**，30FPS 大高面数图 **552→725ms**，存在退步。不能承诺所有场景都加速。

CommitDelay 1500→0 将大图移动后全部就绪从约 1.70 秒降至 0.18 秒，主要去掉等待时间；本地文件读取约 0.4–3ms，并不是网络下载。完整方法、114 条记录和适用范围见 [性能报告](https://github.com/hotpad100c/litematicafastmove/blob/port/1.20.4/docs/benchmarks/2026-10-21/README.md)。
