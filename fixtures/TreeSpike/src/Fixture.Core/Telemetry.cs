using System;
using System.Collections.Concurrent;
using System.Collections.Generic;

namespace Fixture.Core;

/// <summary>
/// A deliberately boring in-memory trace sink. Its only job in this fixture is to be called from every
/// layer, so a walkthrough has a plausible reason to jump between files.
/// </summary>
public static class Telemetry
{
    private static readonly ConcurrentQueue<TraceEntry> Entries = new();

    private static long _sequence;

    public static bool Enabled { get; set; } = true;

    public static int Count => Entries.Count;

    public static void Record(string source, string message)
    {
        if (!Enabled)
        {
            return;
        }

        var next = System.Threading.Interlocked.Increment(ref _sequence);
        Entries.Enqueue(new TraceEntry(next, source, message));
    }

    public static void Record(string source, string message, Money amount) =>
        Record(source, message + " → " + amount.ToDisplayString());

    public static IReadOnlyList<TraceEntry> Drain()
    {
        var drained = new List<TraceEntry>();
        while (Entries.TryDequeue(out var entry))
        {
            drained.Add(entry);
        }

        return drained;
    }

    public static void Reset()
    {
        while (Entries.TryDequeue(out _))
        {
            // Draining is the reset; nothing else to do.
        }

        System.Threading.Interlocked.Exchange(ref _sequence, 0);
    }
}

/// <summary>One recorded trace line.</summary>
public readonly record struct TraceEntry(long Sequence, string Source, string Message)
{
    public override string ToString() => FormattableString.Invariant($"[{Sequence:D4}] {Source}: {Message}");
}
