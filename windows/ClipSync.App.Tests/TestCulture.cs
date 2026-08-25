using System.Globalization;
using System.Runtime.CompilerServices;

namespace ClipSync.App.Tests;

/// <summary>
/// 语言（settings-roadmap P1#16）：测试进程钉死 zh-Hans UI 文化。断言里的中文文案
/// 来自中立资源；不钉的话，en 等卫星资源一旦入库，runner 的系统语言就会决定
/// <c>Strings</c> 解析结果，让断言随机器而变。
/// </summary>
internal static class TestCulture
{
    [ModuleInitializer]
    internal static void PinToNeutral()
    {
        var neutral = CultureInfo.GetCultureInfo("zh-Hans");
        CultureInfo.DefaultThreadCurrentUICulture = neutral;
        CultureInfo.CurrentUICulture = neutral;
    }
}
