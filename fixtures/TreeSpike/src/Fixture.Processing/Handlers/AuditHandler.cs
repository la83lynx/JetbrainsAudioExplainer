using System;
using System.Collections.Generic;
using System.Linq;
using Fixture.Core;

namespace Fixture.Processing.Handlers;

/// <summary>Third of the three siblings. Reads what the other two left behind and writes a trail.</summary>
public sealed class AuditHandler
{
    private readonly List<string> _trail = new();

    public IReadOnlyList<string> LastTrail => _trail;

    public bool IncludeTelemetry { get; set; } = true;

    /// <summary>Builds the audit trail for one dispatched order.</summary>
    public IReadOnlyList<string> Handle(Order order, Money total)
    {
        ArgumentNullException.ThrowIfNull(order);
        _trail.Clear();

        _trail.Add("reference=" + order.Reference);
        _trail.Add("lines=" + order.Lines.Count);
        _trail.Add("units=" + order.TotalUnits);
        _trail.Add("subtotal=" + order.Subtotal.ToDisplayString());
        _trail.Add("total=" + total.ToDisplayString());

        if (IncludeTelemetry)
        {
            _trail.AddRange(Telemetry.Drain().Select(entry => entry.ToString()));
        }

        return _trail.ToArray();
    }

    public string Summarise() => string.Join(Environment.NewLine, _trail);
}
