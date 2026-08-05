using System;
using System.Globalization;

namespace Fixture.Core;

/// <summary>
/// A currency-tagged decimal amount.
/// </summary>
/// <remarks>
/// Deliberately a value type with a handful of members either side of the interesting ones: the tree spike
/// folds everything outside a node's kept ranges, so a file with no distance between its methods cannot
/// produce an elision worth looking at.
/// </remarks>
public readonly record struct Money(decimal Amount, string Currency) : IComparable<Money>
{
    public const string DefaultCurrency = "EUR";

    private const int WorkingScale = 4;

    public static readonly Money Zero = new(0m, DefaultCurrency);

    public bool IsZero => Amount == 0m;

    public bool IsNegative => Amount < 0m;

    public bool IsPositive => Amount > 0m;

    public static Money Of(decimal amount) => new(amount, DefaultCurrency);

    public static Money Of(decimal amount, string currency) => new(amount, Normalise(currency));

    /// <summary>Multiplies by <paramref name="factor"/>, rounding half-to-even at the working scale.</summary>
    public Money Scale(decimal factor)
    {
        var scaled = decimal.Round(Amount * factor, WorkingScale, MidpointRounding.ToEven);
        return this with { Amount = scaled };
    }

    public Money Add(Money other)
    {
        EnsureSameCurrency(other);
        return this with { Amount = Amount + other.Amount };
    }

    public Money Subtract(Money other)
    {
        EnsureSameCurrency(other);
        return this with { Amount = Amount - other.Amount };
    }

    public Money ClampToZero() => IsNegative ? this with { Amount = 0m } : this;

    public Money RoundToCents() => this with { Amount = decimal.Round(Amount, 2, MidpointRounding.ToEven) };

    public int CompareTo(Money other)
    {
        EnsureSameCurrency(other);
        return Amount.CompareTo(other.Amount);
    }

    public override string ToString() => FormattableString.Invariant($"{Amount:0.####} {Currency}");

    private void EnsureSameCurrency(Money other)
    {
        if (!string.Equals(Currency, other.Currency, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException(
                FormattableString.Invariant($"Cannot combine {Currency} with {other.Currency}."));
        }
    }

    private static string Normalise(string currency)
    {
        if (string.IsNullOrWhiteSpace(currency))
        {
            return DefaultCurrency;
        }

        return currency.Trim().ToUpperInvariant();
    }
}

/// <summary>Formatting helpers kept out of the value type so its own surface stays small.</summary>
public static class MoneyFormatting
{
    private static readonly CultureInfo Invariant = CultureInfo.InvariantCulture;

    public static string ToDisplayString(this Money money) => money.Amount.ToString("N2", Invariant) + " " + money.Currency;

    public static string ToLedgerString(this Money money)
    {
        var sign = money.IsNegative ? "-" : "+";
        var magnitude = Math.Abs(money.Amount).ToString("N4", Invariant);
        return sign + magnitude + " " + money.Currency;
    }

    public static bool TryParse(string? text, out Money money)
    {
        money = Money.Zero;
        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }

        var parts = text.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length != 2)
        {
            return false;
        }

        if (!decimal.TryParse(parts[0], NumberStyles.Number, Invariant, out var amount))
        {
            return false;
        }

        money = Money.Of(amount, parts[1]);
        return true;
    }
}
