using ClipSync.App.Diagnostics;

namespace ClipSync.App.Tests.Diagnostics;

public class UnhandledExceptionCodesTests
{
    [Fact]
    public void DispatcherCodeCarriesTypeNameOnly()
    {
        var code = UnhandledExceptionCodes.ForDispatcher(new InvalidOperationException("SECRET clipboard text"));

        Assert.Equal("unhandled_ui_InvalidOperationException", code);
        Assert.DoesNotContain("SECRET", code, StringComparison.Ordinal);
    }

    [Fact]
    public void UnobservedTaskCodeNamesInnermostCause()
    {
        var code = UnhandledExceptionCodes.ForUnobservedTask(
            new AggregateException(new TimeoutException("SECRET")));

        Assert.Equal("unobserved_task_TimeoutException", code);
        Assert.DoesNotContain("SECRET", code, StringComparison.Ordinal);
    }

    [Fact]
    public void UnobservedTaskCodeUnwrapsNestedAggregates()
    {
        var nested = new AggregateException(new AggregateException(new FormatException("SECRET")));

        Assert.Equal("unobserved_task_FormatException", UnhandledExceptionCodes.ForUnobservedTask(nested));
    }

    [Fact]
    public void UnobservedTaskCodeWithoutCauseFallsBackToAggregate()
    {
        Assert.Equal("unobserved_task_AggregateException", UnhandledExceptionCodes.ForUnobservedTask(new AggregateException()));
        Assert.Equal("unobserved_task_Unknown", UnhandledExceptionCodes.ForUnobservedTask(null));
    }

    [Fact]
    public void DomainCodeNamesTheException()
    {
        var code = UnhandledExceptionCodes.ForDomain(new ArgumentException("SECRET"));

        Assert.Equal("unhandled_fatal_ArgumentException", code);
        Assert.DoesNotContain("SECRET", code, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("not an exception")]
    public void DomainCodeWithoutExceptionObjectReadsUnknown(object? exceptionObject) =>
        Assert.Equal("unhandled_fatal_Unknown", UnhandledExceptionCodes.ForDomain(exceptionObject));
}
