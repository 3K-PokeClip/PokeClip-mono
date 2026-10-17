[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $Installer,
    [string] $ProductName = 'pokeclip-obs',
    [string] $DisplayName = 'PokeClip for OBS'
)

# Windows 설치 파일 무인 시험 (POK-361) — CI 러너 전용. ProgramData·HKLM·APPDATA를 직접 만지므로 실제 PC에서는 돌리지 않는다.
#  1. OBS가 켜져 있으면(OBSStudioCore 뮤텍스) 설치가 멈추지 않고 실패로 끝나며 아무것도 깔리지 않는다
#  2. ProgramData\obs-studio\plugins\<이름>에 깔리고, 가짜 OBS 폴더의 예전 zip 수동 설치본만 지워진다
#  3. 다시 설치하면 data의 옛 파일이 남지 않는다
#  4. 제거하면 플러그인 폴더·제거 항목만 사라지고 다른 플러그인·plugin_config·OBS 파일은 남는다
#  5. OBS 레지스트리가 없어도 무인 설치는 경고 기본값(계속)으로 끝난다

$ErrorActionPreference = 'Stop'

if ( $env:CI -eq $null ) {
    throw 'Test-WindowsInstaller.ps1 requires CI environment (it modifies ProgramData, HKLM and APPDATA).'
}

$Installer = (Resolve-Path -Path $Installer).Path
$PluginsRoot = Join-Path -Path $env:ProgramData -ChildPath 'obs-studio\plugins'
$PluginDir = Join-Path -Path $PluginsRoot -ChildPath $ProductName
$OtherPluginDll = Join-Path -Path $PluginsRoot -ChildPath 'other-plugin\bin\64bit\other-plugin.dll'
$FakeObs = Join-Path -Path $env:RUNNER_TEMP -ChildPath 'fake-obs'
$ObsExe = Join-Path -Path $FakeObs -ChildPath 'bin\64bit\obs64.exe'
$OldDll = Join-Path -Path $FakeObs -ChildPath "obs-plugins\64bit\${ProductName}.dll"
$OldData = Join-Path -Path $FakeObs -ChildPath "data\obs-plugins\${ProductName}"
$ConfigFile = Join-Path -Path $env:APPDATA -ChildPath "obs-studio\plugin_config\${ProductName}\pokeclip.json"
$ObsRegKey = 'HKLM:\SOFTWARE\OBS Studio'
$LogDir = Join-Path -Path $env:RUNNER_TEMP -ChildPath 'installer-logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Assert-That {
    param( [bool] $Condition, [string] $Message )
    if ( ! $Condition ) {
        throw "FAIL: ${Message}"
    }
    Write-Host "ok - ${Message}"
}

function New-File {
    param( [string] $Path, [string] $Content = 'x' )
    New-Item -ItemType Directory -Force -Path (Split-Path -Path $Path -Parent) | Out-Null
    Set-Content -Path $Path -Value $Content
}

function Invoke-Setup {
    param( [string] $Exe, [string] $Name, [int] $TimeoutSeconds = 180 )
    $Log = Join-Path -Path $LogDir -ChildPath "${Name}.log"
    $Process = Start-Process -FilePath $Exe -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LOG=${Log}") -PassThru
    # 핸들을 먼저 잡아 두지 않으면 -PassThru 프로세스의 ExitCode가 $null로 남는다.
    $null = $Process.Handle
    if ( ! $Process.WaitForExit($TimeoutSeconds * 1000) ) {
        $Process.Kill()
        throw "FAIL: ${Name} did not finish within ${TimeoutSeconds}s (log: ${Log})"
    }
    if ( $null -eq $Process.ExitCode ) {
        throw "FAIL: ${Name} exit code unavailable"
    }
    Write-Host "${Name}: exit $($Process.ExitCode)"
    return $Process.ExitCode
}

function Wait-Gone {
    param( [string] $Path, [int] $TimeoutSeconds = 60 )
    $Deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ( ( Test-Path -Path $Path ) -and ( (Get-Date) -lt $Deadline ) ) {
        Start-Sleep -Milliseconds 500
    }
    return ! ( Test-Path -Path $Path )
}

function Get-UninstallEntry {
    Get-ChildItem -Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall' |
        Where-Object { $_.GetValue('DisplayName') -eq $DisplayName }
}

function Invoke-Uninstall {
    param( [string] $Name )
    $Uninstaller = Join-Path -Path $PluginDir -ChildPath 'uninstall\unins000.exe'
    Assert-That (Test-Path -Path $Uninstaller -PathType Leaf) "uninstaller exists (${Name})"
    # 제거 프로그램은 임시 폴더로 자신을 복사해 2단계로 돈다 — 종료 코드 뒤 폴더가 사라질 때까지 기다린다.
    $ExitCode = Invoke-Setup -Exe $Uninstaller -Name $Name
    Assert-That ($ExitCode -eq 0) "${Name} exits 0"
    Assert-That (Wait-Gone -Path $PluginDir) "${Name} removes ${PluginDir}"
}

try {
    Write-Host "Installer: ${Installer}"
    $Signature = Get-AuthenticodeSignature -FilePath $Installer
    Write-Host "Authenticode status: $($Signature.Status) signer: $($Signature.SignerCertificate.Subject)"

    # 준비: 가짜 OBS(레지스트리 + obs64.exe) · 예전 zip 수동 설치본 · 다른 플러그인 · 페어링 설정
    New-File -Path $ObsExe
    New-File -Path $OldDll
    New-File -Path (Join-Path -Path $OldData -ChildPath 'locale\en-US.ini')
    New-File -Path $OtherPluginDll
    New-File -Path $ConfigFile -Content '{"streamid":"test"}'
    New-Item -Path $ObsRegKey -Force | Out-Null
    Set-ItemProperty -Path $ObsRegKey -Name '(default)' -Value $FakeObs

    # 1. OBS 실행 중
    $Mutex = [System.Threading.Mutex]::new($false, 'OBSStudioCore')
    try {
        $ExitCode = Invoke-Setup -Exe $Installer -Name 'install-while-obs-running' -TimeoutSeconds 60
    } finally {
        $Mutex.Dispose()
    }
    Assert-That ($ExitCode -ne 0) 'setup refuses while OBSStudioCore mutex exists'
    Assert-That (! ( Test-Path -Path $PluginDir )) 'nothing installed while OBS is running'
    Assert-That (Test-Path -Path $OldDll) 'old manual copy untouched while OBS is running'

    # 2. 설치
    $ExitCode = Invoke-Setup -Exe $Installer -Name 'install'
    Assert-That ($ExitCode -eq 0) 'install exits 0'
    foreach( $Relative in @("bin\64bit\${ProductName}.dll", 'data\locale\en-US.ini', 'data\ui\index.html', 'LICENSE.txt', 'uninstall\unins000.exe') ) {
        Assert-That (Test-Path -Path (Join-Path -Path $PluginDir -ChildPath $Relative) -PathType Leaf) "installed ${Relative}"
    }
    Assert-That (@(Get-ChildItem -Path (Join-Path -Path $PluginDir -ChildPath 'bin\64bit') -Filter '*.pdb').Count -eq 0) 'no pdb in installer payload'
    Assert-That (! ( Test-Path -Path $OldDll )) 'old manual dll removed from OBS folder'
    Assert-That (! ( Test-Path -Path $OldData )) 'old manual data removed from OBS folder'
    Assert-That (Test-Path -Path $ObsExe) 'OBS files untouched'
    Assert-That (@(Get-UninstallEntry).Count -eq 1) 'one uninstall entry registered'

    # 3. 다시 설치 — data의 옛 파일이 남지 않는다
    $Stale = Join-Path -Path $PluginDir -ChildPath 'data\ui\stale-old.js'
    New-File -Path $Stale
    $ExitCode = Invoke-Setup -Exe $Installer -Name 'reinstall'
    Assert-That ($ExitCode -eq 0) 'reinstall exits 0'
    Assert-That (! ( Test-Path -Path $Stale )) 'stale data file removed on reinstall'
    Assert-That (Test-Path -Path (Join-Path -Path $PluginDir -ChildPath 'data\ui\index.html')) 'ui still present after reinstall'
    Assert-That (@(Get-UninstallEntry).Count -eq 1) 'still one uninstall entry after reinstall'

    # 4. 제거
    Invoke-Uninstall -Name 'uninstall'
    Assert-That (@(Get-UninstallEntry).Count -eq 0) 'uninstall entry removed'
    Assert-That (Test-Path -Path $OtherPluginDll) 'other plugin kept'
    Assert-That (Test-Path -Path $ConfigFile) 'plugin_config kept'
    Assert-That (Test-Path -Path $ObsExe) 'OBS files kept'

    # 5. OBS 레지스트리 없음 — 무인 실행은 경고 기본값(계속)
    Remove-Item -Path $ObsRegKey -Recurse -Force
    $ExitCode = Invoke-Setup -Exe $Installer -Name 'install-without-obs'
    Assert-That ($ExitCode -eq 0) 'install without OBS registry exits 0 (warning defaults to continue)'
    Assert-That (Test-Path -Path (Join-Path -Path $PluginDir -ChildPath "bin\64bit\${ProductName}.dll")) 'installed without OBS registry'
    Invoke-Uninstall -Name 'uninstall-without-obs'
} finally {
    Remove-Item -Path $ObsRegKey -Recurse -Force -ErrorAction SilentlyContinue
    foreach( $Log in Get-ChildItem -Path $LogDir -Filter '*.log' -ErrorAction SilentlyContinue ) {
        Write-Host "::group::$($Log.Name)"
        Get-Content -Path $Log.FullName
        Write-Host '::endgroup::'
    }
}
