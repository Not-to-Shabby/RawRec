$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$classesDir = Join-Path $root 'app\build\tmp\kotlin-classes\debug'
if (-not (Test-Path (Join-Path $classesDir 'dev\rawrec\tool\Rvtool.class'))) {
    & "$root\gradlew.bat" -p "$root" :app:assembleDebug --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
}

# Locate a jar in the gradle cache by artifact directory + name filter (newest, non-sources).
function Find-CacheJar($artifactDir, $filter) {
    Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\$artifactDir" -Recurse -Filter $filter -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|common|metadata' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

$kotlinStdlib = Find-CacheJar 'org.jetbrains.kotlin\kotlin-stdlib' 'kotlin-stdlib-2*.jar'
if (-not $kotlinStdlib) { throw "kotlin-stdlib jar not found in gradle cache" }

$javacClasses = Join-Path $root 'app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$libsDir = if (Test-Path (Join-Path $PSScriptRoot 'libs')) { Join-Path $PSScriptRoot 'libs' } else { Join-Path $root 'tools\libs' }
$cpBase = "$classesDir;$javacClasses;$kotlinStdlib;$libsDir\*"

# ---- GUI (desktop-only source; Swing is absent from android.jar so it cannot
# ----  live in the android source sets). Compiled on demand against the real
# ----  JDK with the embeddable Kotlin compiler from the gradle cache, plus
# ----  the compiler's own runtime jars (script-runtime, coroutines, trove4j,
# ----  annotations).
$guiSrc = if (Test-Path (Join-Path $PSScriptRoot 'gui\RvtoolGui.kt')) { Join-Path $PSScriptRoot 'gui\RvtoolGui.kt' } else { Join-Path $root 'tools\gui\RvtoolGui.kt' }
$guiOut = Join-Path $root 'app\build\tmp\kotlin-classes\rvtool-gui'
$guiStamp = "$guiOut\.stamp"
$needGui = -not (Test-Path $guiStamp)
if (Test-Path $guiSrc) {
    if (Test-Path $guiStamp) {
        $last = Get-Content $guiStamp -ErrorAction SilentlyContinue
        $needGui = ($last -ne (Get-Item $guiSrc).LastWriteTimeUtc.ToString('o'))
    }
    if ($needGui) {
        $compiler = Find-CacheJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable' 'kotlin-compiler-embeddable-*.jar'
        $scriptRt  = Find-CacheJar 'org.jetbrains.kotlin\kotlin-script-runtime' 'kotlin-script-runtime-*.jar'
        $corout    = Find-CacheJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm' 'kotlinx-coroutines-core-jvm-1.9.0.jar'
        $trove     = Find-CacheJar 'org.jetbrains.intellij.deps\trove4j' 'trove4j-*.jar'
        $annot     = Find-CacheJar 'org.jetbrains\annotations' 'annotations-*.jar'
        foreach ($j in @($compiler, $scriptRt, $corout, $trove, $annot)) {
            if (-not $j) { throw "GUI compiler jar missing from gradle cache (component: $($null -eq $j))" }
        }
        New-Item -ItemType Directory -Force -Path $guiOut | Out-Null
        $compCp = "$compiler;$kotlinStdlib;$scriptRt;$corout;$trove;$annot"
        & java "-Xshare:off" -cp $compCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
            -classpath "$classesDir;$javacClasses;$kotlinStdlib" -d $guiOut $guiSrc
        if ($LASTEXITCODE -ne 0) { throw "RvtoolGui.kt compile failed" }
        (Get-Item $guiSrc).LastWriteTimeUtc.ToString('o') | Set-Content $guiStamp
    }
}

$cp = if (Test-Path (Join-Path $guiOut 'dev\rawrec\tool\RvtoolGui.class')) { "$guiOut;$cpBase" } else { $cpBase }
& java "-Xmx2g" "-XX:+UseG1GC" "-Dsun.java2d.d3d=true" "-Dsun.java2d.ddforcevram=true" "-Dsun.java2d.transaccel=true" -cp $cp dev.rawrec.tool.Rvtool @args
