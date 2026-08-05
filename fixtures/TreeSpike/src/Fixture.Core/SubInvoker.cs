using System;
using System.Collections.Generic;
using System.Linq;

namespace Fixture.Core;

/// <summary>
/// Depth 2 of the call chain — the leaf the walkthrough is heading towards.
/// </summary>
/// <remarks>
/// <see cref="Apply"/> sits well down the file on purpose. A node view keeps the class header plus the
/// method and elides what is between them, so the gap above the interesting method is the fixture's main
/// elision subject.
/// </remarks>
public static class SubInvoker
{
    /// <summary>Adjustments are looked up by name so the enabled set can come from configuration.</summary>
    private static readonly Dictionary<string, IPriceAdjustment> Registry = new(StringComparer.OrdinalIgnoreCase)
    {
        ["volume"] = new VolumeAdjustment(),
        ["loyalty"] = new LoyaltyAdjustment(),
        ["tax"] = new TaxAdjustment(),
    };

    public static IReadOnlyCollection<string> KnownAdjustments => Registry.Keys;

    public static bool IsKnown(string name) => Registry.ContainsKey(name);

    public static IPriceAdjustment Resolve(string name)
    {
        if (Registry.TryGetValue(name, out var adjustment))
        {
            return adjustment;
        }

        throw new KeyNotFoundException("No adjustment registered under '" + name + "'.");
    }

    public static IEnumerable<IPriceAdjustment> ResolveEnabled(PricingOptions options)
    {
        foreach (var name in options.EnabledAdjustments)
        {
            if (Registry.TryGetValue(name, out var adjustment))
            {
                yield return adjustment;
            }
        }
    }

    private static Money GuardAgainstNegative(Money candidate, PricingOptions options) =>
        options.AllowNegativeTotals ? candidate : candidate.ClampToZero();

    private static decimal WeightFor(AdjustmentKind kind) => kind switch
    {
        AdjustmentKind.Discount => 1.0m,
        AdjustmentKind.Surcharge => 1.0m,
        AdjustmentKind.Tax => 1.0m,
        AdjustmentKind.Rounding => 0.5m,
        _ => 0m,
    };

    /// <summary>
    /// The leaf call. Runs every enabled adjustment over <paramref name="amount"/> in registration order
    /// and returns the settled total.
    /// </summary>
    /// <param name="amount">The already-scaled amount handed down by the processing layer.</param>
    /// <param name="options">The same options object the app layer created.</param>
    public static Money Apply(Money amount, PricingOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        options.Validate();

        var running = amount;
        foreach (var adjustment in ResolveEnabled(options))
        {
            var before = running;
            running = adjustment.Apply(running, options);
            Telemetry.Record(nameof(SubInvoker), adjustment.Name + " " + before.ToLedgerString(), running);
        }

        var guarded = GuardAgainstNegative(running, options);
        return decimal.Round(guarded.Amount, options.PrecisionScale, MidpointRounding.ToEven) == guarded.Amount
            ? guarded
            : guarded with { Amount = decimal.Round(guarded.Amount, options.PrecisionScale, MidpointRounding.ToEven) };
    }

    public static Money ApplySingle(string adjustmentName, Money amount, PricingOptions options)
    {
        var adjustment = Resolve(adjustmentName);
        return adjustment.Apply(amount, options);
    }

    public static IReadOnlyDictionary<string, Money> Explain(Money amount, PricingOptions options)
    {
        var breakdown = new Dictionary<string, Money>(StringComparer.Ordinal);
        var running = amount;
        foreach (var adjustment in ResolveEnabled(options))
        {
            running = adjustment.Apply(running, options);
            breakdown[adjustment.Name] = running;
        }

        return breakdown;
    }

    public static decimal TotalWeight(PricingOptions options) =>
        ResolveEnabled(options).Sum(adjustment => WeightFor(adjustment.Kind));
}

internal sealed class VolumeAdjustment : IPriceAdjustment
{
    public string Name => "volume";

    public AdjustmentKind Kind => AdjustmentKind.Discount;

    public Money Apply(Money amount, PricingOptions options)
    {
        var ratio = amount.Amount > 1000m ? 0.10m : 0.02m;
        var capped = Math.Min(ratio, options.MaxDiscountRatio);
        return amount.Subtract(amount.Scale(capped));
    }
}

internal sealed class LoyaltyAdjustment : IPriceAdjustment
{
    private const decimal FlatRatio = 0.05m;

    public string Name => "loyalty";

    public AdjustmentKind Kind => AdjustmentKind.Discount;

    public Money Apply(Money amount, PricingOptions options)
    {
        var ratio = Math.Min(FlatRatio, options.MaxDiscountRatio);
        return amount.Subtract(amount.Scale(ratio));
    }
}

internal sealed class TaxAdjustment : IPriceAdjustment
{
    public string Name => "tax";

    public AdjustmentKind Kind => AdjustmentKind.Tax;

    public Money Apply(Money amount, PricingOptions options) => amount.Add(amount.Scale(options.TaxRate));
}
