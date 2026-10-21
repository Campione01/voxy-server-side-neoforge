$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskRoot='C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1'
$taskRepos=@($taskRoot,'C:/Users/Administrator/Desktop/voxyserverside')
function Get-ArchiveHashes([string]$taskPath) {
    $taskZip=[System.IO.Compression.ZipFile]::OpenRead($taskPath)
    $taskHashes=@{}
    try {
        foreach($taskEntry in $taskZip.Entries) {
            if($taskEntry.FullName.EndsWith('/')) { continue }
            $taskStream=$taskEntry.Open()
            $taskHasher=[System.Security.Cryptography.SHA256]::Create()
            try {
                if($taskEntry.FullName -eq 'META-INF/MANIFEST.MF') {
                    $taskReader=[System.IO.StreamReader]::new($taskStream)
                    try { $taskText=$taskReader.ReadToEnd() -replace '(?m)^Implementation-Timestamp:[^\r\n]*\r?\n','' }
                    finally {$taskReader.Dispose()}
                    $taskDigest=$taskHasher.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($taskText))
                }else{$taskDigest=$taskHasher.ComputeHash($taskStream)}
                $taskHashes[$taskEntry.FullName]=[BitConverter]::ToString($taskDigest).Replace('-','').ToLowerInvariant()
            }
            finally { $taskHasher.Dispose();$taskStream.Dispose() }
        }
    }finally{$taskZip.Dispose()}
    return $taskHashes
}
$taskReports=@()
foreach($taskRepo in $taskRepos) {
    $taskJar=Get-ChildItem -LiteralPath ($taskRepo+'/lib/teleport-loading-20261017') -Filter '*.jar'
    if($taskJar.Count -ne 1){throw 'Expected one release jar'}
    $taskPrevious=Get-ChildItem -LiteralPath ($taskRepo+'/lib/visual-handoff-20261017') -Filter '*.jar'
    $taskOld=Get-ArchiveHashes $taskPrevious.FullName
    $taskNew=Get-ArchiveHashes $taskJar.FullName
    $taskChanges=@()
    foreach($taskName in (@($taskOld.Keys)+@($taskNew.Keys)|Sort-Object -Unique)) {
        if($taskOld[$taskName] -ne $taskNew[$taskName]) { $taskChanges+=$taskName }
    }
    $taskUnexpected=@($taskChanges|Where-Object { $_ -notmatch '^dev/xantha/vss/client/prediction/Prediction(TileManager|SchedulePreparation)(\$[^/]*)?\.class$' })
    if($taskUnexpected.Count -gt 0) {throw ('Unexpected release changes: '+($taskUnexpected -join ', '))}
    $taskSuites=@()
    foreach($taskSuitePath in @('teleport-repro','teleport-regression')) {
        $taskCount=0;$taskFailures=0;$taskErrors=0;$taskSkipped=0
        foreach($taskXmlPath in Get-ChildItem -LiteralPath ($taskRepo+'/build-teleport-loading-20261017/test-results/'+$taskSuitePath) -Filter '*.xml') {
            $taskXml=[xml](Get-Content -Raw -LiteralPath $taskXmlPath.FullName)
            $taskCount += [int]$taskXml.testsuite.tests;$taskFailures += [int]$taskXml.testsuite.failures
            $taskErrors += [int]$taskXml.testsuite.errors;$taskSkipped += [int]$taskXml.testsuite.skipped
        }
        if($taskCount -eq 0 -or $taskFailures -ne 0 -or $taskErrors -ne 0 -or $taskSkipped -ne 0){throw ('Invalid test suite '+$taskSuitePath)}
        $taskSuites+=@{suite=$taskSuitePath;tests=$taskCount;failures=$taskFailures;errors=$taskErrors;skipped=$taskSkipped}
    }
    $taskReport=@{repository=$taskRepo;jar=$taskJar.FullName;sha256=(Get-FileHash -LiteralPath $taskJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant();bytes=$taskJar.Length;changedEntries=$taskChanges;tests=$taskSuites}
    $taskBenchmark=$taskRepo+'/build-teleport-loading-20261017/results/teleport-schedule.json'
    if(Test-Path -LiteralPath $taskBenchmark){$taskReport.benchmark=Get-Content -Raw -LiteralPath $taskBenchmark|ConvertFrom-Json}
    $taskReports+=$taskReport
}
$taskJson=ConvertTo-Json -InputObject $taskReports -Depth 8
[System.IO.File]::WriteAllText(($taskRoot+'/tools/prediction/teleport-loading-20261017/artifact-audit.json'),$taskJson,[System.Text.UTF8Encoding]::new($false))
$taskJson
