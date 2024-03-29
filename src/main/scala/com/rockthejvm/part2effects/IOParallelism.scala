package com.rockthejvm.part2effects

import cats.Parallel
import cats.effect.{IO, IOApp}

object IOParallelism extends IOApp.Simple {

  // IOs are usually sequential
  val aniIO = IO(s"[${Thread.currentThread().getName}] Ani")
  val kamranIO = IO(s"[${Thread.currentThread().getName}] Kamran")

  val composedIO = for {
    ani <- aniIO
    kamran <- kamranIO
  } yield s"$ani and $kamran love Rock the JVM"

  // debugging extension method

  import com.rockthejvm.utils.*
  // mapN extension method
  import cats.syntax.apply.*

  val meaningOfLife = IO.delay(42)
  val favLang = IO.delay("Scala")
  val goalInLife =
    (meaningOfLife.debugging, favLang.debugging).mapN((num, string) =>
      s"my goal in life is $num and $string"
    )

  // parallelism on IOs
  // convert a sequential IO to parallel IO
  val parIO1: IO.Par[Int] = Parallel[IO].parallel(meaningOfLife.debugging)
  val parIO2: IO.Par[String] = Parallel[IO].parallel(favLang.debugging)

  import cats.effect.implicits.*

  val goalInLifeParallel: IO.Par[String] =
    (parIO1, parIO2).mapN((num, string) =>
      s"my goal in life is $num and $string"
    )
  // turn back to sequential
  val goalInLife_v2: IO[String] = Parallel[IO].sequential(goalInLifeParallel)

  // shorthand:

  import cats.syntax.parallel.*

  val goalInLife_v3: IO[String] =
    (meaningOfLife.debugging, favLang.debugging).parMapN((num, string) =>
      s"my goal in life is $num and $string"
    )

  // regarding failure
  val aFailure: IO[String] =
    IO.raiseError(new RuntimeException("I can't do this!"))
  // compose success + failure
  val parallelWithFailure =
    (meaningOfLife.debugging, aFailure.debugging).parMapN(_ + _)
  // compose failure + failure
  val anotherFailure: IO[String] =
    IO.raiseError(new RuntimeException("Second failure"))
  val twoFailures: IO[String] =
    (aFailure.debugging, anotherFailure.debugging).parMapN(_ + _)
  // the first effect to fail gives the failure of the result
  val twoFailuresDelayed: IO[String] =
    (IO(Thread.sleep(1000)) >> aFailure.debugging, anotherFailure.debugging)
      .parMapN(_ + _)

  override def run: IO[Unit] = {
    //    composedIO.map(println)
    //    goalInLife.map(println)
    //    goalInLife_v2.debugging.void
    //    goalInLife_v3.debugging.void
    //    parallelWithFailure.debugging.void
    //    twoFailures.debugging.void
    twoFailuresDelayed.debugging.void
  }

}
