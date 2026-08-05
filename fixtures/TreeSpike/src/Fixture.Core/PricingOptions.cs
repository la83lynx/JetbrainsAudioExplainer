using System;
using System.Collections.Generic;
using System.Linq;

namespace Fixture.Core;

/// <summary>How an adjustment relates to the amount it is applied to.</summary>
public enum AdjustmentKind
{
    None = 0,
    Discount = 1,
    Surcharge = 2,
    Tax = 3,
    Rounding = 4,
}

/// <summary>One step of the pricing pipeline.</summary>
public interface IPriceAdjustment
{
    string Name { get; }

    AdjustmentKind Kind { get; }

    Money Apply(Money amount, PricingOptions options);
}

/// <summary>
/// The knobs the whole chain reads. Passed by reference through every layer, which is what makes it a
/// useful subject for a narrated walkthrough: the same object shows up in three files.
/// </summary>
public sealed record PricingOptions
{
    public required string Region { get; init; }

    public decimal TaxRate { get; init; } = 0.21m;

    public decimal MaxDiscountRatio { get; init; } = 0.35m;

    public int PrecisionScale { get; init; } = 2;

    public bool AllowNegativeTotals { get; init; }

    public IReadOnlyList<string> EnabledAdjustments { get; init; } = Array.Empty<string>();

    public static PricingOptions Default { get; } = new()
    {
        Region = "EU",
        EnabledAdjustments = new[] { "volume", "loyalty", "tax" },
    };

    public bool IsEnabled(string adjustmentName) =>
        EnabledAdjustments.Any(name => string.Equals(name, adjustmentName, StringComparison.OrdinalIgnoreCase));

    public PricingOptions WithRegion(string region) => this with { Region = region };

    public PricingOptions WithoutTax() => this with { TaxRate = 0m };

    public void Validate()
    {
        if (TaxRate is < 0m or > 1m)
        {
            throw new ArgumentOutOfRangeException(nameof(TaxRate), TaxRate, "Tax rate must sit between 0 and 1.");
        }

        if (MaxDiscountRatio is < 0m or > 1m)
        {
            throw new ArgumentOutOfRangeException(
                nameof(MaxDiscountRatio), MaxDiscountRatio, "Discount ratio must sit between 0 and 1.");
        }

        if (PrecisionScale is < 0 or > 6)
        {
            throw new ArgumentOutOfRangeException(nameof(PrecisionScale), PrecisionScale, "Scale must sit between 0 and 6.");
        }
    }
}

/// <summary>A single line of an order, as it reaches the processing layer.</summary>
public sealed record OrderLine(string Sku, int Quantity, Money UnitPrice)
{
    public Money LineTotal => UnitPrice.Scale(Quantity);

    public bool IsBulk => Quantity >= BulkThreshold;

    public const int BulkThreshold = 25;
}

/// <summary>An order as the app layer hands it over.</summary>
public sealed record Order(string Reference, IReadOnlyList<OrderLine> Lines)
{
    public Money Subtotal => Lines.Aggregate(Money.Zero, (running, line) => running.Add(line.LineTotal));

    public int TotalUnits => Lines.Sum(line => line.Quantity);

    public bool IsEmpty => Lines.Count == 0;
}
