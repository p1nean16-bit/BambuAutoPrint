param([string[]]$Tasks = @('assembleDebug'), [switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = 'D:\OpenJDK 17\install\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'D:\Android SDK'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = 'D:\Gradle 8.9\cache'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$gradlePath = 'D:\Gradle 8.9\install\gradle-8.9\bin\gradle.bat'
$propertiesPath = Join-Path $projectPath 'gradle.properties'
$originalBytes = [IO.File]::ReadAllBytes($propertiesPath)
try {
    # 仅构建时沿用电脑的代理；不把机器相关配置写进交付项目。
    if ($env:HTTPS_PROXY) {
        $proxyUri = [Uri]$env:HTTPS_PROXY
        $hostName = $proxyUri.Host
        $portNumber = $proxyUri.Port
        Add-Content -LiteralPath $propertiesPath -Value "`nsystemProp.http.proxyHost=$hostName`nsystemProp.http.proxyPort=$portNumber`nsystemProp.https.proxyHost=$hostName`nsystemProp.https.proxyPort=$portNumber`norg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -Dhttp.proxyHost=$hostName -Dhttp.proxyPort=$portNumber -Dhttps.proxyHost=$hostName -Dhttps.proxyPort=$portNumber"
    }
    $arguments = @('--project-dir', $projectPath, '--no-daemon', '--max-workers=2')
    if ($Offline) { $arguments += '--offline' }
    & $gradlePath @arguments @Tasks
    $buildExit = $LASTEXITCODE
} finally { [IO.File]::WriteAllBytes($propertiesPath, $originalBytes) }
exit $buildExit
