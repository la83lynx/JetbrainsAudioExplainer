using System;
using System.Collections.Generic;
using System.Linq;
using Fixture.Core;
using Fixture.Processing.Handlers;

namespace Fixture.Processing;

/// <summary>
/// The fixture's <b>wide</b> case: one parent with three children in three different files.
/// </summary>
/// <remarks>
/// <see cref="Dispatch"/> calls <see cref="ValidationHandler.Handle"/>, <see cref="PricingHandler.Handle"/>
/// and <see cref="AuditHandler.Handle"/> from three consecutive lines, which is the layout question Phase 1
/// needs a real subject for: three sibling nodes under one parent, and whether they read as siblings.
/// </remarks>
public sealed class Dispatcher
{
    private readonly ValidationHandler _validation;

    private readonly PricingHandler _pricing;

    private readonly AuditHandler _audit;

    private readonly List<string> _failures = new();

    public Dispatcher(PricingOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        Options = options;
        _validation = new ValidationHandler();
        _pricing = new PricingHandler(options);
        _audit = new AuditHandler();
    }

    public PricingOptions Options { get; }

    public IReadOnlyList<string> Failures => _failures;

    public bool HasFailures => _failures.Count > 0;

    public int HandlerCount => 3;

    /// <summary>Runs the three handlers over <paramref name="order"/> in a fixed order.</summary>
    public DispatchResult Dispatch(Order order)
    {
        ArgumentNullException.ThrowIfNull(order);
        _failures.Clear();

        var verdict = _validation.Handle(order); //comment
        var priced = _pricing.Handle(order, verdict);
        var trail = _audit.Handle(order, priced);

        if (!verdict.IsAcceptable)
        {
            _failures.AddRange(verdict.Problems);
        }

        return new DispatchResult(order.Reference, priced, trail, verdict.IsAcceptable);
    }

    public IReadOnlyList<DispatchResult> DispatchAll(IEnumerable<Order> orders) =>
        orders.Select(Dispatch).ToArray();

    public Money TotalOf(IReadOnlyList<DispatchResult> results) =>
        results.Where(result => result.Accepted)
            .Aggregate(Money.Zero, (running, result) => running.Add(result.Total));

    public void Reset()
    {
        _failures.Clear();
        Telemetry.Reset();
    }
}

/// <summary>What one dispatch produced.</summary>
public sealed record DispatchResult(string Reference, Money Total, IReadOnlyList<string> AuditTrail, bool Accepted)
{
    public bool IsFree => Total.IsZero;

    public int TrailLength => AuditTrail.Count;
}

/// <summary>The validation handler's verdict, shared with the two handlers that run after it.</summary>
public sealed record Verdict(bool IsAcceptable, IReadOnlyList<string> Problems)
{
    public static Verdict Clean { get; } = new(true, Array.Empty<string>());

    public static Verdict Rejected(params string[] problems) => new(false, problems);
}
