[CmdletBinding()]
param(
    [ValidateSet('x64')]
    [string] $Target = 'x64',
    [ValidateSet('Debug', 'RelWithDebInfo', 'Release', 'MinSizeRel')]
    [string] $Configuration = 'RelWithDebInfo',
    # Inno Setup 설치 파일도 만든다 — build_<Target>/installer-Windows.generated.iss (cmake/windows/helpers.cmake)
    [switch] $BuildInstaller = $false
)

$ErrorActionPreference = 'Stop'

if ( $DebugPreference -eq 'Continue' ) {
    $VerbosePreference = 'Continue'
    $InformationPreference = 'Continue'
}

if ( $env:CI -eq $null ) {
    throw "Package-Windows.ps1 requires CI environment"
}

if ( ! ( [System.Environment]::Is64BitOperatingSystem ) ) {
    throw "Packaging script requires a 64-bit system to build and run."
}

if ( $PSVersionTable.PSVersion -lt '7.2.0' ) {
    Write-Warning 'The packaging script requires PowerShell Core 7. Install or upgrade your PowerShell version: https://aka.ms/pscore6'
    exit 2
}

function Package {
    trap {
        Write-Error $_
        exit 2
    }

    $ScriptHome = $PSScriptRoot
    $ProjectRoot = Resolve-Path -Path "$PSScriptRoot/../.."
    $BuildSpecFile = "${ProjectRoot}/buildspec.json"

    $UtilityFunctions = Get-ChildItem -Path $PSScriptRoot/utils.pwsh/*.ps1 -Recurse

    foreach( $Utility in $UtilityFunctions ) {
        Write-Debug "Loading $($Utility.FullName)"
        . $Utility.FullName
    }

    $BuildSpec = Get-Content -Path ${BuildSpecFile} -Raw | ConvertFrom-Json
    $ProductName = $BuildSpec.name
    $ProductVersion = $BuildSpec.version

    $OutputName = "${ProductName}-${ProductVersion}-windows-${Target}"
    $StageDir = "${ProjectRoot}/release/${Configuration}/${ProductName}"

    $RemoveArgs = @{
        ErrorAction = 'SilentlyContinue'
        Path = @(
            "${ProjectRoot}/release/${ProductName}-*-windows-*.zip"
            "${ProjectRoot}/release/${ProductName}-*-windows-*-installer.exe"
        )
    }

    Remove-Item @RemoveArgs

    # 빠진 파일이 있는 채로 zip·설치 파일을 내지 않는다 — 독 페이지(data/ui)는 dock-ui 잡 산출물이다.
    $RequiredFiles = @(
        "${StageDir}/bin/64bit/${ProductName}.dll"
        "${StageDir}/data/locale/en-US.ini"
        "${StageDir}/data/ui/index.html"
    )
    foreach( $RequiredFile in $RequiredFiles ) {
        if ( ! ( Test-Path -Path $RequiredFile -PathType Leaf ) ) {
            throw "Missing file for packaging: ${RequiredFile}"
        }
    }

    Log-Group "Archiving ${ProductName}..."
    # $CompressArgs = @{
    #     Path = (Get-ChildItem -Path "${ProjectRoot}/release/${Configuration}" -Exclude "${OutputName}*.*")
    #     CompressionLevel = 'Optimal'
    #     DestinationPath = "${ProjectRoot}/release/${OutputName}.zip"
    #     Verbose = ($Env:CI -ne $null)
    # }
    # Compress-Archive -Force @CompressArgs
    
    if (-not ([AppDomain]::CurrentDomain.GetAssemblies() | Where-Object { $_.GetType('System.IO.Compression.ZipFile', $false) })) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
    }
    $zipf = New-Object System.IO.Compression.ZipArchive -ArgumentList ([System.IO.File]::OpenWrite("${ProjectRoot}/release/${OutputName}.zip"), [System.IO.Compression.ZipArchiveMode]::Create)
    function AddDirectory{
        param( [string] $DestDir, [string] $SourceDir )
        Log-Information "Adding directory: ${SourceDir} to ${DestDir}"
        $Entries = Get-ChildItem -Path $SourceDir -Recurse
        foreach( $Entry in $Entries ) {
            $RelativePath = $Entry.FullName.Substring($SourceDir.Length).TrimStart('\') -replace '\\', '/'
            $ZipEntryPath = "$DestDir/${RelativePath}"
            if ( $Entry.PSIsContainer -eq $false ) {
                Log-Information "Adding file entry: ${ZipEntryPath}"
                $stream = $zipf.CreateEntry($ZipEntryPath, [System.IO.Compression.CompressionLevel]::Optimal)
                $fileStream = [System.IO.File]::OpenRead($Entry.FullName)
                $entryStream = $stream.Open()
                $fileStream.CopyTo($entryStream)
                $entryStream.Close()
                $fileStream.Close()
            }
        }
    }
    AddDirectory "obs-plugins/64bit" "${StageDir}/bin/64bit"
    AddDirectory "data/obs-plugins/${ProductName}" "${StageDir}/data"
    $zipf.Dispose()
    Log-Group

    if ( $BuildInstaller ) {
        $IssFile = "${ProjectRoot}/build_${Target}/installer-Windows.generated.iss"
        if ( ! ( Test-Path -Path $IssFile -PathType Leaf ) ) {
            throw "InnoSetup install script not found: ${IssFile}. Run the build script first."
        }

        $Iscc = (Get-Command -Name 'iscc' -ErrorAction SilentlyContinue).Source
        if ( ! $Iscc ) {
            $Iscc = Join-Path -Path ${env:ProgramFiles(x86)} -ChildPath 'Inno Setup 6/ISCC.exe'
        }
        if ( ! ( Test-Path -Path $Iscc -PathType Leaf ) ) {
            throw "InnoSetup compiler (iscc) not found."
        }

        Log-Group "Building ${ProductName} installer..."
        $StageDirNative = (Resolve-Path -Path $StageDir).Path
        $ReleaseDirNative = (Resolve-Path -Path "${ProjectRoot}/release").Path
        Invoke-External $Iscc "/DStageDir=${StageDirNative}" "/O${ReleaseDirNative}" "/F${OutputName}-installer" $IssFile
        Log-Group
    }
}

Package
