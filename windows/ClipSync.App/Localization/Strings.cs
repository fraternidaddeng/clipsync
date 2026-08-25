using System.Globalization;
using System.Resources;

namespace ClipSync.App.Localization;

/// <summary>
/// 语言（settings-roadmap P1#16）：全部界面文案的取用口。中立资源即 zh-Hans（与 Android 端
/// 默认资源同语言）；其余语言由 Strings.&lt;culture&gt;.resx 卫星程序集承载，缺键按键回落
/// zh-Hans——宁可混排也不显示空串或资源键。每键一个生成的静态属性
/// （Strings.Designer.cs），XAML 经 <c>{x:Static loc:Strings.Key}</c> 引用，重启后按
/// 启动时设定的 <see cref="CultureInfo.CurrentUICulture"/> 解析（换语言重启生效）。
/// </summary>
public static partial class Strings
{
    private static readonly ResourceManager Resources =
        new("ClipSync.App.Localization.Strings", typeof(Strings).Assembly);

    /// <summary>The text for a key in the current UI culture; the key itself if the resx lost it.</summary>
    public static string Get(string key) =>
        Resources.GetString(key, CultureInfo.CurrentUICulture) ?? key;

    /// <summary>Composite formatting on a resource pattern; numbers/dates follow the current culture.</summary>
    public static string Format(string key, params object?[] arguments) =>
        string.Format(CultureInfo.CurrentCulture, Get(key), arguments);
}
