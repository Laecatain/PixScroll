# 自托管 GitHub Actions Runner + Claude Code PR 审查

## 背景

Anthropic 云账号注册受限（中国大陆），无法使用官方的 Claude GitHub App（自动 Code Review、`@claude` 评论响应）。替代方案：在本地 Windows 机器上注册自托管 GitHub Actions Runner，调用本地 Claude Code CLI 执行 PR 审查，结果通过 `gh` CLI 回贴到 PR。

## 架构

```
GitHub PR (open/sync) ──触发──► GitHub Actions ──调度──► 自托管 Runner (本机)
                                                              │
                                                              ├── 拉取 PR diff
                                                              ├── 管道至 Claude Code CLI
                                                              ├── 捕获审查结果
                                                              └── gh pr comment 回贴
```

## 文件结构

```
.github/workflows/
├── pr-review.yml       # PR 创建/同步时自动触发 Claude Code 审查
├── pr-comment.yml      # PR 评论 @claude 时触发响应
└── issue-fix.yml       # Issue 打上 auto-fix 标签后自动分析并提 PR
```

## 前置条件

- Windows 11 机器
- Git 已安装、`gh` CLI 已认证（`repo` + `workflow` 权限）
- Claude Code CLI 已在系统 `PATH` 中
- 目标 GitHub 仓库（`Laecatain/reader-app`）

## 步骤

### 1. 创建 Workflow 文件

在项目根目录创建 `.github/workflows/pr-review.yml`：

```yaml
name: Self-Hosted Claude Code Review

on:
  pull_request:
    types: [opened, synchronize]

jobs:
  review:
    runs-on: self-hosted
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
          ref: ${{ github.event.pull_request.head.sha }}

      - name: Verify Claude CLI
        shell: pwsh
        run: claude --version

      - name: Run Claude Code review
        id: review
        shell: pwsh
        env:
          PR_NUMBER: ${{ github.event.pull_request.number }}
          GH_TOKEN: ${{ github.token }}
        run: |
          $diff = gh pr diff $env:PR_NUMBER 2>&1
          $prompt = @"
          You are a code reviewer. Review this PR diff for:
          - Bugs and logic errors
          - Security vulnerabilities
          - Code quality and maintainability issues

          PR diff:
          $diff
          "@

          $result = $prompt | claude 2>&1
          Set-Content -Path "$env:RUNNER_TEMP\review.md" -Value $result

      - name: Post review comment
        shell: pwsh
        env:
          PR_NUMBER: ${{ github.event.pull_request.number }}
          GH_TOKEN: ${{ github.token }}
        run: |
          $body = Get-Content -Path "$env:RUNNER_TEMP\review.md" -Raw
          gh pr comment $env:PR_NUMBER --body "$body"
```

创建 `.github/workflows/pr-comment.yml`：

```yaml
name: "@claude Comment Handler"

on:
  issue_comment:
    types: [created]

jobs:
  respond:
    if: |
      github.event.issue.pull_request &&
      contains(github.event.comment.body, '@claude')
    runs-on: self-hosted
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
          ref: refs/pull/${{ github.event.issue.number }}/head

      - name: Verify Claude CLI
        shell: pwsh
        run: claude --version

      - name: Run Claude with comment request
        id: claude
        shell: pwsh
        env:
          PR_NUMBER: ${{ github.event.issue.number }}
          COMMENT_BODY: ${{ github.event.comment.body }}
          GH_TOKEN: ${{ github.token }}
        run: |
          $diff = gh pr diff $env:PR_NUMBER 2>&1
          $prompt = @"
          A request was made on PR #$env:PR_NUMBER via a GitHub comment:

          $env:COMMENT_BODY

          Here is the PR diff for context:
          $diff

          Respond concisely and helpfully.
          "@

          $result = $prompt | claude 2>&1
          Set-Content -Path "$env:RUNNER_TEMP\response.md" -Value $result

      - name: Post reply
        shell: pwsh
        env:
          PR_NUMBER: ${{ github.event.issue.number }}
          GH_TOKEN: ${{ github.token }}
        run: |
          $body = Get-Content -Path "$env:RUNNER_TEMP\response.md" -Raw
          gh pr comment $env:PR_NUMBER --body "$body"
```

### 2. 注册自托管 Runner

在**本机 PowerShell** 执行：

```powershell
# 创建目录
mkdir D:\actions-runner
cd D:\actions-runner

# 下载 Runner 包
Invoke-WebRequest -Uri https://github.com/actions/runner/releases/download/v2.334.0/actions-runner-win-x64-2.334.0.zip -OutFile actions-runner-win-x64-2.334.0.zip

# 解压
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory("$PWD/actions-runner-win-x64-2.334.0.zip", "$PWD")

# 配置（需要从 GitHub 页面获取 token）
# 浏览器打开: https://github.com/Laecatain/reader-app/settings/actions/runners
# 点 "New self-hosted runner" → Windows → 复制 token
.\config.cmd --unattended `
  --url "https://github.com/Laecatain/reader-app" `
  --token "<YOUR_TOKEN>" `
  --name windows-1 `
  --labels "self-hosted,windows" `
  --runnergroup default `
  --work _work
```

### 3. 启动 Runner

```powershell
# 前台运行（保持终端开启）
cd D:\actions-runner
.\run.cmd

# 或后台运行（隐藏窗口）
Start-Process -WindowStyle Hidden -FilePath "D:\actions-runner\run.cmd" -WorkingDirectory "D:\actions-runner"
```

### 4. 推送 Workflow 到仓库

```powershell
git add .github/workflows/
git commit -m "ci: 自托管 Claude Code PR 审查流水线"
git push
```

## 验证

在 GitHub 上创建新的 PR，观察：

1. **Actions 页面**: `https://github.com/Laecatain/reader-app/actions` 应出现 `Self-Hosted Claude Code Review` 工作流
2. **Runner 日志**: `D:\actions-runner` 终端显示 Job 被 pick up 并执行
3. **PR 评论**: 审查完成后 Claude Code 的结果以 PR comment 形式回贴

## 检验 Runner 在线状态

```powershell
gh api repos/Laecatain/reader-app/actions/runners --jq '.runners[] | "\(.name): \(.status) \(.busy)"'
```

输出 `windows-1: online false` 表示在线空闲。

## 日常操作

| 操作 | 命令 / 说明 |
|------|-------------|
| 启动 Runner | `Start-Process -WindowStyle Hidden D:\actions-runner\run.cmd` |
| 停用 Runner | GitHub → Settings → Actions → Runners → `windows-1` → Remove |
| 更新 Runner | 下载新版 zip 替换 `D:\actions-runner` 配置保留无需重新注册 |
| 查看 Runner 日志 | `D:\actions-runner\_diag\` 目录下的 `.log` 文件 |
| 查看 Runner 状态 | `gh api repos/Laecatain/reader-app/actions/runners` |

## Issue 自动修复

### 触发方式

在 Issue 上添加 `auto-fix` 标签，Runner 会自动：

1. 拉取代码
2. 创建分支 `auto-fix/issue-<编号>`
3. 执行 Claude Code 分析并实现修复
4. 推送修改并创建 PR

### 使用

```text
1. 用户创建 Issue
2. 维护者添加 auto-fix 标签
3. Workflow 自动触发
4. Claude 分析 → 实现 → 提 PR
5. 人工 review PR 后合并
```

### issue-fix.yml

```yaml
name: Claude Auto-Fix from Issue

on:
  issues:
    types: [opened]

jobs:
  fix:
    if: contains(github.event.issue.labels.*.name, 'auto-fix')
    runs-on: self-hosted
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Configure git
        shell: pwsh
        run: |
          git config user.name "Claude Auto-Fix"
          git config user.email "claude@local-runner"

      - name: Create fix branch
        shell: pwsh
        env:
          ISSUE_NUMBER: ${{ github.event.issue.number }}
        run: |
          git checkout -b auto-fix/issue-$env:ISSUE_NUMBER

      - name: Claude analyze and implement fix
        shell: pwsh
        env:
          ISSUE_TITLE: ${{ github.event.issue.title }}
          ISSUE_BODY: ${{ github.event.issue.body }}
          ISSUE_NUMBER: ${{ github.event.issue.number }}
          GH_TOKEN: ${{ github.token }}
        run: |
          $body = @"
          You are working in the repository cloned to the current directory.

          GitHub Issue #$env:ISSUE_NUMBER
          Title: $env:ISSUE_TITLE
          Body:

          $env:ISSUE_BODY

          Your task:
          1. Analyze the codebase to understand the issue
          2. Implement the fix by editing relevant files
          3. Stage changes with git add
          4. Commit with message: "fix: $env:ISSUE_TITLE"
          5. Push to origin auto-fix/issue-$env:ISSUE_NUMBER
          "@

          $body | claude 2>&1

      - name: Create PR if changes pushed
        shell: pwsh
        env:
          ISSUE_NUMBER: ${{ github.event.issue.number }}
          ISSUE_TITLE: ${{ github.event.issue.title }}
          GH_TOKEN: ${{ github.token }}
        run: |
          $branch = "auto-fix/issue-$env:ISSUE_NUMBER"
          $has_commits = git rev-list --count origin/$branch...$branch 2>$null
          if ($LASTEXITCODE -eq 0 -and $has_commits -gt 0) {
            gh pr create `
              --base TabRow-HorizontalPager `
              --head $branch `
              --title "fix: $env:ISSUE_TITLE" `
              --body "Auto-fix for issue #$env:ISSUE_NUMBER" `
              --label auto-fix
          } else {
            gh issue comment $env:ISSUE_NUMBER `
              --body "Claude could not produce an automatic fix."
          }
```
