package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp, Fiber, Outcome}
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Outcome.Errored
import cats.effect.kernel.Outcome.Canceled
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

  override def run = testCancel().debugging.void
}
