package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp, Fiber, Outcome}
import cats.effect.Outcome.{Succeeded, Errored, Canceled}
import scala.concurrent.duration._

object Fibers extends IOApp.Simple {
  val meaningOfLife = IO.pure(42)
  val favLang = IO.pure("Scala")

  import com.rockthejvm.utils._

  def sameThreadIOs() = for {
    _ <- meaningOfLife.debugging
    _ <- favLang.debugging
  } yield ()

  // introduce the Fiber
  def createFiber: Fiber[IO, Throwable, String] =
    ??? // almost impossible to create fibers manually

    // the fiber is not actually started, but the fiber allocation is wrapped in another effect
  val aFiber: IO[Fiber[IO, Throwable, Int]] = meaningOfLife.debugging.start

  def differentThreadIOs() = for {
    _ <- aFiber
    _ <- favLang.debugging
  } yield ()

  // joining a fiber
  def runOnSomeOtherThread[A](io: IO[A]): IO[Outcome[IO, Throwable, A]] = for {
    fib <- io.start
    result <- fib.join // an effect which waits for the fiber to terminate
  } yield result
  /*
    possible outcomes:
      - success with an IO
      - failure with an exception
      - cancelled
   */

  val someIOOnAnotherThread = runOnSomeOtherThread(meaningOfLife)
  val someResultFromAnotherThread = someIOOnAnotherThread.flatMap {
    case Succeeded(effect) => effect
    case Errored(e)        => IO(0)
    case Canceled()        => IO(0)
  }

  def throwOnAnotherThread() = for {
    fib <- IO
      .raiseError[Int](new java.lang.RuntimeException("no number for you"))
      .start
    result <- fib.join
  } yield result

  def testCancel() = {
    val task =
      IO("starting").debugging >> IO.sleep(1.second) >> IO("done").debugging

      // onCancel is a "finalizer", allowing you to free up resources in case you get cancelled
    val taskWithCancellationHandler =
      task.onCancel(IO("I'm being cancelled").debugging.void)

    for {
      fib <- taskWithCancellationHandler.start // on a seperate thread
      _ <- IO.sleep(500.millis) >> IO(
        "cancelling"
      ).debugging // running on the calling thread
      _ <- fib.cancel
      result <- fib.join
    } yield result
  }

  /** Exercises:
    *   1. Write a function that returns an IO on another thread, and, depending
    *      on the result of the fiber
    *      - return the result in an IO
    *      - if errored or cancelled, return a failed IO
    *
    * 2. Write a function that takes two IOs, runs them on different fibers and
    * returns an IO with a tuple containing both results
    *   - If both IOs complete successfully, tuple their results
    *   - If the first IO returns an error, raise that error (ignoring the
    *     second IO's result/error)
    *   - If the first IO doesn't error but second IO returns an error, raise
    *     that error
    *   - If one (or both) cancelled, raise a RuntimeException
    *
    * 3. Write a function that adds a timeout to an IO:
    *   - IO runs on an fiber
    *   - If the timeout duration passes, then the fiber is cancelled
    *   - the method returns an IO[A] which contains
    *     - the original value if the computation is successful before the
    *       timeout signal
    *     - the exception if the computation is failed before the timeout signal
    *     - a RuntimeException if it times out (i.e. cancelled by the timeout)
    */

  // 1
  def processResultsFromFiber[A](io: IO[A]): IO[A] = {
    val ioResult = for {
      fib <- io.debugging.start
      result <- fib.join
    } yield result

    ioResult flatMap {
      case Succeeded(fa) => fa
      case Errored(e)    => IO.raiseError(e)
      case Canceled() =>
        IO.raiseError(new RuntimeException("Computation cancelled."))
    }
  }

  def testEx1() = {
    val aComputation = IO("starting").debugging >> IO.sleep(1.second) >> IO(
      "done!"
    ).debugging >> IO(42)
    processResultsFromFiber(aComputation).void
  }

  // 2
  def tupleIOs[A, B](ioa: IO[A], iob: IO[B]): IO[(A, B)] = {
    val ioResult = for {
      fib1 <- ioa.start
      fib2 <- iob.start
      result1 <- fib1.join
      result2 <- fib2.join
    } yield (result1, result2)

    ioResult.flatMap {
      case (Succeeded(fa), Succeeded(fb)) =>
        for {
          a <- fa
          b <- fb
        } yield (a, b)
      case (Errored(e), _) => IO.raiseError(e)
      case (_, Errored(e)) => IO.raiseError(e)
      case _ =>
        IO.raiseError(new RuntimeException("Some computation cancelled."))
    }
  }

  def testEx2() = {
    val firstIO = IO.sleep(2.seconds) >> IO(1).debugging
    val secondIO = IO.sleep(3.seconds) >> IO(2).debugging
    tupleIOs(firstIO, secondIO).debugging.void
  }

  // 3
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] = {
    val computation = for {
      fib <- io.start
      _ <- (IO.sleep(
        duration
      ) >> fib.cancel).start // careful - fibers can leakÒ
//      _ <- IO.sleep(duration) >> fib.cancel
      result <- fib.join
    } yield result

    computation.flatMap {
      case Succeeded(fa) => fa
      case Errored(e)    => IO.raiseError(e)
      case Canceled() =>
        IO.raiseError(new RuntimeException("Computation cancelled."))
    }
  }

  def testEx3() = {
    val aComputation = IO("starting").debugging >> IO.sleep(1.second) >> IO(
      "done!"
    ).debugging >> IO(42)
    timeout(aComputation, 2.seconds).debugging.void
  }

  override def run = testEx3()
}
