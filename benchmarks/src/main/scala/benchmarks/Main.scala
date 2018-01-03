package benchmarks

import java.lang.System.exit

import montecarlo.{PiFuturesBenchmark, PiMultiThreadBenchmark}

object Main {
  def main(args: Array[String]): Unit = {
    val opts = Opts(args)
    val benchmarks = Seq(new PiMultiThreadBenchmark(opts.threadCount),
                         new PiFuturesBenchmark(opts.threadCount))

    val results = benchmarks.map { bench =>
      bench.loop(opts.iterations)
      bench.loop(opts.iterations)
    }
    val success = results.forall(_.success)

    println(opts.format.show(results))

    if (success) exit(0) else exit(1)
  }
}
