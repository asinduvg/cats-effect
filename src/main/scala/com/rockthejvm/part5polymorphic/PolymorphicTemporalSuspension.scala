package com.rockthejvm.part5polymorphic

import cats.effect.{Concurrent, IO, IOApp, Temporal}

import scala.concurrent.duration.FiniteDuration
import com.rockthejvm.utils.general.*
import scala.concurrent.duration.*

object PolymorphicTemporalSuspension extends IOApp.Simple {

  // Temporal - time-blocking effects

  trait MyTemporal[F[_]] extends Concurrent[F] {
    def sleep(
        time: FiniteDuration
    ): F[Unit] // semantically blocks this fiber for a specified time
  }

  // abilities: pure, map/flatMap, raiseError, uncancelable, start, ref/deferred, +sleep

  val temporalIO = Temporal[IO] // given Temporal[IO] in scope
  val chainOfEffects = IO("Loading...").debugging *> IO.sleep(1.second) *> IO(
    "Game ready!"
  ).debugging
  val chainOfEffects_v2 =
    temporalIO.pure("Loading...").debugging *> temporalIO.sleep(1.second) *> IO(
      "Game ready!"
    ).debugging

  /** Exercise: generalize the following piece
    */
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] =
    val timeout = IO.sleep(duration)
    val first = IO.race(io, timeout)

    first.flatMap {
      case Left(a) => IO(a)
      case Right(_) =>
        IO.raiseError(new RuntimeException("Computation timed out"))
    }

  import cats.syntax.flatMap.*

  def timeoutGen[F[_], A](fa: F[A], duration: FiniteDuration)(using
      temporal: Temporal[F]
  ): F[A] =
    val timeout = temporal.sleep(duration)
    val first = temporal.race(fa, timeout)

    first.flatMap {
      case Left(a) => temporal.pure(a)
      case Right(_) =>
        temporal.raiseError(new RuntimeException("Computation timed out"))
    }

  override def run: IO[Unit] = ???

}
