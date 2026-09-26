# ClipVault 자동 업데이트 교체 스크립트 (Updater.install 이 띄운다)
# 1) 앱이 종료되길 기다린다  2) 기존 폴더 -> .old  3) 새 폴더 -> 원래 자리  4) 앱 재실행  5) .old 삭제
# 중간에 실패하면 .old 를 되돌려 기존 버전으로 다시 켠다.
param(
    [int]$ProcId,
    [string]$AppDir,
    [string]$NewDir,
    [string]$Staging,
    [string]$Exe
)
$ErrorActionPreference = 'Stop'
$old = "$AppDir.old"

# 1) 앱 종료 대기 (최대 30초). jpackage exe는 실행기 + 자바 프로세스 두 개로 뜨므로
#    넘겨받은 자바 PID뿐 아니라 이 폴더의 exe로 실행 중인 프로세스가 모두 끝날 때까지 기다린다.
$exePath = Join-Path $AppDir $Exe
try { Wait-Process -Id $ProcId -Timeout 30 } catch { }
try { Get-Process | Where-Object { $_.Path -eq $exePath } | Wait-Process -Timeout 30 } catch { }

# 2) 기존 폴더를 치운다. 파일 잠금이 늦게 풀릴 수 있어 몇 번 재시도한다.
if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force }
$moved = $false
for ($i = 0; $i -lt 20 -and -not $moved; $i++) {
    try { Move-Item -LiteralPath $AppDir -Destination $old; $moved = $true }
    catch { Start-Sleep -Milliseconds 500 }
}
if (-not $moved) {
    Write-Output "could not move $AppDir"
    Start-Process -FilePath (Join-Path $AppDir $Exe)
    exit 1
}

# 3) 새 폴더를 원래 자리로. 실패하면 되돌린다.
try {
    Move-Item -LiteralPath $NewDir -Destination $AppDir
} catch {
    Write-Output "install failed: $_"
    Move-Item -LiteralPath $old -Destination $AppDir
    Start-Process -FilePath (Join-Path $AppDir $Exe)
    exit 1
}

# 4) 새 버전 실행  5) 정리
Start-Process -FilePath (Join-Path $AppDir $Exe)
Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $Staging -Recurse -Force -ErrorAction SilentlyContinue
Write-Output "updated"
