package com.rockthejvm.part5polymorphic

import cats.effect.kernel.{Concurrent, Deferred, Fiber, Outcome}
import cats.effect.{Deferred, IO, IOApp, Ref, Spawn}

object PolymorphicCoordination extends IOApp.Simple {

  // Concurrent - Ref + Deferred for ANY effect type
  trait MyConcurrent[F[_]] extends Spawn[F] {
    def ref[A](a: A): F[Ref[F, A]]
    def deferred[A]: F[Deferred[F, A]]
  }

  val concurrentIO = Concurrent[IO] // given instance of Concurrent[IO]
  val aDeferred = Deferred[IO, Int] // given/implicit Concurrent[IO] in scope
  val aDeferred_V2 = concurrentIO.deferred[Int]
  val aRef = concurrentIO.ref(42)

  // capabilities: pure, map/flatMap, raiseError, uncancelable, start(fibers), + ref/deferred

  import com.rockthejvm.utils.general.*
  import scala.concurrent.duration.*

  def eggBoiler(): IO[Unit] = {
    def tickingClock(
        counter: Ref[IO, Int],
        signal: Deferred[IO, Unit]
    ): IO[Unit] = for {
      _ <- IO.sleep(1.second)
      count <- counter.updateAndGet(_ + 1)
      _ <- IO(s"[incrementer] updated counter: $count").debugging
      _ <-
        if (count == 10) signal.complete(IO.unit)
        else tickingClock(counter, signal)
    } yield ()

    def eggReadyNotification(signal: Deferred[IO, Unit]) = for {
      _ <- IO("Egg boiling on some other fiber, waiting...").debugging
      _ <- signal.get
      _ <- IO("EGG READY!").debugging
    } yield ()

    for {
      counter <- Ref[IO].of(0)
      signal <- Deferred[IO, Unit]
      fibNotifier <- eggReadyNotification(signal).start
      clock <- tickingClock(counter, signal).start
      _ <- fibNotifier.join
      _ <- clock.join
    } yield ()
  }

  import cats.syntax.flatMap._
  import cats.syntax.functor._
  import cats.effect.syntax.spawn._ // start extension method

  def polymorphicEggBoiler[F[_]](using concurrent: Concurrent[F]): F[Unit] = {
    def tickingClock(
        counter: Ref[F, Int],
        signal: Deferred[F, Unit]
    ): F[Unit] = for {
      _ <- unsafeSleep[F, Throwable](1.second)
      count <- counter.updateAndGet(_ + 1)
      _ <- concurrent.pure(s"[incrementer] updated counter: $count").debugging
      _ <-
        if (count >= 10) signal.complete(()).void
        else tickingClock(counter, signal)
    } yield ()

    def eggReadyNotification(signal: Deferred[F, Unit]) = for {
      _ <- concurrent
        .pure("Egg boiling on some other fiber, waiting...")
        .debugging
      _ <- signal.get
      _ <- concurrent.pure("EGG READY!").debugging
    } yield ()

    for {
      counter <- concurrent.ref(0)
      signal <- concurrent.deferred[Unit]
      fibNotifier <- concurrent.start(eggReadyNotification(signal))
      clock <- concurrent.start(tickingClock(counter, signal))
      _ <- fibNotifier.join
      _ <- clock.join
    } yield ()
  }

  /** Exercises:
    *   1. Generalize racePair 2. Generalize the Mutex concurrency primitive for
    *      any F
    */

  type RaceResult[A, B] = Either[
    (Outcome[IO, Throwable, A], Fiber[IO, Throwable, B]),
    (Fiber[IO, Throwable, A], Outcome[IO, Throwable, B])
  ]

  type RaceResultGen[F[_], A, B] = Either[
    (Outcome[F, Throwable, A], Fiber[F, Throwable, B]),
    (Fiber[F, Throwable, A], Outcome[F, Throwable, B])
  ]

  type EitherOutcome[A, B] =
    Either[Outcome[IO, Throwable, A], Outcome[IO, Throwable, B]]

  type EitherOutcomeGen[F[_], A, B] =
    Either[Outcome[F, Throwable, A], Outcome[F, Throwable, B]]

  import cats.effect.syntax.monadCancel.* // guaranteeCase extension method
  import cats.effect.syntax.spawn.* // start extension method

  def ourRacePairGen[A, B, F[_]](fa: F[A], fb: F[B])(using
      concurrent: Concurrent[F]
  ): F[RaceResultGen[F, A, B]] =
    concurrent.uncancelable { poll =>
      for {
        signal <- concurrent.deferred[EitherOutcomeGen[F, A, B]]
        fibA <- fa
          .guaranteeCase(outcomeA => signal.complete(Left(outcomeA)).void)
          .start
        fibB <- fb
          .guaranteeCase(outcomeB => signal.complete(Right(outcomeB)).void)
          .start
        result <- poll(signal.get)
          .onCancel { // blocking call - should be cancellable
            for {
              cancelFibA <- fibA.cancel.start
              cancelFibB <- fibB.cancel.start
              _ <- cancelFibA.join
              _ <- cancelFibB.join
            } yield ()
          }
      } yield result match
        case Left(outcomeA)  => Left(outcomeA, fibB)
        case Right(outcomeB) => Right(fibA, outcomeB)
    }

  def ourRacePair[A, B](ioa: IO[A], iob: IO[B]): IO[RaceResult[A, B]] =
    IO.uncancelable { poll =>
      for {
        signal <- Deferred[IO, EitherOutcome[A, B]]
        fibA <- ioa
          .guaranteeCase(outcomeA => signal.complete(Left(outcomeA)).void)
          .start
        fibB <- iob
          .guaranteeCase(outcomeB => signal.complete(Right(outcomeB)).void)
          .start
        result <- poll(signal.get)
          .onCancel { // blocking call - should be cancellable
            for {
              cancelFibA <- fibA.cancel.start
              cancelFibB <- fibB.cancel.start
              _ <- cancelFibA.join
              _ <- cancelFibB.join
            } yield ()
          }
      } yield result match
        case Left(outcomeA)  => Left(outcomeA, fibB)
        case Right(outcomeB) => Right(fibA, outcomeB)
    }

  override def run: IO[Unit] = polymorphicEggBoiler[IO]
}
