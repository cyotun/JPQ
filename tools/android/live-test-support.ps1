param([int]$SignalProcessId = 0)
$script:JpqEvidenceSupportPath=$PSCommandPath

# Loading this file does not invoke ADB or record a screen. The isolated signal
# helper attaches only to a console explicitly created for one owned child.
if (-not ('JpqEvidence.NativeConsole' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace JpqEvidence {
    public static class NativeConsole {
        [StructLayout(LayoutKind.Sequential)] struct SecurityAttributes {
            public int Length; public IntPtr Descriptor; public int Inherit;
        }
        [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct StartupInfo {
            public int Size; public string Reserved; public string Desktop; public string Title;
            public int X, Y, XSize, YSize, XChars, YChars, FillAttribute, Flags;
            public short ShowWindow, Reserved2; public IntPtr ReservedBytes, Input, Output, Error;
        }
        [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
            public IntPtr Process, Thread; public uint ProcessId, ThreadId;
        }
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
        static extern IntPtr CreateFile(string path, uint access, uint share,
            ref SecurityAttributes security, uint creation, uint flags, IntPtr template);
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
        static extern bool CreateProcess(string app, StringBuilder command, IntPtr processSecurity,
            IntPtr threadSecurity, bool inherit, uint flags, IntPtr environment,
            string directory, ref StartupInfo startup, out ProcessInfo process);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool AttachConsole(uint processId);
        [DllImport("kernel32.dll")] static extern bool FreeConsole();
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool GenerateConsoleCtrlEvent(uint type, uint group);

        static string Quote(string value) {
            if (value.Length > 0 && value.IndexOfAny(new char[] {' ', '\t', '\n', '\v', '"'}) < 0) return value;
            var result=new StringBuilder("\""); int slashes=0;
            foreach (char c in value) {
                if (c=='\\') { slashes++; continue; }
                if (c=='"') result.Append('\\', slashes*2+1).Append(c);
                else result.Append('\\', slashes).Append(c);
                slashes=0;
            }
            return result.Append('\\', slashes*2).Append('"').ToString();
        }
        static void Validate(IntPtr handle) {
            if (handle==new IntPtr(-1)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        public static Process Start(string executable, string[] arguments, string directory,
            string output, string error, string adbPath) {
            var security=new SecurityAttributes { Length=Marshal.SizeOf<SecurityAttributes>(), Inherit=1 };
            IntPtr stdout=IntPtr.Zero, stderr=IntPtr.Zero, stdin=IntPtr.Zero, env=IntPtr.Zero;
            try {
                stdout=CreateFile(output, 0x40000000, 1, ref security, 2, 0x80, IntPtr.Zero); Validate(stdout);
                stderr=CreateFile(error, 0x40000000, 1, ref security, 2, 0x80, IntPtr.Zero); Validate(stderr);
                stdin=CreateFile("NUL", 0x80000000, 3, ref security, 3, 0x80, IntPtr.Zero); Validate(stdin);
                var command=new StringBuilder(Quote(executable));
                foreach (string argument in arguments) command.Append(' ').Append(Quote(argument));
                var variables=new System.Collections.Generic.SortedDictionary<string,string>(StringComparer.OrdinalIgnoreCase);
                foreach (System.Collections.DictionaryEntry pair in Environment.GetEnvironmentVariables())
                    variables[pair.Key.ToString()]=pair.Value.ToString();
                if (!String.IsNullOrEmpty(adbPath)) variables["ADB"]=adbPath;
                var environment=new StringBuilder();
                foreach (var pair in variables) environment.Append(pair.Key).Append('=').Append(pair.Value).Append('\0');
                environment.Append('\0'); env=Marshal.StringToHGlobalUni(environment.ToString());
                var startup=new StartupInfo { Size=Marshal.SizeOf<StartupInfo>(), Flags=0x101,
                    ShowWindow=0, Input=stdin, Output=stdout, Error=stderr };
                ProcessInfo info;
                // A separate, hidden console prevents Ctrl+C from reaching the
                // assistant's terminal, another recorder, or any device app.
                if (!CreateProcess(executable, command, IntPtr.Zero, IntPtr.Zero, true,
                    0x00000410, env, directory, ref startup, out info))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                try {
                    var child=Process.GetProcessById((int)info.ProcessId);
                    // Retain a query handle before the child exits. A lazily
                    // attached Process can otherwise lose its exit code.
                    IntPtr retained=child.Handle;
                    return child;
                } finally { CloseHandle(info.Thread); CloseHandle(info.Process); }
            } finally {
                if (stdout!=IntPtr.Zero && stdout!=new IntPtr(-1)) CloseHandle(stdout);
                if (stderr!=IntPtr.Zero && stderr!=new IntPtr(-1)) CloseHandle(stderr);
                if (stdin!=IntPtr.Zero && stdin!=new IntPtr(-1)) CloseHandle(stdin);
                if (env!=IntPtr.Zero) Marshal.FreeHGlobal(env);
            }
        }
        public static void Signal(uint processId) {
            // Only called inside a disposable helper launched without a console.
            FreeConsole();
            if (!AttachConsole(processId)) throw new Win32Exception(Marshal.GetLastWin32Error());
            try {
                if (!SetConsoleCtrlHandler(IntPtr.Zero, true)) throw new Win32Exception(Marshal.GetLastWin32Error());
                if (!GenerateConsoleCtrlEvent(0, 0)) throw new Win32Exception(Marshal.GetLastWin32Error());
                System.Threading.Thread.Sleep(150);
            } finally { FreeConsole(); }
        }
    }
}
'@
}

if ($SignalProcessId -gt 0) {
    [JpqEvidence.NativeConsole]::Signal([uint32]$SignalProcessId)
    return
}

function Get-JpqForegroundState([string]$Activity, [string]$Policy, [string]$Power, [switch]$SyntheticProbe) {
    # Only display 0 is recorded. A rear-panel launcher can be resumed at the
    # same time on display 1; it must neither veto nor authorize display 0.
    $displayHeaders=@([regex]::Matches($Activity,'(?m)^\s*Display #(\d+) \(activities from top to bottom\):\s*$'))
    $activityScope=$Activity
    if($displayHeaders.Count -gt 0) {
        $primary=@($displayHeaders | Where-Object { $_.Groups[1].Value -eq '0' })
        if($primary.Count -ne 1) { $activityScope='' }
        else {
            $start=$primary[0].Index+$primary[0].Length
            $next=@($displayHeaders | Where-Object { $_.Index -gt $primary[0].Index } | Select-Object -First 1)
            $end=if($next.Count -eq 1) { $next[0].Index } else { $Activity.Length }
            $activityScope=$Activity.Substring($start,$end-$start)
        }
    }
    $packages=@([regex]::Matches($activityScope, 'topResumedActivity=ActivityRecord\{[^\r\n]*?\s([A-Za-z0-9_.]+)/'))
    # Ambiguity within the recorded display, missing fields and timeouts fail closed.
    $gameOnly=$packages.Count -eq 1 -and $packages[0].Groups[1].Value -eq 'com.tencent.guandansy'
    if($SyntheticProbe) {
        $gameOnly=$packages.Count -eq 1 -and
            $activityScope -match 'topResumedActivity=ActivityRecord\{[^\r\n]*\scom\.cyotun\.jpq\.tests/com\.cyotun\.jpq\.vision\.PhoneFrameProbeActivity(?:\s|\})'
    }
    # HyperOS emits numeric wakefulness and screen-on fields for both displays.
    # Use the named global state, rather than an any(true) test across panels.
    $wakefulness=[regex]::Match($Power, '(?m)^\s*mWakefulness=(Awake|Asleep|Dozing|Dreaming)\s*$')
    $screenOn=$wakefulness.Success -and $wakefulness.Groups[1].Value -eq 'Awake'
    $keyguard=@([regex]::Matches($Policy, '(?m)^\s*showing=(true|false)(?:\s|$)'))
    $unlocked=$keyguard.Count -eq 1 -and $keyguard[0].Groups[1].Value -eq 'false'
    [pscustomobject]@{ GameOnly=$gameOnly; ScreenOn=$screenOn; Unlocked=$unlocked;
        SafeToRecord=($gameOnly -and $screenOn -and $unlocked) }
}

function Invoke-JpqReadCommand([string]$Executable, [string[]]$Arguments, [int]$TimeoutMs=2000) {
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Executable; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process=[Diagnostics.Process]::Start($info)
    # Drain both pipes immediately. Waiting before draining can deadlock dumpsys.
    $outTask=$process.StandardOutput.ReadToEndAsync(); $errTask=$process.StandardError.ReadToEndAsync()
    try {
        if (-not $process.WaitForExit($TimeoutMs)) {
            $process.Kill(); [void]$process.WaitForExit(1000)
            return [pscustomobject]@{ Ok=$false; Text=''; Error='read_timeout'; ExitCode=$null }
        }
        [pscustomobject]@{ Ok=($process.ExitCode -eq 0); Text=$outTask.GetAwaiter().GetResult();
            Error=$errTask.GetAwaiter().GetResult(); ExitCode=$process.ExitCode }
    } finally { $process.Dispose() }
}

function Get-JpqPrimaryPhysicalDisplayId([string]$Displays) {
    $primary=@([regex]::Matches($Displays,'(?m)^Display (\d+) \(HWC display 0\):'))
    if($primary.Count -eq 1) { $primary[0].Groups[1].Value } else { $null }
}

function ConvertTo-JpqPng([byte[]]$Bytes) {
    # Explicit physical display selection normally has no prefix. Some OEMs
    # print a plain-text warning before the PNG even with exec-out.
    $signature=[byte[]](137,80,78,71,13,10,26,10)
    $start=-1
    for($i=0;$i -le [Math]::Min(1024,$Bytes.Length-8);$i++) {
        $sameSignature=$true
        for($j=0;$j -lt 8;$j++) { if($Bytes[$i+$j] -ne $signature[$j]) { $sameSignature=$false; break } }
        if($sameSignature) { $start=$i; break }
    }
    if($start -lt 0 -or $Bytes.Length-$start -lt 45) { return $null }
    # A killed/truncated screencap must not be mistaken for a complete frame.
    $end=[byte[]](0,0,0,0,73,69,78,68,174,66,96,130)
    for($j=0;$j -lt 12;$j++) { if($Bytes[$Bytes.Length-12+$j] -ne $end[$j]) { return $null } }
    $png=[byte[]]::new($Bytes.Length-$start)
    [Array]::Copy($Bytes,$start,$png,0,$png.Length)
    return ,$png
}

function Invoke-JpqReadPng([string]$Executable,[string[]]$Arguments,[int]$TimeoutMs=3000) {
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Executable; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process=[Diagnostics.Process]::Start($info)
    $memory=[IO.MemoryStream]::new()
    $copy=$process.StandardOutput.BaseStream.CopyToAsync($memory)
    $errors=$process.StandardError.ReadToEndAsync()
    try {
        if(-not $process.WaitForExit($TimeoutMs)) {
            $process.Kill(); [void]$process.WaitForExit(1000)
            try { $copy.GetAwaiter().GetResult() } catch { }
            return [pscustomobject]@{Ok=$false; Bytes=$null; Error='screencap_timeout'}
        }
        $copy.GetAwaiter().GetResult()
        [void]$errors.GetAwaiter().GetResult()
        $png=if($process.ExitCode -eq 0) { ConvertTo-JpqPng $memory.ToArray() } else { $null }
        [pscustomobject]@{Ok=($null -ne $png); Bytes=$png; Error=if($null -eq $png) {'invalid_screencap'} else {''}}
    } finally { $memory.Dispose(); $process.Dispose() }
}

function Stop-JpqOwnedConsole([Diagnostics.Process]$Process, [int]$GraceMs=2500) {
    if ($null -eq $Process) { return $null }
    $forced=$false; $signaled=$false; $signalError=$null
    try {
        if (-not $Process.HasExited) {
            $pwsh=(Get-Process -Id $PID).Path
            $helper=Invoke-JpqReadCommand $pwsh @('-NoProfile','-NonInteractive','-File', $script:JpqEvidenceSupportPath,
                '-SignalProcessId', [string]$Process.Id) 5000
            $signaled=$helper.Ok
            if (-not $helper.Ok) { $signalError='console_signal_failed' }
            if (-not $Process.WaitForExit($GraceMs)) {
                $forced=$true; $Process.Kill(); [void]$Process.WaitForExit(1000)
            }
        }
        [pscustomobject]@{ ExitCode=if ($Process.HasExited) { $Process.ExitCode } else { $null };
            Signaled=$signaled; Forced=$forced; SignalError=$signalError }
    } finally { $Process.Dispose() }
}
