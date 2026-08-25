using System.Resources;
using System.Runtime.InteropServices;

// CA5392: every P/Invoke in this app is user32/kernel32; load only from System32.
[assembly: DefaultDllImportSearchPaths(DllImportSearchPath.System32)]

// 语言（settings-roadmap P1#16）：中立资源即 zh-Hans 文案，住在主程序集里；其余语言是
// 卫星程序集，缺键按键回落中立资源——与 Android 端「默认资源 = zh-Hans」同一约定。
[assembly: NeutralResourcesLanguage("zh-Hans", UltimateResourceFallbackLocation.MainAssembly)]
