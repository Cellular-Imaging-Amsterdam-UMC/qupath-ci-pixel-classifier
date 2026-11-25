param(
    [Parameter(Mandatory)] [string] $QuPathConsole,
    [Parameter(Mandatory)] [string] $Image,
    [string] $Annotations
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot
$qupathExe = (Resolve-Path -LiteralPath $QuPathConsole).Path
$imagePath = (Resolve-Path -LiteralPath $Image).Path
$annotationsPath = if ($Annotations) { (Resolve-Path -LiteralPath $Annotations).Path } else { '' }

Push-Location $repo
try {
    & .\gradlew.bat jar testClasses
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

    $tag = [guid]::NewGuid().ToString('N').Substring(0, 12)
    $work = Join-Path $repo "build\desktop-smoke-$tag"
    $userDir = Join-Path $work 'qupath-user'
    $extensions = Join-Path $userDir 'extensions'
    $service = Join-Path $work 'smoke-jar\META-INF\services'
    New-Item -ItemType Directory -Force $extensions,$service | Out-Null
    $jar = Get-ChildItem build/libs/qupath-ci-pixel-classifier-*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    Copy-Item -LiteralPath $jar.FullName -Destination $extensions
    'qupath.ext.cipixel.smoke.DesktopSmokeExtension' | Set-Content -Encoding utf8 (Join-Path $service 'qupath.lib.gui.extensions.QuPathExtension')
    & jar --create --file (Join-Path $extensions 'ci-desktop-smoke.jar') -C (Join-Path $work 'smoke-jar') META-INF -C build/classes/java/test qupath/ext/cipixel/smoke
    if ($LASTEXITCODE -ne 0) { throw "Smoke extension packaging failed." }

    $bootstrap = Join-Path $work 'bootstrap.groovy'
    @'
import qupath.lib.gui.prefs.PathPrefs
PathPrefs.userPathProperty().set(System.getProperty("ci.pixel.smoke.user"))
PathPrefs.imageTypeSettingProperty().set(PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
PathPrefs.savePreferences()
'@ | Set-Content -Encoding utf8 $bootstrap
    $pref = "-Dqupath.prefs.name=io.github.qupath/ci-pixel-smoke-$tag"

    function Invoke-QuPath($phase, [string[]] $arguments) {
        $quoted = $arguments | ForEach-Object { '"' + $_ + '"' }
        $stdout = Join-Path $work "$phase.log"
        $stderr = Join-Path $work "$phase.stderr.log"
        $process = Start-Process -FilePath $qupathExe -ArgumentList $quoted -WorkingDirectory $work -WindowStyle Hidden `
            -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        if (!$process.WaitForExit(115000)) {
            $process.Kill($true)
            throw "QuPath timed out. See $stdout and $stderr."
        }
        if ($process.ExitCode -ne 0) { throw "QuPath exited with code $($process.ExitCode). See $stdout and $stderr." }
    }

    Invoke-QuPath 'bootstrap' @('-q', $pref, "-Dci.pixel.smoke.user=$userDir", 'script', $bootstrap)
    $launch = @('-q', $pref, "-Dci.pixel.smoke.output=$work", '-i', $imagePath)
    if ($annotationsPath) { $launch += "-Dci.pixel.smoke.annotations=$annotationsPath" }
    Invoke-QuPath 'train' $launch
    if (!(Test-Path (Join-Path $work 'trained.ok'))) {
        Get-Content (Join-Path $work 'error.txt') -ErrorAction SilentlyContinue | Write-Host
        throw "Desktop training test failed. See $work."
    }
    Invoke-QuPath 'restore' ($launch + '-Dci.pixel.smoke.restore=true')
    if (!(Test-Path (Join-Path $work 'restored.ok'))) {
        Get-Content (Join-Path $work 'error.txt') -ErrorAction SilentlyContinue | Write-Host
        throw "Settings restoration test failed. See $work."
    }
    Write-Host "Desktop smoke test passed. Logs, native JSON files and screenshots are in $work."
} finally {
    Pop-Location
}
