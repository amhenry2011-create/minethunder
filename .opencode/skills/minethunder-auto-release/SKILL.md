---
name: minethunder-auto-release
description: Use after completing any MineThunder code or version change to build, install the jar, commit the full change set, and push it to the GitHub repository. Triggers on 改一版、上传仓库、auto release、push after build.
---

# MineThunder 自动构建与上传

## 目标

每次完成一版代码改动后，自动执行：

1. 构建
2. 复制 jar 到 NeoForge mods
3. 提交本地 Git
4. 推送到 GitHub

用户不需要再次说“上传仓库”。

## 仓库事实

- 项目根目录：`/Users/henry/code/minethunder`
- 远程：`origin git@github.com:amhenry2011-create/minethunder.git`
- 分支：`main`
- GitHub SSH 已在 `~/.ssh/config` 中走 `ssh.github.com:443`
- 构建：`./gradlew build --no-daemon --console=plain`
- 安装目录：`/Users/henry/Desktop/minecraft/versions/1.21.1-NeoForge/mods/`

## 执行流程

1. 检查仓库状态：

```bash
git status --short
git remote -v
git branch --show-current
```

2. 构建：

```bash
./gradlew build --no-daemon --console=plain
```

3. 复制 jar：

```bash
cp build/libs/minethunder-0.1.0.jar \
  /Users/henry/Desktop/minecraft/versions/1.21.1-NeoForge/mods/minethunder-0.1.0.jar
```

4. 提交前检查：

- `build/`、`.gradle/`、`.DS_Store` 不得进入提交。
- 检查改动中是否包含 token、密码、私钥、access key。
- `git diff --check` 不得有空白错误。
- 如果没有实际改动，不创建空提交。

5. 提交：

```bash
git add -A
git commit -m "<本版改动摘要>"
```

提交信息使用简短英文或中文均可，但要描述实际改动，例如：

- `Fix IR seeker heat and lock tolerance`
- `Add radar beam debug rendering`
- `Improve missile datalink guidance`

6. 推送：

```bash
git push origin main
```

如果 SSH 推送超时：

- 先重试一次。
- 检查 `ssh -T -o ConnectTimeout=10 git@github.com`。
- 检查 `/etc/hosts` 是否仍把 `github.com` 指向可用地址。
- 如果网络恢复后仍失败，报告错误，不要强推、不要改写远程历史。

7. 报告：

- 构建是否成功
- jar 是否已复制
- commit hash
- 是否已推送
- 需要用户实机测试的内容

## 禁止

- 不要在用户未明确要求时启动或重启游戏。
- 不要提交构建产物、缓存、日志、截图或本机 Minecraft 目录。
- 不要使用 `git push --force`。
- 不要把 GitHub token 写入仓库。
- 推送失败时不要假装成功。
