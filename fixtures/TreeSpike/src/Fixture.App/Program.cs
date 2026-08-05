using System;
using Fixture.Core;

namespace Fixture.App;

/// <summary>
/// Entry point. The fixture is runnable rather than merely compilable so that "is this real code?" has an
/// answer that does not depend on reading it.
/// </summary>
public static class Program
{
    public static int Main(string[] args)
    {
        Console.WriteLine("TreeSpike fixture — three-deep chain, same-file pair, wide dispatch.");
        Console.WriteLine();

        var settled = MainInvoker.MainLoop();
        Console.WriteLine("MainLoop settled at " + settled.ToLedgerString());
        Console.WriteLine();

        var dispatched = MainInvoker.RunDispatch();
        Console.WriteLine("Dispatch accepted=" + dispatched.Accepted + " total=" + dispatched.Total.ToDisplayString());
        foreach (var entry in dispatched.AuditTrail)
        {
            Console.WriteLine("  " + entry);
        }

        if (args.Length > 0 && int.TryParse(args[0], out var batchSize))
        {
            var batch = MainInvoker.RunBatch(batchSize);
            Console.WriteLine();
            Console.WriteLine("Batch of " + batch.Count + " settled.");
        }

        return 0;
    }
}
