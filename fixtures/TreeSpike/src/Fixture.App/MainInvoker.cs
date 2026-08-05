using System;
using System.Collections.Generic;
using System.Linq;
using Fixture.Core;
using Fixture.Processing;

namespace Fixture.App;

/// <summary>
/// Depth 0 of the call chain — the root node of the fixture's three-deep tree.
/// </summary>
/// <remarks>
/// Laid out to match the sketch in <c>MultilineTreeFindings1.md</c>: roughly thirty lines between the class
/// header and <see cref="MainLoop"/>, then roughly fifteen more inside it before the call that carries
/// <c>someParam</c> across the project boundary. Both gaps exist so the node view has something worth
/// eliding; do not tidy them away.
/// </remarks>
public static class MainInvoker
{
    private const string DefaultReference = "ORD-0001";

    private const int FallbackScaleFactor = 4;

    private static readonly Random Entropy = new(20260730);

    private static readonly List<string> Skus = new()
    {
        "SKU-ALPHA",
        "SKU-BETA",
        "SKU-GAMMA",
        "SKU-DELTA",
    };

    public static int LoopCount { get; private set; }

    public static PricingOptions Options { get; set; } = PricingOptions.Default;

    public static Order BuildSampleOrder(string reference)
    {
        var lines = Skus.Select((sku, index) => new OrderLine(
            sku,
            Quantity: (index + 1) * 7,
            UnitPrice: Money.Of(12.50m + index))).ToArray();

        return new Order(reference, lines);
    }

    public static IReadOnlyList<Order> BuildBatch(int count) =>
        Enumerable.Range(1, count)
            .Select(index => BuildSampleOrder(FormattableString.Invariant($"ORD-{index:D4}")))
            .ToArray();

    private static int SomeSysCall()
    {
        // Stands in for whatever the real system would have been asked. Deterministic so a narrated
        // walkthrough describes the same numbers every time it is replayed.
        var jitter = Entropy.Next(0, 3);
        return FallbackScaleFactor + jitter;
    }

    private static void Report(string label, Money value) =>
        Console.WriteLine(label.PadRight(24) + value.ToDisplayString());

    /// <summary>
    /// The walkthrough's entry point: builds an order, asks the system for a scale factor, and hands both
    /// down to <see cref="MidInvoker.InvokeOn"/> in the processing project.
    /// </summary>
    public static Money MainLoop()
    {
        LoopCount++;
        Options.Validate();

        var order = BuildSampleOrder(DefaultReference);
        if (order.IsEmpty)
        {
            Console.WriteLine("Nothing to price.");
            return Money.Zero;
        }

        var subtotal = order.Subtotal;
        Report("subtotal", subtotal);
        Telemetry.Record(nameof(MainInvoker), "built " + order.Reference, subtotal);

        // The line the fixture exists for: a value produced here, consumed two projects away.
        int someParam = SomeSysCall();
        var settled = MidInvoker.InvokeOn(subtotal, someParam, Options);

        Report("settled", settled);
        return settled;
    }

    public static IReadOnlyList<Money> RunBatch(int count)
    {
        var orders = BuildBatch(count);
        var factor = SomeSysCall();
        return orders.Select(order => MidInvoker.InvokeOn(order.Subtotal, factor, Options)).ToArray();
    }

    public static DispatchResult RunDispatch()
    {
        var dispatcher = new Dispatcher(Options);
        var order = BuildSampleOrder("ORD-WIDE");
        return dispatcher.Dispatch(order);
    }

    public static void Reset()
    {
        LoopCount = 0;
        MidInvoker.ResetCounters();
    }
}
