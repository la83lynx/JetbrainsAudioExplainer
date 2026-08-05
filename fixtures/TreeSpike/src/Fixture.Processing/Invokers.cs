using System;
using System.Collections.Generic;
using System.Linq;
using Fixture.Core;

namespace Fixture.Processing;

/// <summary>
/// Depth 1 of the call chain: the app layer calls <see cref="InvokeOn"/>, which normalises its inputs and
/// hands them to <see cref="SubInvoker.Apply"/> in the core project.
/// </summary>
/// <remarks>
/// This file also carries the fixture's <b>same-file</b> case. <see cref="InvokeOn"/> calls
/// <see cref="Normalize"/>, which sits far enough below it that caller and callee are two separate nodes
/// over one document — the arrangement Phase 0 finding 4 says is legal but that nobody has yet looked at.
/// </remarks>
public static class MidInvoker
{
    /// <summary>Scale factors above this are treated as a configuration mistake rather than an intent.</summary>
    public const int MaxScaleFactor = 512;

    private const decimal MinimumViableAmount = 0.0001m;

    private static readonly IReadOnlyList<string> RequiredAdjustments = new[] { "tax" };

    public static bool StrictMode { get; set; } = true;

    public static int InvocationCount { get; private set; }

    /// <summary>
    /// The call the walkthrough follows in from <c>MainInvoker.MainLoop</c>.
    /// </summary>
    /// <param name="amount">The order subtotal, straight from the app layer.</param>
    /// <param name="scaleFactor">The system-supplied factor the app layer read moments earlier.</param>
    /// <param name="options">Shared pricing configuration, passed unchanged all the way down.</param>
    public static Money InvokeOn(Money amount, int scaleFactor, PricingOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        EnsureUsableFactor(scaleFactor);

        InvocationCount++;
        var normalised = Normalize(amount, scaleFactor);
        Telemetry.Record(nameof(MidInvoker), "normalised", normalised);

        if (normalised.Amount < MinimumViableAmount)
        {
            Telemetry.Record(nameof(MidInvoker), "below minimum, short-circuiting");
            return Money.Zero;
        }

        var settled = SubInvoker.Apply(normalised, EnsureRequiredAdjustments(options));
        Telemetry.Record(nameof(MidInvoker), "settled", settled);
        return settled;
    }

    private static void EnsureUsableFactor(int scaleFactor)
    {
        if (scaleFactor <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(scaleFactor), scaleFactor, "Scale factor must be positive.");
        }

        if (scaleFactor > MaxScaleFactor && StrictMode)
        {
            throw new ArgumentOutOfRangeException(
                nameof(scaleFactor), scaleFactor, "Scale factor exceeds " + MaxScaleFactor + ".");
        }
    }

    private static PricingOptions EnsureRequiredAdjustments(PricingOptions options)
    {
        var missing = RequiredAdjustments.Where(name => !options.IsEnabled(name)).ToArray();
        if (missing.Length == 0)
        {
            return options;
        }

        var merged = options.EnabledAdjustments.Concat(missing).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        return options with { EnabledAdjustments = merged };
    }

    public static Money InvokeOnEach(IEnumerable<OrderLine> lines, int scaleFactor, PricingOptions options)
    {
        var running = Money.Zero;
        foreach (var line in lines)
        {
            running = running.Add(InvokeOn(line.LineTotal, scaleFactor, options));
        }

        return running;
    }

    public static IReadOnlyDictionary<string, Money> InvokeBySku(Order order, int scaleFactor, PricingOptions options)
    {
        var bySku = new Dictionary<string, Money>(StringComparer.Ordinal);
        foreach (var line in order.Lines)
        {
            bySku[line.Sku] = InvokeOn(line.LineTotal, scaleFactor, options);
        }

        return bySku;
    }

    public static void ResetCounters()
    {
        InvocationCount = 0;
        Telemetry.Reset();
    }

    private static decimal FactorToRatio(int scaleFactor) => scaleFactor <= 1 ? 1m : 1m + (scaleFactor / 100m);

    private static bool LooksLikeRoundingNoise(Money before, Money after) =>
        Math.Abs(before.Amount - after.Amount) < MinimumViableAmount;

    /// <summary>
    /// The same-file callee. Turns a raw subtotal and a system scale factor into the amount the core layer
    /// is willing to work with.
    /// </summary>
    /// <remarks>
    /// Kept deliberately far below <see cref="InvokeOn"/>: the two of them together are the fixture's
    /// two-nodes-over-one-document case.
    /// </remarks>
    public static Money Normalize(Money amount, int scaleFactor)
    {
        if (amount.IsZero)
        {
            return Money.Zero;
        }

        var ratio = FactorToRatio(scaleFactor);
        var scaled = amount.Scale(ratio);
        if (LooksLikeRoundingNoise(amount, scaled))
        {
            return amount.RoundToCents();
        }

        return scaled.RoundToCents();
    }
}

/// <summary>
/// A second caller of the same leaf, so the tree has a shape where one node has two parents if a plan ever
/// wants to express that (Phase 2 decision 1).
/// </summary>
public static class BatchInvoker
{
    public static IReadOnlyList<Money> InvokeAll(IReadOnlyList<Order> orders, PricingOptions options)
    {
        var results = new List<Money>(orders.Count);
        foreach (var order in orders)
        {
            var factor = ChooseFactor(order);
            results.Add(MidInvoker.InvokeOn(order.Subtotal, factor, options));
        }

        return results;
    }

    private static int ChooseFactor(Order order) => order.TotalUnits switch
    {
        < 10 => 1,
        < 100 => 4,
        < 1000 => 16,
        _ => 64,
    };

    public static Money Largest(IReadOnlyList<Money> settled) =>
        settled.Count == 0 ? Money.Zero : settled.Max();
}
