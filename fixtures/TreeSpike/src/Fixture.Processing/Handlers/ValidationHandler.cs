using System;
using System.Collections.Generic;
using System.Linq;
using Fixture.Core;

namespace Fixture.Processing.Handlers;

/// <summary>First of the three siblings under <see cref="Dispatcher.Dispatch"/>.</summary>
public sealed class ValidationHandler
{
    private const int MaxLines = 200;

    private static readonly string[] BlockedSkuPrefixes = { "TEST-", "VOID-" };

    public int Inspected { get; private set; }

    /// <summary>Checks the order over and returns whether the rest of the pipeline should trust it.</summary>
    public Verdict Handle(Order order)
    {
        ArgumentNullException.ThrowIfNull(order);
        Inspected++;

        var problems = new List<string>();
        if (order.IsEmpty)
        {
            problems.Add("Order carries no lines.");
        }

        if (order.Lines.Count > MaxLines)
        {
            problems.Add("Order carries more than " + MaxLines + " lines.");
        }

        problems.AddRange(BlockedLines(order).Select(line => "Blocked SKU: " + line.Sku));
        Telemetry.Record(nameof(ValidationHandler), problems.Count + " problem(s)");

        return problems.Count == 0 ? Verdict.Clean : Verdict.Rejected(problems.ToArray());
    }

    private static IEnumerable<OrderLine> BlockedLines(Order order) =>
        order.Lines.Where(line => BlockedSkuPrefixes.Any(prefix =>
            line.Sku.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)));

    public void Reset() => Inspected = 0;
}
