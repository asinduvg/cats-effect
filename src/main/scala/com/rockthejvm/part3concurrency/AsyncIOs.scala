package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}
import com.rockthejvm.utils.*

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Try

object AsyncIOs extends IOApp.Simple {

  // IOs can run asynchronously on fibers, without having to manually manage the fiber lifecycle
  val threadPool = Executors.newFixedThreadPool(8)
  given ec: ExecutionContext = ExecutionContext.fromExecutorService(threadPool)
  type Callback[A] = Either[Throwable, A] => Unit

  def computeMeaningOfLife(): Int = {
    Thread.sleep(1000)
    println(
      s"[${Thread.currentThread().getName}] computing the meaning of life on some other thread..."
    )
    42
  }
  def computeMeaningOfLifeEither(): Either[Throwable, Int] = Try {
    computeMeaningOfLife()
  }.toEither

  def computeMolOnThreadPool(): Unit = {
    threadPool.execute(() => computeMeaningOfLifeEither())
  }

  // lift computation to an IO
  // async is a Foreign Function Interface (FFI)
  val asyncMolIO: IO[Int] = IO.async_ {
    (cb: Callback[Int]) => // CE thread blocks (semantically) until this cb is invoked (by some other thread)
      threadPool.execute { () => // computation not managed by CE
        val result = computeMeaningOfLifeEither()
        cb(result) // CE thread is notified with the result
      }
  }

  /** Exercise: lift an async computation on ec to an IO
    */
  def asyncToIO[A](computation: () => A)(ec: ExecutionContext): IO[A] = {
    IO.async_[A] { cb =>
      ec.execute { () =>
        val result = Try(computation()).toEither
        cb(result)
      }
    }
  }

  val asyncMolIO_v2: IO[Int] = asyncToIO(computeMeaningOfLife)(ec)

  /** Exercise: lift an async computation as a Future, to an IO.
    */

  def convertFutureToIO[A](future: => Future[A]): IO[A] = {
    IO.async_ { cb =>
      future.onComplete { tryResult =>
        cb(tryResult.toEither)
      }
    }
  }

  lazy val molFuture: Future[Int] = Future { computeMeaningOfLife() }
  val asyncMolIO_v3: IO[Int] = convertFutureToIO(molFuture)
  val asyncMolIO_v4: IO[Int] = IO.fromFuture(IO(molFuture))

  /** Exercise: a never-ending IO?
    */
  val neverEndingIO: IO[Int] = IO.async_[Int](_ => ()) // no callback, no finish
  val neverEndingIO_v2 = IO.never

  /*
    FULL ASYNC CALL
   */
  def demoAsyncCancellation(): IO[Unit] = {
    val asyncMeaningOfLifeIO_v2: IO[Int] = IO.async { (cb: Callback[Int]) =>
      /*
        finalizer in case computation gets cancelled.
        finalizers are of type IO[Unit]
        not specifying finalizer => Option[IO[Unit]]
        creating option is an effect => IO[Option[IO[Unit]]]
       */
      // return IO[Option[IO[Unit]]
      IO {
        threadPool.execute { () =>
          val result = computeMeaningOfLifeEither()
          cb(result)
        }
      }.as(Some(IO("Cancelled!").debugging.void))
    }

    for {
      fib <- asyncMeaningOfLifeIO_v2.start
      _ <- IO.sleep(500.millis) >> IO("cancelling...").debugging >> fib.cancel
      _ <- fib.join
    } yield ()
  }

  override def run: IO[Unit] =
    demoAsyncCancellation().debugging >> IO(threadPool.shutdown())

}
