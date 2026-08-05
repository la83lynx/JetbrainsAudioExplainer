using System;
using System.Linq;
using Fixture.Core;

namespace Fixture.Processing.Handlers;

/// <summary>
/// Second of the three siblings. The only one that re-enters the depth-1 → depth-2 chain, so a tree rooted
/// at <see cref="Dispatcher.Dispatch"/> is both wide and deep.
/// </summary>
public sealed class PricingHandler
{
    private readonly PricingOptions _options;

    private int _scaleFactor = 8;

    public PricingHandler(PricingOptions options)
    {
        _options = options ?? throw new ArgumentNullException(nameof(options));
    }

    public int ScaleFactor
    {
        get => _scaleFactor;
        set => _scaleFactor = value > 0 ? value : 1;
    }

    /// <summary>Prices the order, unless validation already rejected it.</summary>
    public Money Handle(Order order, Verdict verdict)
    {
        ArgumentNullException.ThrowIfNull(order);
        if (!verdict.IsAcceptable)
        {
            Telemetry.Record(nameof(PricingHandler), "skipped, verdict rejected");
            return Money.Zero;
        }

        var subtotal = order.Subtotal;
        var settled = MidInvoker.InvokeOn(subtotal, ScaleFactor, _options);
        Telemetry.Record(nameof(PricingHandler), "priced " + order.Reference, settled);
        return settled;
    }

    public Money HandleLinesIndividually(Order order) =>
        order.Lines.Aggregate(Money.Zero, (running, line) =>
            running.Add(MidInvoker.InvokeOn(line.LineTotal, ScaleFactor, _options)));
}
