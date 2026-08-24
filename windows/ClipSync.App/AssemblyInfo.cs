using System.Runtime.InteropServices;

// CA5392: every P/Invoke in this app is user32/kernel32; load only from System32.
[assembly: DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
