# deploy.ps1 - 提交、推送、CI 编译、部署 jar 到本地 mods 目录
# 用法: .\scripts\deploy.ps1 "提交信息"
#       .\scripts\deploy.ps1              # 使用默认提交信息

param(
    [string]$Message = "auto commit $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path "$projectRoot\.git")) {
    $projectRoot = Split-Path -Parent $PSScriptRoot
}

$modDirs = @(
    "C:\Users\Natsume\Desktop\PCL2\server\mods",
    "C:\Users\Natsume\Desktop\PCL2\.minecraft\versions\1.21.7-Fabric 0.16.13\mods"
)

$env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")

# 1. commit
Write-Host ""
Write-Host "[1/5] Committing..." -ForegroundColor Cyan
Set-Location $projectRoot
$changed = git status --porcelain
if (-not $changed) {
    Write-Host "  No changes, skip." -ForegroundColor Yellow
} else {
    git add -A
    git commit -m $Message
    Write-Host "  Committed: $Message" -ForegroundColor Green
}

# 2. push
Write-Host ""
Write-Host "[2/5] Pushing..." -ForegroundColor Cyan
$branch = git branch --show-current
git push origin $branch 2>&1 | ForEach-Object { Write-Host "  $_" }
Write-Host "  Pushed." -ForegroundColor Green

# 3. wait for CI
Write-Host ""
Write-Host "[3/5] Waiting for CI..." -ForegroundColor Cyan
$remoteUrl = git remote get-url origin
if ($remoteUrl -match "github\.com[:/](.+?)/(.+?)(?:\.git)?$") {
    $repo = "$($Matches[1])/$($Matches[2])"
} else {
    Write-Host "  Cannot parse remote: $remoteUrl" -ForegroundColor Red
    exit 1
}

$token = gh auth token
$headers = @{
    "Accept" = "application/vnd.github+json"
    "Authorization" = "Bearer $token"
}

Start-Sleep -Seconds 5
$runs = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs?per_page=1&branch=$branch" -Headers $headers
$runId = $runs.workflow_runs[0].id
Write-Host "  Run ID: $runId"

$maxWait = 300
$elapsed = 0
while ($elapsed -lt $maxWait) {
    Start-Sleep -Seconds 15
    $elapsed += 15
    $run = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs/$runId" -Headers $headers
    $ts = Get-Date -Format "HH:mm:ss"
    Write-Host "  $ts $($run.status) ($elapsed s)"
    if ($run.status -eq "completed") { break }
}

if ($run.conclusion -ne "success") {
    Write-Host "  CI failed: $($run.conclusion)" -ForegroundColor Red
    exit 1
}
Write-Host "  CI passed!" -ForegroundColor Green

# 4. download jar
Write-Host ""
Write-Host "[4/5] Downloading jar..." -ForegroundColor Cyan
$dlDir = "$projectRoot\build\libs\deploy"
if (Test-Path $dlDir) { Remove-Item $dlDir -Recurse -Force }
gh run download $runId --name SyncMaterial-jar --dir $dlDir
$jar = Get-ChildItem $dlDir -Filter "*.jar" | Where-Object { $_.Name -notmatch "sources" } | Select-Object -First 1
if (-not $jar) {
    Write-Host "  No jar found" -ForegroundColor Red
    exit 1
}
$sizeMB = [math]::Round($jar.Length / 1MB, 1)
Write-Host "  Downloaded: $($jar.Name) ($sizeMB MB)" -ForegroundColor Green

# 5. deploy to mods
Write-Host ""
Write-Host "[5/5] Deploying to mods..." -ForegroundColor Cyan
foreach ($dir in $modDirs) {
    if (-not (Test-Path $dir)) {
        Write-Host "  Skip (not found): $dir" -ForegroundColor Yellow
        continue
    }
    Get-ChildItem $dir -Filter "SyncMaterial-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $jar.FullName "$dir\$($jar.Name)"
    Write-Host "  $dir\$($jar.Name)" -ForegroundColor Green
}

Remove-Item $dlDir -Recurse -Force

Write-Host ""
Write-Host "Done!" -ForegroundColor Cyan